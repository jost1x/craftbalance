package dev.craftbalance.storage;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Serializes every SQLite operation on one daemon worker, keeping JDBC off the server thread. */
public final class PlayerPersistence implements AutoCloseable {
    private final ExecutorService io;
    private final Path file;
    private SqlitePlayerRepository repository;
    private boolean closeRequested;

    public PlayerPersistence(Path file) {
        this.file = file;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "CraftBalance-SQLite");
            thread.setDaemon(true);
            return thread;
        };
        this.io = Executors.newSingleThreadExecutor(factory);
    }

    public CompletableFuture<List<PlayerBalance>> loadAll() {
        return submit(() -> {
            repository = new SqlitePlayerRepository(file);
            return repository.findAll();
        });
    }

    public CompletableFuture<List<AdminAdjustment>> findAdjustments(java.util.UUID playerUuid, int limit) {
        return submit(() -> {
            requireOpen();
            return repository.findAdjustments(playerUuid, limit);
        });
    }

    public CompletableFuture<Void> saveAll(Collection<PlayerBalance> balances) {
        List<PlayerBalance> snapshot = List.copyOf(balances);
        return submit(() -> {
            requireOpen();
            repository.saveAll(snapshot);
            return null;
        });
    }

    public CompletableFuture<Void> recordAdjustment(java.util.UUID adminUuid, PlayerBalance before,
                                                    PlayerBalance after, String adjustmentType, long atEpochMillis) {
        return submit(() -> {
            requireOpen();
            repository.recordAdjustment(adminUuid, before, after, adjustmentType, atEpochMillis);
            return null;
        });
    }

    private void requireOpen() {
        if (repository == null) throw new IllegalStateException("La base SQLite aún no está lista.");
    }

    private synchronized <T> CompletableFuture<T> submit(SqlTask<T> task) {
        if (closeRequested)
            return CompletableFuture.failedFuture(new IllegalStateException("La persistencia de CraftBalance ya está cerrada."));
        return CompletableFuture.supplyAsync(() -> {
            try { return task.run(); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, io);
    }

    @Override public void close() throws Exception {
        CompletableFuture<Void> closing;
        synchronized (this) {
            if (closeRequested) return;
            closeRequested = true;
            closing = CompletableFuture.runAsync(() -> {
                try {
                    if (repository != null) repository.close();
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, io);
        }
        try {
            closing.join();
        } finally {
            io.shutdown();
        }
    }

    @FunctionalInterface private interface SqlTask<T> { T run() throws Exception; }
}
