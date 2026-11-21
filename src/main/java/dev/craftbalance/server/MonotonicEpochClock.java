package dev.craftbalance.server;

/** Provides epoch-shaped timestamps that advance from a monotonic clock. */
final class MonotonicEpochClock {
    private final long epochOriginMillis = System.currentTimeMillis();
    private final long monotonicOriginNanos = System.nanoTime();

    long millis() {
        return epochOriginMillis + (System.nanoTime() - monotonicOriginNanos) / 1_000_000L;
    }
}
