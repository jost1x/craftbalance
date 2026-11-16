package dev.craftbalance.time;

import dev.craftbalance.config.BalanceConfig;
import dev.craftbalance.storage.PlayerBalance;

/** Applies one elapsed real-time interval to an immutable player balance. */
public final class BalanceEngine {
    private BalanceEngine() {}

    public enum StopReason { NONE, BALANCE_EMPTY, DAILY_REAL_LIMIT }
    public record Advance(PlayerBalance balance, long realMillisCounted, StopReason stopReason) {}

    public static Advance advanceConnected(PlayerBalance player, long elapsedMillis,
                                           int activePlayers, BalanceConfig config) {
        return advanceConnected(player, elapsedMillis, activePlayers, config, true);
    }

    public static Advance advanceConnected(PlayerBalance player, long elapsedMillis,
                                           int activePlayers, BalanceConfig config, boolean consumeBalance) {
        if (elapsedMillis < 0) throw new IllegalArgumentException("El intervalo no puede ser negativo.");
        if (elapsedMillis == 0) return new Advance(player, 0, StopReason.NONE);

        long realCap = config.maxRealMinutes() * 60_000L;
        long realRoom = Math.max(0, realCap - player.realPlayedTodayMillis());
        long allowedElapsed = Math.min(elapsedMillis, realRoom);
        if (!consumeBalance) {
            long playedToday = player.realPlayedTodayMillis() + allowedElapsed;
            PlayerBalance updated = new PlayerBalance(player.uuid(), player.dailyDate(), player.normalBalanceMillis(),
                    player.recoveredBalanceMillis(), player.recoveryGeneratedTodayMillis(), playedToday,
                    player.lastDisconnectEpochMillis(), player.normalExhaustedToday(),
                    player.recoveryFractionalNumerator(), player.consumptionFractionalMillis());
            return new Advance(updated, allowedElapsed, playedToday >= realCap
                    ? StopReason.DAILY_REAL_LIMIT : StopReason.NONE);
        }
        long normal = player.normalBalanceMillis();
        long recovered = player.recoveredBalanceMillis();
        double consumptionFraction = player.consumptionFractionalMillis();
        double factor = config.consumptionFor(activePlayers);
        long normalRealMillis = normal == 0 ? 0
                : Math.min(allowedElapsed, millisUntil(normal, consumptionFraction, factor));
        if (normal > 0) {
            double normalAccrued = normalRealMillis * factor + consumptionFraction;
            long normalSpent = Math.min(normal, wholeBalanceMillis(normalAccrued));
            normal -= normalSpent;
            consumptionFraction = normal == 0 ? 0 : fractionalPart(normalAccrued);
        }
        long remainingReal = allowedElapsed - normalRealMillis;
        double recoveredFactor = config.recoveredConsumption();
        long recoveredRealMillis = recovered == 0 ? 0
                : Math.min(remainingReal, millisUntil(recovered, consumptionFraction, recoveredFactor));
        if (recovered > 0) {
            double recoveredAccrued = recoveredRealMillis * recoveredFactor + consumptionFraction;
            long recoveredSpent = Math.min(recovered, wholeBalanceMillis(recoveredAccrued));
            recovered -= recoveredSpent;
            consumptionFraction = recovered == 0 ? 0 : fractionalPart(recoveredAccrued);
        } else if (normal == 0) {
            consumptionFraction = 0;
        }
        long acceptedReal = normalRealMillis + recoveredRealMillis;
        // Count the whole interval connected, including the final interval before a balance kick.
        // Keep an already-over-cap total when an administrator lowers the cap on reload;
        // clamping it would forgive time if the cap were raised again before daily reset.
        long playedToday = player.realPlayedTodayMillis() + allowedElapsed;
        boolean exhausted = player.normalExhaustedToday() || normal == 0;
        PlayerBalance updated = new PlayerBalance(player.uuid(), player.dailyDate(), normal, recovered,
                player.recoveryGeneratedTodayMillis(), playedToday, player.lastDisconnectEpochMillis(), exhausted,
                player.recoveryFractionalNumerator(), consumptionFraction);

        StopReason reason = StopReason.NONE;
        if (playedToday >= realCap) reason = StopReason.DAILY_REAL_LIMIT;
        else if (normal == 0 && recovered == 0) reason = StopReason.BALANCE_EMPTY;
        else if (acceptedReal < allowedElapsed) reason = StopReason.BALANCE_EMPTY;
        return new Advance(updated, allowedElapsed, reason);
    }

    public static PlayerBalance addRecovery(PlayerBalance player, long earnedMillis, BalanceConfig config) {
        return addRecovery(player, earnedMillis, player.recoveryFractionalNumerator(), config);
    }

    public static PlayerBalance addRecovery(PlayerBalance player, long earnedMillis,
                                            long fractionalNumerator, BalanceConfig config) {
        if (earnedMillis < 0) throw new IllegalArgumentException("La recuperación no puede ser negativa.");
        if (fractionalNumerator < 0 || fractionalNumerator >= 60_000)
            throw new IllegalArgumentException("La fracción de recuperación es inválida.");
        long generatedRoom = Math.max(0, config.maxDailyRecoveryMinutes() * 60_000L
                - player.recoveryGeneratedTodayMillis());
        long bankRoom = Math.max(0, config.maxRecoveryBankMinutes() * 60_000L
                - player.recoveredBalanceMillis());
        long credited = Math.min(earnedMillis, Math.min(generatedRoom, bankRoom));
        long remainder = credited < earnedMillis ? 0 : fractionalNumerator;
        return new PlayerBalance(player.uuid(), player.dailyDate(), player.normalBalanceMillis(),
                player.recoveredBalanceMillis() + credited,
                player.recoveryGeneratedTodayMillis() + credited, player.realPlayedTodayMillis(),
                player.lastDisconnectEpochMillis(), player.normalExhaustedToday(), remainder,
                player.consumptionFractionalMillis());
    }

    private static long millisUntil(long balanceMillis, double fractionalMillis, double factor) {
        double requiredMillis = (balanceMillis - fractionalMillis) / factor;
        if (!Double.isFinite(requiredMillis) || requiredMillis >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(0, (long) Math.ceil(requiredMillis));
    }

    private static long wholeBalanceMillis(double accrued) {
        if (!(accrued > 0)) return 0;
        if (!Double.isFinite(accrued) || accrued >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.floor(accrued);
    }

    private static double fractionalPart(double accrued) {
        if (!Double.isFinite(accrued) || accrued <= 0) return 0;
        double fraction = accrued - Math.floor(accrued);
        return fraction >= 1 ? Math.nextDown(1.0) : Math.max(0, fraction);
    }
}
