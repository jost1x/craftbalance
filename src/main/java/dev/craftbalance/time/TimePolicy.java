package dev.craftbalance.time;

import dev.craftbalance.config.BalanceConfig;

/** Pure calculations for balance consumption and offline recovery. */
public final class TimePolicy {
    private static final long RECOVERY_DENOMINATOR = 60_000L;

    private TimePolicy() {}

    public record RecoveryCredit(long earnedMillis, long fractionalNumerator) {}

    public static long normalConsumptionMillis(long realElapsedMillis, int activePlayers, BalanceConfig config) {
        if (realElapsedMillis < 0) throw new IllegalArgumentException("El intervalo no puede ser negativo.");
        return Math.round(realElapsedMillis * config.consumptionFor(activePlayers));
    }

    /**
     * Calculates a single aggregate interval without carrying fractional credit.
     * Repeated runtime intervals must use {@link #recoveryCredit} and persist its remainder.
     */
    public static long recoveryEarnedMillis(long eligibleOfflineMillis, int activeFriends,
                                            long alreadyGeneratedTodayMillis, BalanceConfig config) {
        if (eligibleOfflineMillis < 0 || activeFriends < 0 || alreadyGeneratedTodayMillis < 0)
            throw new IllegalArgumentException("Los tiempos y cantidades no pueden ser negativos.");
        return recoveryCredit(eligibleOfflineMillis, activeFriends, alreadyGeneratedTodayMillis,
                0, 0, config).earnedMillis();
    }

    /** Carries sub-millisecond credit between intervals so short ticks do not round recovery to zero. */
    public static RecoveryCredit recoveryCredit(long eligibleOfflineMillis, int activeFriends,
                                                long alreadyGeneratedTodayMillis, long currentBankMillis,
                                                long fractionalNumerator, BalanceConfig config) {
        if (eligibleOfflineMillis < 0 || activeFriends < 0 || alreadyGeneratedTodayMillis < 0
                || currentBankMillis < 0 || fractionalNumerator < 0
                || fractionalNumerator >= RECOVERY_DENOMINATOR)
            throw new IllegalArgumentException("Los tiempos, saldos y fracción no pueden ser negativos o inválidos.");
        if (!config.recoveryEnabled()) return new RecoveryCredit(0, fractionalNumerator);

        long generatedRoom = Math.max(0, config.maxDailyRecoveryMinutes() * 60_000L - alreadyGeneratedTodayMillis);
        long bankRoom = Math.max(0, config.maxRecoveryBankMinutes() * 60_000L - currentBankMillis);
        long creditRoom = Math.min(generatedRoom, bankRoom);
        if (creditRoom == 0) return new RecoveryCredit(0, 0);

        long rateSeconds = Math.min((long) activeFriends * config.secondsPerFriendPerMinute(),
                config.maxRecoverySecondsPerMinute());
        if (rateSeconds == 0 || eligibleOfflineMillis == 0)
            return new RecoveryCredit(0, fractionalNumerator);

        // rateSeconds is seconds of balance per minute offline. Convert those seconds to
        // milliseconds before dividing by the 60,000 ms in one offline minute.
        long numeratorPerOfflineMillis = rateSeconds * 1_000L;
        long capNumerator = creditRoom * RECOVERY_DENOMINATOR - fractionalNumerator;
        long millisToCap = capNumerator / numeratorPerOfflineMillis
                + (capNumerator % numeratorPerOfflineMillis == 0 ? 0 : 1);
        if (eligibleOfflineMillis >= millisToCap) return new RecoveryCredit(creditRoom, 0);

        // The cap check above guarantees this multiplication plus the saved fraction fits
        // in a signed long, even for unusually large but valid configuration limits.
        long numerator = eligibleOfflineMillis * numeratorPerOfflineMillis + fractionalNumerator;
        return new RecoveryCredit(numerator / RECOVERY_DENOMINATOR,
                numerator % RECOVERY_DENOMINATOR);
    }

    public static boolean canRejoin(long normalBalanceMillis, long recoveredBalanceMillis, BalanceConfig config) {
        if (normalBalanceMillis > 0) return true;
        return recoveredBalanceMillis > 0
                && recoveredBalanceMillis >= config.minimumRejoinMinutes() * 60_000L;
    }

    public static long recoveredBankAfterEarn(long currentBankMillis, long earnedMillis, BalanceConfig config) {
        long cap = config.maxRecoveryBankMinutes() * 60_000L;
        long bank = Math.min(cap, Math.max(0, currentBankMillis));
        long addition = Math.min(Math.max(0, earnedMillis), cap - bank);
        return bank + addition;
    }
}
