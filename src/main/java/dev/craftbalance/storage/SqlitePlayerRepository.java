package dev.craftbalance.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collection;

/** SQLite persistence. Call from a worker thread; no JDBC operation should block the server thread. */
public final class SqlitePlayerRepository implements AutoCloseable {
    private final Connection connection;
    private boolean unusable;

    public SqlitePlayerRepository(Path databaseFile) throws Exception {
        Path parent = databaseFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        this.connection = opened;
        try {
        try (var statement = opened.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
            try (var journalMode = statement.executeQuery("PRAGMA journal_mode")) {
                if (!journalMode.next() || !"wal".equalsIgnoreCase(journalMode.getString(1)))
                    throw new SQLException("SQLite no confirmó journal_mode=WAL; se requiere WAL para la persistencia configurada.");
            }
            statement.execute("PRAGMA synchronous=FULL");
            try (var synchronous = statement.executeQuery("PRAGMA synchronous")) {
                if (!synchronous.next() || synchronous.getInt(1) != 2)
                    throw new SQLException("SQLite no confirmó synchronous=FULL; se requiere durabilidad ACID en modo WAL.");
            }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_balances (
                      uuid TEXT PRIMARY KEY NOT NULL,
                      daily_date TEXT NOT NULL,
                      normal_balance_ms INTEGER NOT NULL CHECK(normal_balance_ms >= 0),
                      recovered_balance_ms INTEGER NOT NULL CHECK(recovered_balance_ms >= 0),
                      recovery_generated_today_ms INTEGER NOT NULL CHECK(recovery_generated_today_ms >= 0),
                      real_played_today_ms INTEGER NOT NULL CHECK(real_played_today_ms >= 0),
                      last_disconnect_epoch_ms INTEGER NOT NULL CHECK(last_disconnect_epoch_ms >= 0),
                      normal_exhausted_today INTEGER NOT NULL CHECK(normal_exhausted_today IN (0, 1)),
                      recovery_fractional_numerator INTEGER NOT NULL DEFAULT 0
                        CHECK(recovery_fractional_numerator >= 0 AND recovery_fractional_numerator < 60000),
                      consumption_fractional_ms REAL NOT NULL DEFAULT 0
                        CHECK(consumption_fractional_ms >= 0 AND consumption_fractional_ms < 1)
                    )
                    """);
            boolean hasRecoveryFraction = false;
            boolean hasConsumptionFraction = false;
            try (var columns = statement.executeQuery("PRAGMA table_info(player_balances)")) {
                while (columns.next()) {
                    if ("recovery_fractional_numerator".equalsIgnoreCase(columns.getString("name"))) {
                        hasRecoveryFraction = true;
                    }
                    if ("consumption_fractional_ms".equalsIgnoreCase(columns.getString("name")))
                        hasConsumptionFraction = true;
                }
            }
            if (!hasRecoveryFraction) statement.execute("ALTER TABLE player_balances ADD COLUMN recovery_fractional_numerator INTEGER NOT NULL DEFAULT 0 CHECK(recovery_fractional_numerator >= 0 AND recovery_fractional_numerator < 60000)");
            if (!hasConsumptionFraction) statement.execute("ALTER TABLE player_balances ADD COLUMN consumption_fractional_ms REAL NOT NULL DEFAULT 0 CHECK(consumption_fractional_ms >= 0 AND consumption_fractional_ms < 1)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS admin_audit (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      admin_uuid TEXT NOT NULL,
                      player_uuid TEXT NOT NULL,
                      adjustment_type TEXT NOT NULL DEFAULT 'unknown',
                      changed_at_epoch_ms INTEGER NOT NULL,
                      normal_before_ms INTEGER NOT NULL,
                      normal_after_ms INTEGER NOT NULL,
                      recovered_before_ms INTEGER NOT NULL,
                      recovered_after_ms INTEGER NOT NULL
                    )
                    """);
            boolean hasAdjustmentType = false;
            try (var columns = statement.executeQuery("PRAGMA table_info(admin_audit)")) {
                while (columns.next()) {
                    if ("adjustment_type".equalsIgnoreCase(columns.getString("name"))) {
                        hasAdjustmentType = true;
                        break;
                    }
                }
            }
            if (!hasAdjustmentType) statement.execute("ALTER TABLE admin_audit ADD COLUMN adjustment_type TEXT NOT NULL DEFAULT 'unknown'");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_admin_audit_player_id ON admin_audit(player_uuid, id DESC)");
        }
        migrateRecoveryFractionScale();
        } catch (Exception failure) {
            try { opened.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Converts carried fractions from the pre-millisecond recovery rate exactly once. */
    private void migrateRecoveryFractionScale() throws SQLException {
        int schemaVersion;
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA user_version")) {
            schemaVersion = result.next() ? result.getInt(1) : 0;
        }
        if (schemaVersion >= 1) return;

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        SQLException transactionFailure = null;
        try (var statement = connection.createStatement()) {
            // Old fractions were accumulated at 1/1000 of the corrected scale. Keep the
            // fractional millisecond remainder while discarding only sub-1/60 ms precision.
            statement.executeUpdate("UPDATE player_balances SET recovery_fractional_numerator = "
                    + "(recovery_fractional_numerator * 1000) % 60000");
            statement.execute("PRAGMA user_version = 1");
            connection.commit();
        } catch (SQLException failure) {
            transactionFailure = failure;
            try { connection.rollback(); }
            catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally {
            try { connection.setAutoCommit(autoCommit); }
            catch (SQLException cleanupFailure) {
                if (transactionFailure != null) transactionFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    public synchronized Optional<PlayerBalance> find(UUID uuid) throws SQLException {
        requireUsable();
        try (var query = connection.prepareStatement("SELECT * FROM player_balances WHERE uuid = ?")) {
            query.setString(1, uuid.toString());
            try (var result = query.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new PlayerBalance(uuid, LocalDate.parse(result.getString("daily_date")),
                        result.getLong("normal_balance_ms"), result.getLong("recovered_balance_ms"),
                        result.getLong("recovery_generated_today_ms"), result.getLong("real_played_today_ms"),
                        result.getLong("last_disconnect_epoch_ms"), result.getInt("normal_exhausted_today") != 0,
                        result.getLong("recovery_fractional_numerator"),
                        result.getDouble("consumption_fractional_ms")));
            }
        }
    }

    public synchronized List<PlayerBalance> findAll() throws SQLException {
        requireUsable();
        List<PlayerBalance> balances = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT * FROM player_balances");
             var result = query.executeQuery()) {
            while (result.next()) {
                balances.add(new PlayerBalance(UUID.fromString(result.getString("uuid")),
                        LocalDate.parse(result.getString("daily_date")),
                        result.getLong("normal_balance_ms"), result.getLong("recovered_balance_ms"),
                        result.getLong("recovery_generated_today_ms"), result.getLong("real_played_today_ms"),
                        result.getLong("last_disconnect_epoch_ms"), result.getInt("normal_exhausted_today") != 0,
                        result.getLong("recovery_fractional_numerator"),
                        result.getDouble("consumption_fractional_ms")));
            }
        }
        return balances;
    }

    public synchronized List<AdminAdjustment> findAdjustments(UUID playerUuid, int limit) throws SQLException {
        requireUsable();
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("El límite de auditoría debe estar entre 1 y 20.");
        List<AdminAdjustment> adjustments = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT admin_uuid, player_uuid, adjustment_type, changed_at_epoch_ms,
                       normal_before_ms, normal_after_ms, recovered_before_ms, recovered_after_ms
                FROM admin_audit WHERE player_uuid = ? ORDER BY id DESC LIMIT ?
                """)) {
            query.setString(1, playerUuid.toString());
            query.setInt(2, limit);
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    adjustments.add(new AdminAdjustment(UUID.fromString(result.getString("admin_uuid")),
                            UUID.fromString(result.getString("player_uuid")), result.getString("adjustment_type"),
                            result.getLong("changed_at_epoch_ms"), result.getLong("normal_before_ms"),
                            result.getLong("normal_after_ms"), result.getLong("recovered_before_ms"),
                            result.getLong("recovered_after_ms")));
                }
            }
        }
        return adjustments;
    }

    public synchronized void save(PlayerBalance balance) throws SQLException {
        requireUsable();
        try (var statement = connection.prepareStatement("""
                INSERT INTO player_balances(uuid, daily_date, normal_balance_ms, recovered_balance_ms,
                    recovery_generated_today_ms, real_played_today_ms, last_disconnect_epoch_ms,
                    normal_exhausted_today, recovery_fractional_numerator, consumption_fractional_ms)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET daily_date=excluded.daily_date,
                    normal_balance_ms=excluded.normal_balance_ms, recovered_balance_ms=excluded.recovered_balance_ms,
                    recovery_generated_today_ms=excluded.recovery_generated_today_ms,
                    real_played_today_ms=excluded.real_played_today_ms,
                    last_disconnect_epoch_ms=excluded.last_disconnect_epoch_ms,
                    normal_exhausted_today=excluded.normal_exhausted_today,
                    recovery_fractional_numerator=excluded.recovery_fractional_numerator,
                    consumption_fractional_ms=excluded.consumption_fractional_ms
                """)) {
            statement.setString(1, balance.uuid().toString());
            statement.setString(2, balance.dailyDate().toString());
            statement.setLong(3, balance.normalBalanceMillis());
            statement.setLong(4, balance.recoveredBalanceMillis());
            statement.setLong(5, balance.recoveryGeneratedTodayMillis());
            statement.setLong(6, balance.realPlayedTodayMillis());
            statement.setLong(7, balance.lastDisconnectEpochMillis());
            statement.setInt(8, balance.normalExhaustedToday() ? 1 : 0);
            statement.setLong(9, balance.recoveryFractionalNumerator());
            statement.setDouble(10, balance.consumptionFractionalMillis());
            statement.executeUpdate();
        }
    }

    public synchronized void saveAll(Collection<PlayerBalance> balances) throws SQLException {
        requireUsable();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable transactionFailure = null;
        boolean committed = false;
        try {
            for (PlayerBalance balance : balances) save(balance);
            connection.commit();
            committed = true;
        } catch (SQLException | RuntimeException exception) {
            transactionFailure = exception;
            rollbackAfterFailure(exception);
            throw exception;
        } finally {
            restoreAutoCommit(autoCommit, transactionFailure, committed);
        }
    }

    public synchronized void recordAdjustment(UUID adminUuid, PlayerBalance before, PlayerBalance after,
                                              String adjustmentType, long atEpochMillis) throws SQLException {
        requireUsable();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable transactionFailure = null;
        boolean committed = false;
        try {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO admin_audit(admin_uuid, player_uuid, adjustment_type, changed_at_epoch_ms,
                        normal_before_ms, normal_after_ms, recovered_before_ms, recovered_after_ms)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, adminUuid.toString());
                statement.setString(2, after.uuid().toString());
                statement.setString(3, adjustmentType);
                statement.setLong(4, atEpochMillis);
                statement.setLong(5, before.normalBalanceMillis());
                statement.setLong(6, after.normalBalanceMillis());
                statement.setLong(7, before.recoveredBalanceMillis());
                statement.setLong(8, after.recoveredBalanceMillis());
                statement.executeUpdate();
            }
            save(after);
            connection.commit();
            committed = true;
        } catch (SQLException | RuntimeException exception) {
            transactionFailure = exception;
            rollbackAfterFailure(exception);
            throw exception;
        } finally {
            restoreAutoCommit(autoCommit, transactionFailure, committed);
        }
    }

    private void rollbackAfterFailure(Throwable originalFailure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            unusable = true;
            originalFailure.addSuppressed(rollbackFailure);
            closeAfterFailure(originalFailure);
        }
    }

    private void restoreAutoCommit(boolean autoCommit, Throwable originalFailure, boolean committed) throws SQLException {
        if (unusable) return;
        try {
            if (!connection.isClosed()) connection.setAutoCommit(autoCommit);
        } catch (SQLException cleanupFailure) {
            unusable = true;
            if (originalFailure == null) {
                closeAfterFailure(cleanupFailure);
                // The caller can safely treat the operation as successful once commit returned.
                // The connection is closed, so a subsequent operation fails closed instead
                // of rolling in-memory state back behind a transaction already on disk.
                if (!committed) throw cleanupFailure;
                return;
            }
            originalFailure.addSuppressed(cleanupFailure);
            closeAfterFailure(originalFailure);
        }
    }

    private void requireUsable() throws SQLException {
        if (unusable) throw new SQLException("La conexión SQLite se marcó como inutilizable tras fallar una transacción.");
    }

    private void closeAfterFailure(Throwable originalFailure) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
