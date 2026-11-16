package dev.craftbalance.storage;

import java.time.LocalDate;
import java.util.UUID;

public record PlayerBalance(UUID uuid, LocalDate dailyDate, long normalBalanceMillis,
                            long recoveredBalanceMillis, long recoveryGeneratedTodayMillis,
                            long realPlayedTodayMillis, long lastDisconnectEpochMillis,
                            boolean normalExhaustedToday, long recoveryFractionalNumerator,
                            double consumptionFractionalMillis) {
    public PlayerBalance(UUID uuid, LocalDate dailyDate, long normalBalanceMillis,
                         long recoveredBalanceMillis, long recoveryGeneratedTodayMillis,
                         long realPlayedTodayMillis, long lastDisconnectEpochMillis,
                         boolean normalExhaustedToday, long recoveryFractionalNumerator) {
        this(uuid, dailyDate, normalBalanceMillis, recoveredBalanceMillis, recoveryGeneratedTodayMillis,
                realPlayedTodayMillis, lastDisconnectEpochMillis, normalExhaustedToday,
                recoveryFractionalNumerator, 0.0);
    }

    public PlayerBalance(UUID uuid, LocalDate dailyDate, long normalBalanceMillis,
                         long recoveredBalanceMillis, long recoveryGeneratedTodayMillis,
                         long realPlayedTodayMillis, long lastDisconnectEpochMillis,
                         boolean normalExhaustedToday) {
        this(uuid, dailyDate, normalBalanceMillis, recoveredBalanceMillis, recoveryGeneratedTodayMillis,
                realPlayedTodayMillis, lastDisconnectEpochMillis, normalExhaustedToday, 0, 0.0);
    }

    public PlayerBalance {
        if (uuid == null || dailyDate == null) throw new IllegalArgumentException("UUID y fecha son obligatorios.");
        if (normalBalanceMillis < 0 || recoveredBalanceMillis < 0 || recoveryGeneratedTodayMillis < 0
                || realPlayedTodayMillis < 0 || lastDisconnectEpochMillis < 0
                || recoveryFractionalNumerator < 0 || recoveryFractionalNumerator >= 60_000
                || !Double.isFinite(consumptionFractionalMillis) || consumptionFractionalMillis < 0
                || consumptionFractionalMillis >= 1)
            throw new IllegalArgumentException("Los contadores deben ser no negativos y la fracción menor que 60000.");
    }

    public PlayerBalance resetFor(LocalDate date, long dailyBalanceMillis) {
        // Preserve the offline session boundary across midnight. Recovery can be configured
        // to accrue while normal balance remains available; the exhaustion flag still resets.
        return new PlayerBalance(uuid, date, dailyBalanceMillis, 0, 0, 0,
                lastDisconnectEpochMillis, false, 0, 0.0);
    }

    public PlayerBalance withDisconnect(long epochMillis, boolean exhausted) {
        return new PlayerBalance(uuid, dailyDate, normalBalanceMillis, recoveredBalanceMillis,
                recoveryGeneratedTodayMillis, realPlayedTodayMillis, epochMillis, exhausted,
                recoveryFractionalNumerator, consumptionFractionalMillis);
    }
}
