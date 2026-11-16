package dev.craftbalance.config;

import java.time.ZoneId;
import java.util.Map;

public record BalanceConfig(
        boolean enabled,
        int dailyMinutes,
        int maxRealMinutes,
        String resetHour,
        ZoneId timezone,
        boolean multiplayerEnabled,
        Map<Integer, Double> consumption,
        double fivePlusConsumption,
        boolean recoveryEnabled,
        boolean recoveryOnlyAfterNormalExhaustion,
        int secondsPerFriendPerMinute,
        int maxRecoverySecondsPerMinute,
        int initialWaitMinutes,
        int maxDailyRecoveryMinutes,
        int maxRecoveryBankMinutes,
        int minimumRejoinMinutes,
        double recoveredConsumption,
        boolean afkEnabled,
        int inactivityMinutes,
        boolean afkCountsAsActiveForOthers,
        boolean afkConsumesOwnTime,
        boolean actionBarEnabled,
        boolean loginSummaryEnabled,
        int[] warningMinutes,
        String databaseFile
) {
    public BalanceConfig {
        consumption = Map.copyOf(consumption);
        warningMinutes = warningMinutes.clone();
    }

    @Override
    public int[] warningMinutes() {
        return warningMinutes.clone();
    }

    public static BalanceConfig defaults() {
        return new BalanceConfig(true, 240, 360, "00:00", ZoneId.of("UTC"), true,
                Map.of(1, 1.00, 2, 0.90, 3, 0.85, 4, 0.80), 0.75,
                true, true, 6, 30, 10, 60, 60, 15, 1.0,
                true, 5, false, true, true, true, new int[]{30, 10, 5}, "craftbalance.db");
    }

    public double consumptionFor(int activePlayers) {
        if (!multiplayerEnabled) return 1.0;
        if (activePlayers >= 5) return fivePlusConsumption;
        return consumption.getOrDefault(Math.max(1, activePlayers), 1.0);
    }
}
