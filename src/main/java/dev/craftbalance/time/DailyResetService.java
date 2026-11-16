package dev.craftbalance.time;

import dev.craftbalance.config.BalanceConfig;
import dev.craftbalance.storage.PlayerBalance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Determines the configured local day and initializes a player's daily counters once per date. */
public final class DailyResetService {
    private final Clock clock;

    public DailyResetService() { this(Clock.systemUTC()); }
    DailyResetService(Clock clock) { this.clock = clock; }

    public LocalDate currentDailyDate(BalanceConfig config) {
        return dailyDateAt(clock.instant(), config);
    }

    public LocalDate currentDailyDate(BalanceConfig config, long epochMillis) {
        return dailyDateAt(Instant.ofEpochMilli(epochMillis), config);
    }

    public String nextResetLabel(BalanceConfig config, long epochMillis) {
        return nextResetLabel(config, epochMillis, dailyDateAt(Instant.ofEpochMilli(epochMillis), config));
    }

    public String nextResetLabel(BalanceConfig config, long epochMillis, LocalDate lastAppliedDate) {
        LocalDate currentDate = dailyDateAt(Instant.ofEpochMilli(epochMillis), config);
        LocalDate anchor = lastAppliedDate.isAfter(currentDate) ? lastAppliedDate : currentDate;
        LocalDate resetDate = anchor.plusDays(1);
        return resetAt(resetDate, config)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
    }

    /** Returns the instant at which this configured local day began. */
    public long startOfDailyDate(LocalDate dailyDate, BalanceConfig config) {
        return resetAt(dailyDate, config).toInstant().toEpochMilli();
    }

    private static LocalDate dailyDateAt(Instant instant, BalanceConfig config) {
        LocalDate localDate = instant.atZone(config.timezone()).toLocalDate();
        return instant.isBefore(resetAt(localDate, config).toInstant()) ? localDate.minusDays(1) : localDate;
    }

    private static ZonedDateTime resetAt(LocalDate date, BalanceConfig config) {
        // ZonedDateTime resolves a skipped local time forward and chooses the earlier offset
        // when clocks repeat, giving each local date one stable reset instant.
        return ZonedDateTime.of(date, LocalTime.parse(config.resetHour()), config.timezone());
    }

    public PlayerBalance applyIfNeeded(PlayerBalance balance, BalanceConfig config) {
        return applyIfNeeded(balance, config, clock.millis());
    }

    public PlayerBalance applyIfNeeded(PlayerBalance balance, BalanceConfig config, long epochMillis) {
        LocalDate dailyDate = currentDailyDate(config, epochMillis);
        if (!dailyDate.isAfter(balance.dailyDate())) return balance;
        return balance.resetFor(dailyDate, config.dailyMinutes() * 60_000L);
    }

    /** Splits a connected interval at every configured daily reset boundary. */
    public BalanceEngine.Advance advanceConnected(PlayerBalance initial, long intervalStartEpochMillis,
                                                  long elapsedMillis, int activePlayers,
                                                  BalanceConfig config, boolean consumeBalance) {
        if (elapsedMillis < 0) throw new IllegalArgumentException("El intervalo no puede ser negativo.");
        long intervalEnd = Math.addExact(intervalStartEpochMillis, elapsedMillis);
        PlayerBalance balance = initial;
        long cursor = intervalStartEpochMillis;
        long counted = 0;
        BalanceEngine.StopReason stopReason = BalanceEngine.StopReason.NONE;

        while (true) {
            LocalDate dateAtCursor = dailyDateAt(Instant.ofEpochMilli(cursor), config);
            if (dateAtCursor.isAfter(balance.dailyDate()))
                balance = balance.resetFor(dateAtCursor, config.dailyMinutes() * 60_000L);
            if (cursor >= intervalEnd) break;

            Instant nextReset = resetAt(balance.dailyDate().plusDays(1), config).toInstant();
            long boundary = nextReset.toEpochMilli();
            long sliceEnd = Math.min(intervalEnd, boundary);
            long sliceMillis = Math.max(0, sliceEnd - cursor);
            if (sliceMillis > 0) {
                BalanceEngine.Advance slice = BalanceEngine.advanceConnected(balance, sliceMillis,
                        activePlayers, config, consumeBalance);
                balance = slice.balance();
                counted += slice.realMillisCounted();
                stopReason = slice.stopReason();
                boolean resetReachedAtRealLimit = stopReason == BalanceEngine.StopReason.DAILY_REAL_LIMIT
                        && sliceEnd == boundary && slice.realMillisCounted() == sliceMillis;
                if (stopReason != BalanceEngine.StopReason.NONE && !resetReachedAtRealLimit) break;
                if (resetReachedAtRealLimit) stopReason = BalanceEngine.StopReason.NONE;
            }
            cursor = sliceEnd;
        }
        return new BalanceEngine.Advance(balance, counted, stopReason);
    }
}
