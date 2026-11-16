package dev.craftbalance.server;

import java.math.BigDecimal;

/** Small formatting helpers shared by player-facing balance messages. */
final class BalanceFormatting {
    private BalanceFormatting() {}

    static String percent(double factor) {
        return BigDecimal.valueOf(factor).multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros().toString() + "%";
    }
}
