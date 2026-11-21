package dev.craftbalance.server;

import dev.craftbalance.config.BalanceConfig;
import dev.craftbalance.config.ConfigManager;
import dev.craftbalance.storage.PlayerBalance;
import dev.craftbalance.storage.PlayerPersistence;
import dev.craftbalance.storage.AdminAdjustment;
import dev.craftbalance.time.BalanceEngine;
import dev.craftbalance.time.DailyResetService;
import dev.craftbalance.time.TimePolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;

/** Main-thread game integration. All SQLite access is delegated to PlayerPersistence. */
public final class BalanceRuntime {
    private static final long SECOND = 1_000L;
    private static final long SAVE_INTERVAL = 10_000L;

    private final Path configFile;
    private final Path configDirectory;
    private final Logger logger;
    private final MonotonicEpochClock clock = new MonotonicEpochClock();
    private final DailyResetService resetService = new DailyResetService();
    private final Map<UUID, PlayerBalance> balances = new HashMap<>();
    private final Map<UUID, Presence> presence = new HashMap<>();
    private final Map<UUID, Long> recoveryCursors = new HashMap<>();
    private final Map<UUID, Set<Integer>> warningsSent = new HashMap<>();
    private final Map<UUID, BalanceEngine.StopReason> deferredStopReasons = new HashMap<>();
    private final Set<UUID> rejectedJoins = new HashSet<>();
    private final Map<UUID, PendingAdjustment> pendingAdjustmentAudits = new HashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingAdjustmentFutures = new HashMap<>();

    private MinecraftServer server;
    private PlayerPersistence persistence;
    private volatile long lifecycleGeneration;
    private volatile boolean ready;
    private boolean persistenceFailed;
    private long lastUpdateAt;
    private long lastSaveAt;
    private int activePlayers;

    public BalanceRuntime(Path configDirectory, Logger logger) {
        this.configDirectory = configDirectory;
        this.configFile = configDirectory.resolve("config.yml");
        this.logger = logger;
    }

    public void start(MinecraftServer server) {
        this.server = server;
        long generation = ++lifecycleGeneration;
        ready = false;
        persistenceFailed = false;
        balances.clear();
        presence.clear();
        recoveryCursors.clear();
        warningsSent.clear();
        deferredStopReasons.clear();
        rejectedJoins.clear();
        pendingAdjustmentAudits.clear();
        pendingAdjustmentFutures.clear();
        activePlayers = 0;
        BalanceConfig startupConfig = ConfigManager.current();
        this.persistence = new PlayerPersistence(configDirectory.resolve(startupConfig.databaseFile()));
        long now = nowMillis();
        lastUpdateAt = now;
        lastSaveAt = now;
        persistence.loadAll().whenComplete((loaded, failure) -> {
            if (generation != lifecycleGeneration) return;
            try {
                server.execute(() -> {
                if (generation != lifecycleGeneration) return;
                if (failure != null) {
                    persistenceFailed = true;
                    logger.error("No se pudo abrir la base de datos de CraftBalance; el control de tiempo no se activará.", unwrap(failure));
                    try {
                        persistence.close();
                    } catch (Exception closeFailure) {
                        logger.error("No se pudo cerrar el trabajador SQLite tras fallar la carga.", unwrap(closeFailure));
                    }
                    return;
                }
                BalanceConfig loadedConfig = ConfigManager.current();
                long loadedAt = nowMillis();
                for (PlayerBalance balance : loaded) {
                    PlayerBalance current = reconcileBalance(balance, loadedConfig, loadedAt);
                    // A crash can leave a previously connected player without a disconnect mark.
                    // Start a fresh recovery wait now; never credit time spent while the server
                    // was offline. This also supports recovery configurations that do not require
                    // normal balance exhaustion first.
                    if (current.lastDisconnectEpochMillis() == 0
                            || current.lastDisconnectEpochMillis() > loadedAt) {
                        current = current.withDisconnect(loadedAt, current.normalExhaustedToday());
                    }
                    balances.put(current.uuid(), current);
                    advanceRecoveryCursor(current.uuid(), loadedAt);
                }
                ready = true;
                if (!balances.isEmpty()) persistSoon();
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) onJoin(player, loadedAt);
                logger.info("CraftBalance cargó {} jugadores desde SQLite.", balances.size());
                });
            } catch (RuntimeException schedulingFailure) {
                logger.warn("No se pudo completar la carga de CraftBalance porque el servidor ya está cerrándose.", schedulingFailure);
            }
        });
    }

    public void onJoin(ServerPlayerEntity player, long now) {
        if (persistenceFailed) {
            player.networkHandler.disconnect(Text.translatable("craftbalance.message.persistence_unavailable"));
            return;
        }
        if (!ready) {
            player.networkHandler.disconnect(Text.translatable("craftbalance.message.loading"));
            return;
        }
        UUID uuid = player.getUuid();
        if (pendingAdjustmentAudits.containsKey(uuid)) {
            player.networkHandler.disconnect(Text.translatable("craftbalance.message.balance_updating"));
            return;
        }
        BalanceConfig config = ConfigManager.current();
        if (!ConfigManager.current().enabled()) {
            advanceRecoveryCursors(now);
            PlayerBalance before = balances.get(uuid);
            PlayerBalance balance = before == null ? freshBalance(uuid, config, now) : before;
            balance = resetService.applyIfNeeded(balance, config, now);
            balances.put(uuid, balance);
            if (before == null || !before.equals(balance)) persistSoon();
            presence.put(uuid, newPresence(now, player));
            refreshActivePlayers(now, config);
            if (config.loginSummaryEnabled()) {
                player.sendMessage(Text.translatable("craftbalance.message.login_paused",
                        format(balance.normalBalanceMillis()), format(balance.recoveredBalanceMillis())), false);
                player.sendMessage(Text.translatable("craftbalance.message.estimate_paused"), false);
            }
            return;
        }
        if (config.recoveryEnabled())
            accrueOffline(now, Math.max(0, now - lastUpdateAt), config, uuid);
        settleConnectedSessions(now, config, player);
        advanceRecoveryCursors(now, uuid);
        PlayerBalance balance = balances.computeIfAbsent(uuid, ignored -> freshBalance(uuid, config, now));
        LocalDate previousDailyDate = balance.dailyDate();
        balance = resetService.applyIfNeeded(balance, config, now);
        if (!balance.dailyDate().equals(previousDailyDate))
            moveRecoveryCursorToDailyStart(uuid, balance.dailyDate(), config);
        balance = accrueRecoveryBeforeJoin(uuid, balance, now, config);
        balances.put(uuid, balance);
        warningsSent.remove(uuid);
        if (balance.realPlayedTodayMillis() >= ConfigManager.current().maxRealMinutes() * 60_000L) {
            persistSoon();
            rejectedJoins.add(uuid);
            refreshActivePlayers(now, config, uuid);
            player.networkHandler.disconnect(Text.translatable("craftbalance.message.daily_limit",
                    resetService.nextResetLabel(config, now, balance.dailyDate()), config.timezone().getId()));
            return;
        }
        if (!TimePolicy.canRejoin(balance.normalBalanceMillis(), balance.recoveredBalanceMillis(), ConfigManager.current())) {
            persistSoon();
            rejectedJoins.add(uuid);
            refreshActivePlayers(now, config, uuid);
            player.networkHandler.disconnect(rejoinBlockReason(balance, now));
            return;
        }
        balance = new PlayerBalance(balance.uuid(), balance.dailyDate(), balance.normalBalanceMillis(),
                balance.recoveredBalanceMillis(), balance.recoveryGeneratedTodayMillis(),
                balance.realPlayedTodayMillis(), 0, balance.normalExhaustedToday(),
                balance.recoveryFractionalNumerator(), balance.consumptionFractionalMillis());
        balances.put(uuid, balance);
        presence.put(uuid, newPresence(now, player));
        advanceRecoveryCursor(uuid, now);
        refreshActivePlayers(now, config);
        if (ConfigManager.current().loginSummaryEnabled()) {
            player.sendMessage(Text.translatable("craftbalance.message.login_summary",
                    format(balance.normalBalanceMillis()), format(balance.recoveredBalanceMillis()),
                    format(Math.max(0, ConfigManager.current().maxRealMinutes() * 60_000L - balance.realPlayedTodayMillis()))), false);
            player.sendMessage(Text.translatable("craftbalance.message.playable_estimate",
                    format(estimatedPlayableMillis(uuid))), false);
        }
        persistSoon();
    }

    public void onDisconnect(ServerPlayerEntity player, long now) {
        UUID uuid = player.getUuid();
        BalanceConfig config = ConfigManager.current();
        // Settle offline recovery with the active-player snapshot that was in effect
        // before this disconnect. Otherwise the departing player could be removed
        // from presence first and the entire interval since the previous tick would
        // be credited using the smaller post-disconnect group.
        if (ready && config.enabled() && config.recoveryEnabled())
            accrueOffline(now, Math.max(0, now - lastUpdateAt), config);
        if (ready && config.enabled()) settleConnectedSessions(now, config, player);
        Presence session = presence.remove(uuid);
        warningsSent.remove(uuid);
        deferredStopReasons.remove(uuid);
        if (rejectedJoins.remove(uuid)) {
            refreshActivePlayers(now, config, uuid);
            return;
        }
        PlayerBalance balance = balances.get(uuid);
        if (balance == null || !ready) return;
        if (pendingAdjustmentAudits.containsKey(uuid)) {
            if (session != null) {
                balances.put(uuid, balance.withDisconnect(now, balance.normalExhaustedToday()));
                pendingAdjustmentAudits.computeIfPresent(uuid,
                        (ignored, pending) -> pending.withDisconnectAt(now));
                advanceRecoveryCursor(uuid, now);
            }
            refreshActivePlayers(now, config, uuid);
            persistSoon();
            return;
        }
        if (session != null && config.enabled()) {
            long finalInterval = Math.max(0, now - session.lastAccountedAt);
            if (finalInterval > 0) {
                boolean isAfk = config.afkEnabled()
                        && now - session.lastActivityAt >= config.inactivityMinutes() * 60_000L;
                int count = isAfk ? 1 : Math.max(1, activePlayers);
                boolean consume = !isAfk || config.afkConsumesOwnTime();
                balance = resetService.advanceConnected(balance, session.lastAccountedAt,
                        finalInterval, count, config, consume).balance();
            }
        }
        balances.put(uuid, balance.withDisconnect(now, balance.normalExhaustedToday()));
        advanceRecoveryCursor(uuid, now);
        refreshActivePlayers(now, config, uuid);
        persistSoon();
    }

    public void markActivity(ServerPlayerEntity player) {
        Presence current = presence.get(player.getUuid());
        if (current == null) return;
        long now = nowMillis();
        BalanceConfig config = ConfigManager.current();
        boolean wasAfk = config.afkEnabled()
                && now - current.lastActivityAt >= config.inactivityMinutes() * 60_000L;
        if (wasAfk && config.enabled() && ready) {
            if (config.recoveryEnabled())
                accrueOffline(now, Math.max(0, now - lastUpdateAt), config);
            settleConnectedSessions(now, config, player);
        }
        current.lastActivityAt = now;
        current.x = player.getX(); current.y = player.getY(); current.z = player.getZ();
        if (ready && server != null) refreshActivePlayers(now, config);
    }

    /** Samples passive activity signals that Fabric does not expose as interaction events. */
    private void updatePresenceActivity(long now) {
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            if (rejectedJoins.contains(uuid)) continue;
            Presence activity = presence.get(uuid);
            if (activity == null) {
                presence.put(uuid, newPresence(now, player));
                continue;
            }
            double dx = player.getX() - activity.x;
            double dy = player.getY() - activity.y;
            double dz = player.getZ() - activity.z;
            float newYaw = player.getYaw();
            float newPitch = player.getPitch();
            boolean lookedAround = angularDistance(newYaw, activity.yaw) > 1.0f
                    || Math.abs(newPitch - activity.pitch) > 1.0f;
            boolean uiChanged = activity.updateScreenState(player);
            boolean moved = dx * dx + dy * dy + dz * dz > 0.0025;
            if (moved) {
                activity.x = player.getX(); activity.y = player.getY(); activity.z = player.getZ();
            }
            if (moved || lookedAround || uiChanged) activity.lastActivityAt = now;
            activity.yaw = newYaw;
            activity.pitch = newPitch;
        }
    }

    public void tick(long now) {
        tick(now, false);
    }

    public long nowMillis() {
        return clock.millis();
    }

    private void tick(long now, boolean forceAccounting) {
        if (!ready || persistenceFailed) return;
        BalanceConfig config = ConfigManager.current();
        if (!config.enabled()) {
            lastUpdateAt = now;
            deferredStopReasons.clear();
            updatePresenceActivity(now);
            boolean resetApplied = false;
            for (Map.Entry<UUID, PlayerBalance> entry : new ArrayList<>(balances.entrySet())) {
                PlayerBalance before = entry.getValue();
                PlayerBalance dated = resetService.applyIfNeeded(before, config, now);
                if (!dated.equals(before)) {
                    balances.put(entry.getKey(), dated);
                    warningsSent.remove(entry.getKey());
                    moveRecoveryCursorToDailyStart(entry.getKey(), dated.dailyDate(), config);
                    resetApplied = true;
                }
            }
            for (Presence session : presence.values()) session.lastAccountedAt = now;
            advanceRecoveryCursors(now);
            refreshActivePlayers(now, config);
            if (resetApplied) persistSoon();
            return;
        }
        if (!forceAccounting && now - lastUpdateAt < SECOND) return;
        long elapsed = Math.max(0, now - lastUpdateAt);
        lastUpdateAt = now;
        boolean dirty = false;

        Set<UUID> onlineUuids = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) onlineUuids.add(player.getUuid());
        onlineUuids.addAll(presence.keySet());
        for (Map.Entry<UUID, PlayerBalance> entry : new ArrayList<>(balances.entrySet())) {
            if (pendingAdjustmentAudits.containsKey(entry.getKey())) continue;
            if (onlineUuids.contains(entry.getKey())) continue;
            PlayerBalance before = entry.getValue();
            if (config.recoveryEnabled()) continue;
            PlayerBalance dated = resetService.applyIfNeeded(before, config, now);
            if (!dated.dailyDate().equals(before.dailyDate())) {
                balances.put(entry.getKey(), dated);
                warningsSent.remove(entry.getKey());
                moveRecoveryCursorToDailyStart(entry.getKey(), dated.dailyDate(), config);
                dirty = true;
            }
        }

        // Settle using the activity snapshot from the preceding interval. The position and
        // inventory sample below may discover activity at this tick, which must only affect
        // the group factor from this instant onward.
        settleConnectedSessions(now, config, null);
        if (config.recoveryEnabled()) dirty |= accrueOffline(now, elapsed, config);
        else advanceRecoveryCursors(now);
        updatePresenceActivity(now);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            if (rejectedJoins.contains(uuid)) continue;
            boolean hasBalance = balances.containsKey(uuid);
            PlayerBalance balance = balances.computeIfAbsent(uuid, ignored -> freshBalance(uuid, config, now));
            if (!hasBalance) dirty = true;
            Presence activity = presence.get(uuid);
            if (activity == null) presence.put(uuid, newPresence(now, player));
        }

        refreshActivePlayers(now, config);
        long afkMillis = config.inactivityMinutes() * 60_000L;

        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            UUID uuid = player.getUuid();
            if (rejectedJoins.contains(uuid)) continue;
            PlayerBalance before = balances.get(uuid);
            if (before == null) continue;
            Presence session = presence.get(uuid);
            if (session == null) {
                session = newPresence(now, player);
                presence.put(uuid, session);
            }
            if (pendingAdjustmentAudits.containsKey(uuid)) {
                // Freeze balance mutation until the SQL audit settles; the pending record retains
                // the accounting start so this interval can be applied once afterward.
                session.lastAccountedAt = now;
                continue;
            }
            boolean isAfk = config.afkEnabled() && now - session.lastActivityAt >= afkMillis;
            int factorPlayers = isAfk ? 1 : Math.max(1, activePlayers);
            long playerElapsed = Math.max(0, now - session.lastAccountedAt);
            long beforeRemaining = effectiveRemaining(before, factorPlayers, config);
            boolean consumeBalance = !isAfk || config.afkConsumesOwnTime();
            BalanceEngine.Advance result = resetService.advanceConnected(before, session.lastAccountedAt,
                    playerElapsed, factorPlayers, config, consumeBalance);
            PlayerBalance after = result.balance();
            BalanceEngine.StopReason stopReason = result.stopReason();
            if (stopReason == BalanceEngine.StopReason.NONE)
                stopReason = deferredStopReasons.getOrDefault(uuid, BalanceEngine.StopReason.NONE);
            deferredStopReasons.remove(uuid);
            balances.put(uuid, after);
            session.lastAccountedAt = now;
            if (!before.dailyDate().equals(after.dailyDate())) {
                warningsSent.remove(uuid);
                dirty = true;
            } else if (consumeBalance) {
                warnIfCrossed(player, beforeRemaining, effectiveRemaining(after, factorPlayers, config), config);
            }
            double displayedRate = !consumeBalance ? 0.0 : after.normalBalanceMillis() > 0
                    ? config.consumptionFor(factorPlayers) : config.recoveredConsumption();
            if (config.actionBarEnabled()) player.sendMessage(Text.translatable("craftbalance.message.action_bar",
                    format(after.normalBalanceMillis()), format(after.recoveredBalanceMillis()),
                    BalanceFormatting.percent(displayedRate)), true);
            if (stopReason == BalanceEngine.StopReason.DAILY_REAL_LIMIT) {
                player.networkHandler.disconnect(Text.translatable("craftbalance.message.daily_limit",
                        resetService.nextResetLabel(config, now, after.dailyDate()), config.timezone().getId()));
                onDisconnect(player, now);
            } else if (stopReason == BalanceEngine.StopReason.BALANCE_EMPTY) {
                player.networkHandler.disconnect(balanceExhaustedMessage(config, after, now));
                onDisconnect(player, now);
            }
        }

        if (dirty || now - lastSaveAt >= SAVE_INTERVAL) {
            persistSoon();
            lastSaveAt = now;
        }
    }

    private boolean accrueOffline(long now, long elapsed, BalanceConfig config) {
        return accrueOffline(now, elapsed, config, null);
    }

    private boolean accrueOffline(long now, long elapsed, BalanceConfig config, UUID excluded) {
        long waitMillis = config.initialWaitMinutes() * 60_000L;
        long realCap = config.maxRealMinutes() * 60_000L;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) online.add(player.getUuid());
        online.addAll(presence.keySet());
        long earliestRecoveryCursor = Math.max(0, now - elapsed);
        for (Map.Entry<UUID, PlayerBalance> entry : balances.entrySet()) {
            UUID uuid = entry.getKey();
            if (online.contains(uuid) || uuid.equals(excluded) || pendingAdjustmentAudits.containsKey(uuid)) continue;
            earliestRecoveryCursor = Math.min(earliestRecoveryCursor,
                    recoveryCursors.getOrDefault(uuid, Math.max(0, now - elapsed)));
        }
        TreeSet<Long> afkBoundaries = new TreeSet<>();
        if (config.afkEnabled()) {
            long afkMillis = config.inactivityMinutes() * 60_000L;
            for (Presence session : presence.values()) {
                long boundary = session.lastActivityAt + afkMillis;
                if (boundary > earliestRecoveryCursor && boundary < now) afkBoundaries.add(boundary);
            }
        }
        boolean resetApplied = false;
        for (Map.Entry<UUID, PlayerBalance> entry : new ArrayList<>(balances.entrySet())) {
            UUID uuid = entry.getKey();
            if (uuid.equals(excluded)) continue;
            PlayerBalance balance = entry.getValue();
            if (pendingAdjustmentAudits.containsKey(uuid)) {
                continue;
            }
            long cursor = recoveryCursors.getOrDefault(uuid, now - elapsed);
            if (now < cursor || online.contains(uuid)) continue;
            long waitEndsAt = balance.lastDisconnectEpochMillis() > 0
                    ? balance.lastDisconnectEpochMillis() + waitMillis : Long.MAX_VALUE;
            PlayerBalance updated = balance;
            long segmentStart = cursor;
            while (true) {
                LocalDate nextDate = updated.dailyDate().plusDays(1);
                long resetAt = resetService.startOfDailyDate(nextDate, config);
                if (segmentStart >= resetAt) {
                    updated = updated.resetFor(nextDate, config.dailyMinutes() * 60_000L);
                    resetApplied = true;
                    continue;
                }
                if (segmentStart >= now) break;
                long segmentEnd = Math.min(now, resetAt);
                if (config.recoveryEnabled()
                        && (!config.recoveryOnlyAfterNormalExhaustion() || updated.normalExhaustedToday())
                        && updated.realPlayedTodayMillis() < realCap) {
                    long eligibleStart = Math.max(segmentStart, waitEndsAt);
                    if (eligibleStart < segmentEnd) {
                        long recoverySegmentStart = eligibleStart;
                        for (long boundary : afkBoundaries) {
                            if (boundary <= recoverySegmentStart) continue;
                            if (boundary >= segmentEnd) break;
                            updated = accrueRecoverySlice(uuid, updated, recoverySegmentStart,
                                    boundary, config);
                            recoverySegmentStart = boundary;
                        }
                        updated = accrueRecoverySlice(uuid, updated, recoverySegmentStart,
                                segmentEnd, config);
                    }
                }
                segmentStart = segmentEnd;
            }
            advanceRecoveryCursor(uuid, now);
            if (!updated.equals(balance)) {
                balances.put(uuid, updated);
            }
        }
        return resetApplied;
    }

    private void advanceRecoveryCursors(long now) {
        advanceRecoveryCursors(now, null);
    }

    private void advanceRecoveryCursors(long now, UUID excluded) {
        for (UUID uuid : balances.keySet()) if (!uuid.equals(excluded)) advanceRecoveryCursor(uuid, now);
    }

    private void advanceRecoveryCursor(UUID uuid, long epochMillis) {
        recoveryCursors.merge(uuid, epochMillis, Math::max);
    }

    private void moveRecoveryCursorToDailyStart(UUID uuid, LocalDate dailyDate, BalanceConfig config) {
        long dailyStart = resetService.startOfDailyDate(dailyDate, config);
        advanceRecoveryCursor(uuid, dailyStart);
    }

    private PlayerBalance accrueRecoveryBeforeJoin(UUID uuid, PlayerBalance balance, long now, BalanceConfig config) {
        long realCap = config.maxRealMinutes() * 60_000L;
        if (!config.recoveryEnabled() || (config.recoveryOnlyAfterNormalExhaustion() && !balance.normalExhaustedToday())
                || balance.lastDisconnectEpochMillis() <= 0 || balance.realPlayedTodayMillis() >= realCap) {
            advanceRecoveryCursor(uuid, now);
            return balance;
        }
        long cursor = recoveryCursors.getOrDefault(uuid, now);
        long eligibleStart = Math.max(cursor, balance.lastDisconnectEpochMillis() + config.initialWaitMinutes() * 60_000L);
        long eligibleMillis = Math.max(0, now - eligibleStart);
        TimePolicy.RecoveryCredit credit = TimePolicy.recoveryCredit(eligibleMillis,
                countActiveFriends(uuid, now, config), balance.recoveryGeneratedTodayMillis(),
                balance.recoveredBalanceMillis(), balance.recoveryFractionalNumerator(), config);
        advanceRecoveryCursor(uuid, now);
        return BalanceEngine.addRecovery(balance, credit.earnedMillis(), credit.fractionalNumerator(), config);
    }

    private int countActiveFriends(UUID excluded, long now, BalanceConfig config) {
        long afkMillis = config.inactivityMinutes() * 60_000L;
        return countActivePresence(now, config, afkMillis, excluded);
    }

    private PlayerBalance accrueRecoverySlice(UUID uuid, PlayerBalance balance, long start, long end,
                                              BalanceConfig config) {
        if (end <= start) return balance;
        TimePolicy.RecoveryCredit credit = TimePolicy.recoveryCredit(end - start,
                countActiveFriends(uuid, start, config), balance.recoveryGeneratedTodayMillis(),
                balance.recoveredBalanceMillis(), balance.recoveryFractionalNumerator(), config);
        return BalanceEngine.addRecovery(balance, credit.earnedMillis(),
                credit.fractionalNumerator(), config);
    }

    private void warnIfCrossed(ServerPlayerEntity player, long before, long after, BalanceConfig config) {
        Set<Integer> sent = warningsSent.computeIfAbsent(player.getUuid(), ignored -> new HashSet<>());
        for (int minutes : config.warningMinutes()) {
            long threshold = minutes * 60_000L;
            if (before > threshold && after <= threshold && sent.add(minutes))
                player.sendMessage(Text.translatable("craftbalance.message.warning", minutes), false);
        }
    }

    private static long effectiveRemaining(PlayerBalance balance, int activeCount, BalanceConfig config) {
        long normalRemaining = (long) Math.ceil(balance.normalBalanceMillis() / config.consumptionFor(activeCount));
        long recoveredRemaining = (long) Math.ceil(balance.recoveredBalanceMillis() / config.recoveredConsumption());
        if (Long.MAX_VALUE - normalRemaining < recoveredRemaining) return Long.MAX_VALUE;
        return normalRemaining + recoveredRemaining;
    }

    private void refreshActivePlayers(long now, BalanceConfig config) {
        refreshActivePlayers(now, config, null);
    }

    private void refreshActivePlayers(long now, BalanceConfig config, UUID excluded) {
        long afkMillis = config.inactivityMinutes() * 60_000L;
        int count = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getUuid().equals(excluded)) continue;
            if (rejectedJoins.contains(player.getUuid())) continue;
            Presence activity = presence.get(player.getUuid());
            if (activity == null) continue;
            boolean isAfk = config.afkEnabled() && now - activity.lastActivityAt >= afkMillis;
            if (!isAfk || config.afkCountsAsActiveForOthers()) count++;
        }
        activePlayers = count;
    }

    /** Settles current sessions at a presence boundary using the pre-change group size. */
    private void settleConnectedSessions(long now, BalanceConfig config, ServerPlayerEntity transitioningPlayer) {
        if (server == null || !ready) return;
        long afkMillis = config.inactivityMinutes() * 60_000L;
        long earliestSessionStart = presence.values().stream()
                .mapToLong(session -> session.lastAccountedAt).min().orElse(now);
        TreeSet<Long> afkBoundaries = new TreeSet<>();
        if (config.afkEnabled()) {
            for (Presence session : presence.values()) {
                long boundary = session.lastActivityAt + afkMillis;
                if (boundary > earliestSessionStart && boundary < now) afkBoundaries.add(boundary);
            }
        }
        for (Map.Entry<UUID, Presence> entry : new ArrayList<>(presence.entrySet())) {
            UUID uuid = entry.getKey();
            Presence session = entry.getValue();
            ServerPlayerEntity connected = transitioningPlayer != null && uuid.equals(transitioningPlayer.getUuid())
                    ? transitioningPlayer : server.getPlayerManager().getPlayer(uuid);
            PlayerBalance balance = balances.get(uuid);
            if (balance == null || pendingAdjustmentAudits.containsKey(uuid)) continue;
            long cursor = session.lastAccountedAt;
            for (long boundary : afkBoundaries) {
                if (boundary <= cursor) continue;
                if (boundary >= now) break;
                if (settleSessionSlice(uuid, connected, balance, session, cursor, boundary, config, afkMillis)) {
                    cursor = now;
                    break;
                }
                balance = balances.get(uuid);
                cursor = boundary;
            }
            if (cursor < now)
                settleSessionSlice(uuid, connected, balance, session, cursor, now, config, afkMillis);
            session.lastAccountedAt = now;
        }
    }

    private boolean settleSessionSlice(UUID uuid, ServerPlayerEntity connected, PlayerBalance balance,
                                       Presence session, long start, long end, BalanceConfig config,
                                       long afkMillis) {
        if (end <= start) return false;
        long elapsed = end - start;
        boolean isAfk = config.afkEnabled() && start - session.lastActivityAt >= afkMillis;
        int factorPlayers = isAfk ? 1 : Math.max(1, activePlayersAt(start, config, afkMillis));
        boolean consume = !isAfk || config.afkConsumesOwnTime();
        long beforeRemaining = effectiveRemaining(balance, factorPlayers, config);
        BalanceEngine.Advance result = resetService.advanceConnected(balance, start, elapsed,
                factorPlayers, config, consume);
        balances.put(uuid, result.balance());
        if (!balance.dailyDate().equals(result.balance().dailyDate())) {
            warningsSent.remove(uuid);
        } else if (consume && connected != null) {
            warnIfCrossed(connected, beforeRemaining,
                    effectiveRemaining(result.balance(), factorPlayers, config), config);
        }
        if (result.stopReason() == BalanceEngine.StopReason.NONE) return false;
        deferredStopReasons.put(uuid, result.stopReason());
        logger.warn("El saldo de {} alcanzó un límite durante una liquidación previa; se aplicará la salida en este tick.", uuid);
        return true;
    }

    private int activePlayersAt(long epochMillis, BalanceConfig config, long afkMillis) {
        return countActivePresence(epochMillis, config, afkMillis, null);
    }

    private int countActivePresence(long epochMillis, BalanceConfig config, long afkMillis, UUID excluded) {
        int count = 0;
        for (Map.Entry<UUID, Presence> entry : presence.entrySet()) {
            if (entry.getKey().equals(excluded) || rejectedJoins.contains(entry.getKey())) continue;
            Presence activity = entry.getValue();
            boolean isAfk = config.afkEnabled() && epochMillis - activity.lastActivityAt >= afkMillis;
            if (!isAfk || config.afkCountsAsActiveForOthers()) count++;
        }
        return count;
    }

    public int activePlayers() { return activePlayers; }
    public String nextResetLabel(PlayerBalance balance, long epochMillis) {
        return resetService.nextResetLabel(ConfigManager.current(), epochMillis, balance.dailyDate());
    }
    public Text rejoinBlockReason(PlayerBalance balance, long epochMillis) {
        BalanceConfig config = ConfigManager.current();
        if (!config.recoveryEnabled())
            return Text.translatable("craftbalance.message.rejoin_recovery_disabled",
                    resetService.nextResetLabel(config, epochMillis, balance.dailyDate()), config.timezone().getId());
        int minimumMinutes = config.minimumRejoinMinutes();
        if (minimumMinutes == 0)
            return Text.translatable("craftbalance.message.rejoin_need_positive");
        return Text.translatable("craftbalance.message.rejoin_need_minimum", minimumMinutes,
                format(Math.max(0, (long) minimumMinutes * 60_000L - balance.recoveredBalanceMillis())));
    }
    public double consumptionFor(UUID player) {
        Presence current = presence.get(player);
        BalanceConfig config = ConfigManager.current();
        boolean afk = config.afkEnabled() && current != null
                && nowMillis() - current.lastActivityAt >= config.inactivityMinutes() * 60_000L;
        if (afk && !config.afkConsumesOwnTime()) return 0.0;
        PlayerBalance balance = balances.get(player);
        if (balance != null && balance.normalBalanceMillis() == 0 && balance.recoveredBalanceMillis() > 0)
            return config.recoveredConsumption();
        return config.consumptionFor(afk ? 1 : Math.max(1, activePlayers));
    }
    public long estimatedPlayableMillis(UUID uuid) {
        BalanceConfig config = ConfigManager.current();
        PlayerBalance balance = balances.get(uuid);
        if (balance == null || !config.enabled()) return 0;
        long realCap = config.maxRealMinutes() * 60_000L;
        long realRoom = Math.max(0, realCap - balance.realPlayedTodayMillis());
        Presence session = presence.get(uuid);
        boolean afk = config.afkEnabled() && session != null
                && nowMillis() - session.lastActivityAt >= config.inactivityMinutes() * 60_000L;
        if (afk && !config.afkConsumesOwnTime()) return realRoom;
        return Math.min(realRoom, effectiveRemaining(balance, afk ? 1 : Math.max(1, activePlayers), config));
    }
    public PlayerBalance balance(UUID uuid) { return balances.get(uuid); }
    public java.util.List<String> knownPlayerUuids() {
        return balances.keySet().stream().map(UUID::toString).sorted().toList();
    }
    public BalanceConfig config() { return ConfigManager.current(); }
    public CompletableFuture<java.util.List<AdminAdjustment>> adminAdjustments(UUID playerUuid, int limit) {
        if (persistence == null || !ready || persistenceFailed)
            return CompletableFuture.failedFuture(new IllegalStateException("La persistencia no está disponible."));
        return persistence.findAdjustments(playerUuid, limit);
    }

    public CompletableFuture<Void> replaceBalance(UUID admin, UUID target, String kind, long minutes) {
        if (persistence == null || !ready || persistenceFailed)
            throw new IllegalArgumentException("La persistencia no está disponible para guardar ajustes.");
        if (pendingAdjustmentAudits.containsKey(target))
            throw new IllegalArgumentException("Ya hay un ajuste de ese jugador pendiente de guardarse.");
        if (minutes < 0) throw new IllegalArgumentException("Los minutos no pueden ser negativos.");
        if (!kind.equals("normal") && !kind.equals("recuperado"))
            throw new IllegalArgumentException("Tipo válido: normal o recuperado.");
        long adjustmentAt = nowMillis();
        // Settle the old balance and any daily reset before applying the administrative change.
        tick(adjustmentAt, true);
        BalanceConfig configAtAdjustment = ConfigManager.current();
        long millis = Math.multiplyExact(minutes, 60_000L);
        int maxMinutes = kind.equals("normal") ? configAtAdjustment.dailyMinutes()
                : configAtAdjustment.maxRecoveryBankMinutes();
        if (minutes > maxMinutes)
            throw new IllegalArgumentException("El saldo " + kind + " no puede superar " + maxMinutes + " minutos.");
        PlayerBalance before = balances.get(target);
        boolean hadBalanceBefore = before != null;
        if (before == null) before = freshBalance(target, configAtAdjustment, adjustmentAt);
        PlayerBalance after;
        if (kind.equals("normal")) {
            long disconnectedAt = millis == 0 ? adjustmentAt : before.lastDisconnectEpochMillis();
            after = new PlayerBalance(before.uuid(), before.dailyDate(), millis, before.recoveredBalanceMillis(),
                    before.recoveryGeneratedTodayMillis(), before.realPlayedTodayMillis(),
                    disconnectedAt, millis == 0, before.recoveryFractionalNumerator(), 0.0);
        } else if (kind.equals("recuperado")) {
            after = new PlayerBalance(before.uuid(), before.dailyDate(), before.normalBalanceMillis(), millis,
                    before.recoveryGeneratedTodayMillis(), before.realPlayedTodayMillis(),
                    before.lastDisconnectEpochMillis(), before.normalExhaustedToday(),
                    before.recoveryFractionalNumerator(), 0.0);
        } else throw new IllegalArgumentException("Tipo válido: normal o recuperado.");
        Presence sessionAtAdjustment = presence.get(target);
        boolean wasOnline = sessionAtAdjustment != null;
        boolean wasAfk = wasOnline && configAtAdjustment.afkEnabled()
                && adjustmentAt - sessionAtAdjustment.lastActivityAt >= configAtAdjustment.inactivityMinutes() * 60_000L;
        long accountingStartAt = wasOnline ? sessionAtAdjustment.lastAccountedAt : adjustmentAt;
        PendingAdjustment pending = new PendingAdjustment(before, after, hadBalanceBefore, accountingStartAt,
                wasOnline, wasAfk, Math.max(1, activePlayers), configAtAdjustment, kind, adjustmentAt, 0);
        pendingAdjustmentAudits.put(target, pending);
        CompletableFuture<Void> audit;
        try {
            audit = persistence.recordAdjustment(admin, before, after, kind, adjustmentAt);
        } catch (RuntimeException exception) {
            pendingAdjustmentAudits.remove(target, pending);
            throw exception;
        }
        pendingAdjustmentFutures.put(target, audit);
        balances.put(target, after);
        warningsSent.remove(target);
        long generation = lifecycleGeneration;
        MinecraftServer callbackServer = server;
        CompletableFuture<Void> settled = new CompletableFuture<>();
        audit.whenComplete((ignored, failure) -> {
            if (generation != lifecycleGeneration) {
                settled.completeExceptionally(new IllegalStateException("El servidor se cerró antes de terminar el ajuste."));
                return;
            }
            try {
                callbackServer.execute(() -> {
                if (generation != lifecycleGeneration) {
                    settled.completeExceptionally(new IllegalStateException("El servidor se cerró antes de terminar el ajuste."));
                    return;
                }
                PendingAdjustment settledAdjustment = pendingAdjustmentAudits.getOrDefault(target, pending);
                long settledAt = nowMillis();
                BalanceEngine.StopReason stopReason = BalanceEngine.StopReason.NONE;
                if (failure == null) {
                    PlayerBalance current = balances.get(target);
                    if (current != null) {
                        long intervalEnd = settledAdjustment.disconnectedAt() > 0
                                ? settledAdjustment.disconnectedAt() : settledAt;
                        BalanceEngine.Advance accrued = accruePendingInterval(current, settledAdjustment, intervalEnd);
                        stopReason = accrued.stopReason();
                        PlayerBalance reconciled = reconcileBalance(accrued.balance(), ConfigManager.current(), settledAt);
                        if (settledAdjustment.disconnectedAt() > 0)
                            reconciled = reconciled.withDisconnect(settledAdjustment.disconnectedAt(), reconciled.normalExhaustedToday());
                        balances.put(target, reconciled);
                        Presence session = presence.get(target);
                        if (session != null) session.lastAccountedAt = intervalEnd;
                        if (kind.equals("normal")) advanceRecoveryCursor(target, adjustmentAt);
                        if (!reconciled.dailyDate().equals(accrued.balance().dailyDate()))
                            moveRecoveryCursorToDailyStart(target, reconciled.dailyDate(), ConfigManager.current());
                    }
                } else {
                    stopReason = rollbackFailedAdjustment(target, settledAdjustment, settledAt);
                    logger.error("No se pudo guardar el ajuste administrativo.", unwrap(failure));
                }
                pendingAdjustmentAudits.remove(target, settledAdjustment);
                pendingAdjustmentFutures.remove(target, audit);
                BalanceConfig currentConfig = ConfigManager.current();
                if (currentConfig.recoveryEnabled()) accrueOffline(settledAt, 0, currentConfig);
                else advanceRecoveryCursors(settledAt);
                persistSoon();
                Presence activeSession = presence.get(target);
                ServerPlayerEntity activePlayer = server.getPlayerManager().getPlayer(target);
                if (stopReason != BalanceEngine.StopReason.NONE && activeSession != null && activePlayer != null) {
                    if (stopReason == BalanceEngine.StopReason.DAILY_REAL_LIMIT) {
                        PlayerBalance balance = balances.get(target);
                        activePlayer.networkHandler.disconnect(Text.translatable("craftbalance.message.daily_limit",
                                resetService.nextResetLabel(currentConfig, settledAt, balance.dailyDate()),
                                currentConfig.timezone().getId()));
                    } else {
                        activePlayer.networkHandler.disconnect(balanceExhaustedMessage(currentConfig,
                                balances.get(target), settledAt));
                    }
                    onDisconnect(activePlayer, settledAt);
                }
                if (failure == null) settled.complete(null);
                else settled.completeExceptionally(unwrap(failure));
                });
            } catch (RuntimeException schedulingFailure) {
                settled.completeExceptionally(schedulingFailure);
            }
        });
        return settled;
    }

    public boolean reloadConfig() throws IOException {
        long now = nowMillis();
        if (ready && !persistenceFailed) tick(now, true);
        BalanceConfig before = ConfigManager.current();
        BalanceConfig after = ConfigManager.reload(configFile, before.databaseFile());
        boolean changed = false;
        for (Map.Entry<UUID, PlayerBalance> entry : new ArrayList<>(balances.entrySet())) {
            if (pendingAdjustmentAudits.containsKey(entry.getKey())) continue;
            PlayerBalance beforeBalance = entry.getValue();
            PlayerBalance reconciled = reconcileBalance(beforeBalance, after, now);
            if (!reconciled.dailyDate().equals(beforeBalance.dailyDate()))
                moveRecoveryCursorToDailyStart(entry.getKey(), reconciled.dailyDate(), after);
            if (!reconciled.equals(beforeBalance)) {
                balances.put(entry.getKey(), reconciled);
                changed = true;
            }
        }
        warningsSent.clear();
        if (ready && server != null) refreshActivePlayers(now, after);
        if (changed) persistSoon();
        return true;
    }

    private PlayerBalance reconcileBalance(PlayerBalance balance, BalanceConfig config, long now) {
        PlayerBalance dated = resetService.applyIfNeeded(balance, config, now);
        long normalCap = config.dailyMinutes() * 60_000L;
        long recoveredCap = config.maxRecoveryBankMinutes() * 60_000L;
        long normal = Math.min(dated.normalBalanceMillis(), normalCap);
        long recovered = Math.min(dated.recoveredBalanceMillis(), recoveredCap);
        long fractional = !config.enabled() || !config.recoveryEnabled()
                || recovered < dated.recoveredBalanceMillis() ? 0 : dated.recoveryFractionalNumerator();
        double consumptionFraction = normal == 0 && recovered == 0 ? 0.0 : dated.consumptionFractionalMillis();
        return new PlayerBalance(dated.uuid(), dated.dailyDate(), normal, recovered,
                dated.recoveryGeneratedTodayMillis(), dated.realPlayedTodayMillis(),
                dated.lastDisconnectEpochMillis(), dated.normalExhaustedToday() || normal == 0, fractional,
                consumptionFraction);
    }

    public void stop() {
        if (persistence == null) {
            lifecycleGeneration++;
            ready = false;
            server = null;
            return;
        }
        PlayerPersistence closingPersistence = persistence;
        long generation = lifecycleGeneration;
        try {
            if (ready) settlePendingAdjustmentsAtShutdown();
            if (ready && !persistenceFailed) {
                long now = nowMillis();
                BalanceConfig config = ConfigManager.current();
                if (config.enabled() && config.recoveryEnabled())
                    accrueOffline(now, Math.max(0, now - lastUpdateAt), config);
                settleSessionsAtShutdown(now);
                settleBalancesAtShutdown(now, config);
            }
            CompletableFuture<Void> finalSave = ready
                    ? closingPersistence.saveAll(persistenceSnapshot()) : CompletableFuture.completedFuture(null);
            closingPersistence.close();
            finalSave.join();
        } catch (Exception exception) {
            logger.error("No se pudieron guardar los saldos finales o cerrar limpiamente SQLite.", unwrap(exception));
        } finally {
            if (lifecycleGeneration == generation) lifecycleGeneration++;
            pendingAdjustmentFutures.clear();
            ready = false;
            persistence = null;
            server = null;
        }
    }

    /** Resolve administrative SQL transactions and account their post-adjustment interval before shutdown. */
    private void settlePendingAdjustmentsAtShutdown() {
        for (UUID target : new ArrayList<>(pendingAdjustmentAudits.keySet())) {
            PendingAdjustment pending = pendingAdjustmentAudits.get(target);
            CompletableFuture<Void> future = pendingAdjustmentFutures.get(target);
            if (pending == null || future == null) {
                logger.error("No se pudo resolver el ajuste pendiente de {} durante el apagado.", target);
                continue;
            }

            boolean committed;
            try {
                future.join();
                committed = true;
            } catch (CompletionException failure) {
                committed = false;
                logger.error("Falló el ajuste administrativo pendiente durante el apagado.", unwrap(failure));
            }

            long settledAt = nowMillis();
            if (committed) {
                PlayerBalance current = balances.get(target);
                if (current != null) {
                    long intervalEnd = pending.disconnectedAt() > 0 ? pending.disconnectedAt() : settledAt;
                    BalanceEngine.Advance accrued = accruePendingInterval(current, pending, intervalEnd);
                    PlayerBalance reconciled = reconcileBalance(accrued.balance(), ConfigManager.current(), settledAt);
                    if (pending.disconnectedAt() > 0)
                        reconciled = reconciled.withDisconnect(pending.disconnectedAt(), reconciled.normalExhaustedToday());
                    balances.put(target, reconciled);
                    Presence session = presence.get(target);
                    if (session != null) session.lastAccountedAt = intervalEnd;
                    if (pending.adjustmentType().equals("normal"))
                        advanceRecoveryCursor(target, pending.adjustmentAtEpochMillis());
                    if (!reconciled.dailyDate().equals(accrued.balance().dailyDate()))
                        moveRecoveryCursorToDailyStart(target, reconciled.dailyDate(), ConfigManager.current());
                }
            } else {
                rollbackFailedAdjustment(target, pending, settledAt);
            }
            pendingAdjustmentAudits.remove(target, pending);
            pendingAdjustmentFutures.remove(target, future);
        }
    }

    private void settleBalancesAtShutdown(long now, BalanceConfig config) {
        for (Map.Entry<UUID, PlayerBalance> entry : new ArrayList<>(balances.entrySet())) {
            UUID uuid = entry.getKey();
            if (pendingAdjustmentAudits.containsKey(uuid)) continue;
            PlayerBalance before = entry.getValue();
            PlayerBalance dated = resetService.applyIfNeeded(before, config, now);
            if (!dated.equals(before)) {
                balances.put(uuid, dated);
                if (!dated.dailyDate().equals(before.dailyDate()))
                    moveRecoveryCursorToDailyStart(uuid, dated.dailyDate(), config);
            }
        }
    }

    /** Accounts the final connected interval before the last durable snapshot is queued. */
    private void settleSessionsAtShutdown(long now) {
        BalanceConfig config = ConfigManager.current();
        if (config.enabled()) settleConnectedSessions(now, config, null);
        for (Map.Entry<UUID, Presence> entry : presence.entrySet()) {
            UUID uuid = entry.getKey();
            if (pendingAdjustmentAudits.containsKey(uuid)) continue;
            PlayerBalance balance = balances.get(uuid);
            if (balance == null) continue;
            balances.put(uuid, balance.withDisconnect(now, balance.normalExhaustedToday()));
            advanceRecoveryCursor(uuid, now);
        }
        presence.clear();
        deferredStopReasons.clear();
    }

    private void persistSoon() {
        if (persistence == null || !ready || persistenceFailed) return;
        long generation = lifecycleGeneration;
        MinecraftServer callbackServer = server;
        persistence.saveAll(persistenceSnapshot()).whenComplete((ignored, failure) -> {
            if (failure == null) return;
            if (generation != lifecycleGeneration || callbackServer != server) return;
            Throwable cause = unwrap(failure);
            try {
                callbackServer.execute(() -> {
                    if (generation == lifecycleGeneration && callbackServer == server) failPersistence(cause);
                });
            } catch (RuntimeException schedulingFailure) {
                cause.addSuppressed(schedulingFailure);
                logger.error("No se pudieron guardar los saldos de CraftBalance y el servidor ya no acepta tareas.", cause);
            }
        });
    }

    private void failPersistence(Throwable failure) {
        if (persistenceFailed) return;
        persistenceFailed = true;
        logger.error("CraftBalance detuvo el control de tiempo porque no pudo guardar SQLite.", failure);
        Text message = Text.translatable("craftbalance.message.persistence_failed");
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList()))
            player.networkHandler.disconnect(message);
    }

    private java.util.List<PlayerBalance> persistenceSnapshot() {
        return balances.values().stream()
                .filter(balance -> !pendingAdjustmentAudits.containsKey(balance.uuid()))
                .toList();
    }

    private BalanceEngine.StopReason rollbackFailedAdjustment(UUID target, PendingAdjustment pending, long settledAt) {
        if (!pending.hadBalanceBefore()) {
            balances.remove(target);
            recoveryCursors.remove(target);
            warningsSent.remove(target);
            return BalanceEngine.StopReason.NONE;
        }
        PlayerBalance restored = pending.before();
        long intervalEnd = pending.disconnectedAt() > 0 ? pending.disconnectedAt() : settledAt;
        BalanceEngine.Advance accrued = accruePendingInterval(restored, pending, intervalEnd);
        LocalDate dateBeforeReconcile = accrued.balance().dailyDate();
        restored = reconcileBalance(accrued.balance(), ConfigManager.current(), settledAt);
        if (pending.disconnectedAt() > 0)
            restored = restored.withDisconnect(pending.disconnectedAt(), restored.normalExhaustedToday());
        balances.put(target, restored);
        Presence session = presence.get(target);
        if (session != null) session.lastAccountedAt = intervalEnd;
        if (pending.disconnectedAt() > 0) advanceRecoveryCursor(target, pending.disconnectedAt());
        else if (session != null) advanceRecoveryCursor(target, settledAt);
        if (!restored.dailyDate().equals(dateBeforeReconcile))
            moveRecoveryCursorToDailyStart(target, restored.dailyDate(), ConfigManager.current());
        warningsSent.remove(target);
        return accrued.stopReason();
    }

    private BalanceEngine.Advance accruePendingInterval(PlayerBalance balance, PendingAdjustment pending, long intervalEnd) {
        if (!pending.wasOnline() || !pending.configAtRequest().enabled()
                || intervalEnd <= pending.accountingStartAt())
            return new BalanceEngine.Advance(balance, 0, BalanceEngine.StopReason.NONE, false);
        // Use one immutable ruleset and let DailyResetService split the interval at reset boundaries.
        int factorPlayers = pending.afkAtRequest() ? 1 : pending.activePlayersAtRequest();
        boolean consume = !pending.afkAtRequest() || pending.configAtRequest().afkConsumesOwnTime();
        return resetService.advanceConnected(balance, pending.accountingStartAt(),
                intervalEnd - pending.accountingStartAt(), factorPlayers, pending.configAtRequest(), consume);
    }

    private PlayerBalance freshBalance(UUID uuid, BalanceConfig config, long now) {
        LocalDate date = resetService.currentDailyDate(config, now);
        return new PlayerBalance(uuid, date, config.dailyMinutes() * 60_000L, 0, 0, 0, 0, false);
    }

    private static Presence newPresence(long now, ServerPlayerEntity player) {
        Presence presence = new Presence(now, now, player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch());
        presence.captureScreenState(player);
        return presence;
    }

    private static String format(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1_000L);
        long hours = seconds / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainder = seconds % 60;
        if (hours > 0) return hours + " h " + minutes + " min";
        if (minutes > 0) return minutes + " min " + remainder + " s";
        return remainder + " s";
    }

    private Text balanceExhaustedMessage(BalanceConfig config, PlayerBalance balance, long now) {
        if (!config.recoveryEnabled())
            return Text.translatable("craftbalance.message.exhausted_recovery_disabled",
                    resetService.nextResetLabel(config, now, balance.dailyDate()), config.timezone().getId());
        if (config.minimumRejoinMinutes() == 0)
            return Text.translatable("craftbalance.message.exhausted_recovery_any");
        return Text.translatable("craftbalance.message.exhausted_recovery_minimum",
                config.minimumRejoinMinutes());
    }

    private static float angularDistance(float first, float second) {
        float difference = (first - second) % 360.0f;
        if (difference > 180.0f) difference -= 360.0f;
        if (difference < -180.0f) difference += 360.0f;
        return Math.abs(difference);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static final class Presence {
        long lastActivityAt;
        long lastAccountedAt;
        double x, y, z;
        float yaw, pitch;
        int inventoryChangeCount;
        int selectedSlot;
        net.minecraft.item.ItemStack cursorStack = net.minecraft.item.ItemStack.EMPTY;
        Presence(long lastActivityAt, long lastAccountedAt, double x, double y, double z,
                 float yaw, float pitch) {
            this.lastActivityAt = lastActivityAt; this.lastAccountedAt = lastAccountedAt;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
        }

        void captureScreenState(ServerPlayerEntity player) {
            inventoryChangeCount = player.getInventory().getChangeCount();
            selectedSlot = player.getInventory().selectedSlot;
            cursorStack = player.currentScreenHandler.getCursorStack().copy();
        }

        boolean updateScreenState(ServerPlayerEntity player) {
            int newInventoryChangeCount = player.getInventory().getChangeCount();
            int newSelectedSlot = player.getInventory().selectedSlot;
            net.minecraft.item.ItemStack newCursorStack = player.currentScreenHandler.getCursorStack();
            boolean changed = newInventoryChangeCount != inventoryChangeCount || newSelectedSlot != selectedSlot
                    || !net.minecraft.item.ItemStack.areEqual(cursorStack, newCursorStack);
            inventoryChangeCount = newInventoryChangeCount;
            selectedSlot = newSelectedSlot;
            cursorStack = newCursorStack.copy();
            return changed;
        }
    }

    private record PendingAdjustment(PlayerBalance before, PlayerBalance after, boolean hadBalanceBefore,
                                     long accountingStartAt,
                                     boolean wasOnline, boolean afkAtRequest,
                                     int activePlayersAtRequest, BalanceConfig configAtRequest,
                                     String adjustmentType, long adjustmentAtEpochMillis, long disconnectedAt) {
        PendingAdjustment withDisconnectAt(long epochMillis) {
            return new PendingAdjustment(before, after, hadBalanceBefore, accountingStartAt, wasOnline,
                    afkAtRequest, activePlayersAtRequest, configAtRequest, adjustmentType,
                    adjustmentAtEpochMillis, epochMillis);
        }
    }
}
