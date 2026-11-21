package dev.craftbalance.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import dev.craftbalance.time.TimePolicy;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

public final class BalanceCommands {
    private final BalanceRuntime runtime;

    public BalanceCommands(BalanceRuntime runtime) { this.runtime = runtime; }

    public void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(root("cb"));
        dispatcher.register(root("craftbalance"));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> root(String name) {
        return CommandManager.literal(name)
                .executes(context -> showSelf(context.getSource()))
                .then(CommandManager.literal("tiempo").executes(context -> showSelf(context.getSource())))
                .then(CommandManager.literal("estado").executes(context -> showStatus(context.getSource())))
                .then(CommandManager.literal("ayuda").executes(context -> help(context.getSource())))
                .then(CommandManager.literal("reload").requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> reload(context.getSource())))
                .then(CommandManager.literal("info").requires(source -> source.hasPermissionLevel(2))
                        .then(playerArgument()
                                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "jugador")))))
                .then(CommandManager.literal("auditoria").requires(source -> source.hasPermissionLevel(2))
                        .then(playerArgument()
                                .executes(context -> audit(context.getSource(),
                                        StringArgumentType.getString(context, "jugador"), 5))
                                .then(CommandManager.argument("cantidad", IntegerArgumentType.integer(1, 20))
                                        .executes(context -> audit(context.getSource(),
                                                StringArgumentType.getString(context, "jugador"),
                                                IntegerArgumentType.getInteger(context, "cantidad"))))))
                .then(CommandManager.literal("set").requires(source -> source.hasPermissionLevel(2))
                        .then(playerArgument()
                                .then(CommandManager.literal("normal")
                                        .then(CommandManager.argument("minutos", IntegerArgumentType.integer(0))
                                                .executes(context -> set(context.getSource(),
                                                        StringArgumentType.getString(context, "jugador"), "normal",
                                                        IntegerArgumentType.getInteger(context, "minutos")))))
                                .then(CommandManager.literal("recuperado")
                                        .then(CommandManager.argument("minutos", IntegerArgumentType.integer(0))
                                                .executes(context -> set(context.getSource(),
                                                        StringArgumentType.getString(context, "jugador"), "recuperado",
                                                        IntegerArgumentType.getInteger(context, "minutos")))))));
    }

    private RequiredArgumentBuilder<ServerCommandSource, String> playerArgument() {
        return CommandManager.argument("jugador", StringArgumentType.word())
                .suggests((context, builder) -> {
                    ArrayList<String> candidates = new ArrayList<>(runtime.knownPlayerUuids());
                    candidates.addAll(context.getSource().getServer().getPlayerManager().getPlayerList().stream()
                            .map(player -> player.getGameProfile().getName()).toList());
                    return CommandSource.suggestMatching(candidates, builder);
                });
    }

    private int showSelf(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        runtime.markActivity(player);
        var balance = runtime.balance(player.getUuid());
        if (balance == null) {
            source.sendFeedback(() -> Text.translatable("craftbalance.command.balance_unavailable"), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable(runtime.config().enabled()
                        ? "craftbalance.command.balance"
                        : "craftbalance.command.balance_paused",
                format(balance.normalBalanceMillis()), format(balance.recoveredBalanceMillis()),
                format(balance.realPlayedTodayMillis()), runtime.config().maxRealMinutes()), false);
        if (runtime.config().enabled()) {
            source.sendFeedback(() -> Text.translatable("craftbalance.command.playable_estimate",
                    format(runtime.estimatedPlayableMillis(player.getUuid()))), false);
        }
        if (runtime.config().enabled()
                && !TimePolicy.canRejoin(balance.normalBalanceMillis(), balance.recoveredBalanceMillis(), runtime.config())) {
            Text reason = runtime.rejoinBlockReason(balance, runtime.nowMillis());
            source.sendFeedback(() -> reason, false);
        }
        return 1;
    }

    private int showStatus(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        runtime.markActivity(player);
        Text status = runtime.config().enabled()
                ? Text.translatable("craftbalance.command.status", runtime.activePlayers(),
                        BalanceFormatting.percent(runtime.consumptionFor(player.getUuid())))
                : Text.translatable("craftbalance.command.status_paused", runtime.activePlayers());
        source.sendFeedback(() -> status, false);
        return 1;
    }

    private int help(ServerCommandSource source) {
        markInvoker(source);
        source.sendFeedback(() -> Text.translatable("craftbalance.command.help_player"), false);
        source.sendFeedback(() -> Text.translatable("craftbalance.command.help_admin"), false);
        source.sendFeedback(() -> Text.translatable("craftbalance.command.help_set"), false);
        return 1;
    }

    private int reload(ServerCommandSource source) {
        markInvoker(source);
        try {
            runtime.reloadConfig();
            source.sendFeedback(() -> Text.translatable("craftbalance.command.reload_success"), true);
            return 1;
        } catch (Exception exception) {
            source.sendError(Text.translatable("craftbalance.command.reload_failure", exception.getMessage()));
            return 0;
        }
    }

    private int info(ServerCommandSource source, String name) {
        markInvoker(source);
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(name);
        java.util.UUID targetUuid = target == null ? parseUuid(name) : target.getUuid();
        if (targetUuid == null) {
            source.sendError(Text.translatable("craftbalance.command.player_required_info"));
            return 0;
        }
        var balance = runtime.balance(targetUuid);
        if (balance == null) { source.sendError(Text.translatable("craftbalance.command.no_player_data")); return 0; }
        String displayName = target == null ? targetUuid.toString() : target.getName().getString();
        var config = runtime.config();
        boolean underRealLimit = balance.realPlayedTodayMillis() < config.maxRealMinutes() * 60_000L;
        boolean canRejoin = !config.enabled() || (underRealLimit
                && TimePolicy.canRejoin(balance.normalBalanceMillis(), balance.recoveredBalanceMillis(), config));
        Text accessStatus;
        if (!config.enabled()) {
            accessStatus = Text.translatable("craftbalance.command.access_disabled");
        } else if (!underRealLimit) {
            accessStatus = Text.translatable("craftbalance.command.access_real_limit",
                    runtime.nextResetLabel(balance, runtime.nowMillis()));
        } else if (!canRejoin && !config.recoveryEnabled()) {
            accessStatus = Text.translatable("craftbalance.command.access_recovery_disabled",
                    runtime.nextResetLabel(balance, runtime.nowMillis()));
        } else if (!canRejoin && config.minimumRejoinMinutes() == 0) {
            accessStatus = Text.translatable("craftbalance.command.access_need_positive");
        } else if (!canRejoin) {
            long missing = Math.max(0, config.minimumRejoinMinutes() * 60_000L
                    - balance.recoveredBalanceMillis());
            accessStatus = Text.translatable("craftbalance.command.access_missing", format(missing));
        } else {
            accessStatus = Text.translatable("craftbalance.command.access_yes");
        }
        Text lastDisconnect = balance.lastDisconnectEpochMillis() <= 0
                ? Text.translatable("craftbalance.command.no_disconnect_mark")
                : Text.literal(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(config.timezone())
                        .format(Instant.ofEpochMilli(balance.lastDisconnectEpochMillis())));
        Text playableEstimate = config.enabled()
                ? Text.translatable("craftbalance.command.playable_estimate_short",
                        format(runtime.estimatedPlayableMillis(targetUuid)))
                : Text.translatable("craftbalance.status.paused");
        source.sendFeedback(() -> Text.translatable("craftbalance.command.info_balance", displayName,
                format(balance.normalBalanceMillis()), format(balance.recoveredBalanceMillis())), false);
        source.sendFeedback(() -> Text.translatable("craftbalance.command.info_today",
                format(balance.recoveryGeneratedTodayMillis()), format(balance.realPlayedTodayMillis()),
                playableEstimate, balance.dailyDate().toString()), false);
        source.sendFeedback(() -> Text.translatable("craftbalance.command.info_access", accessStatus,
                Text.translatable(balance.normalExhaustedToday()
                        ? "craftbalance.command.yes" : "craftbalance.command.no")), false);
        source.sendFeedback(() -> Text.translatable("craftbalance.command.info_disconnect", lastDisconnect), false);
        return 1;
    }

    private int audit(ServerCommandSource source, String name, int limit) {
        markInvoker(source);
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(name);
        java.util.UUID targetUuid = target == null ? parseUuid(name) : target.getUuid();
        if (targetUuid == null) {
            source.sendError(Text.translatable("craftbalance.command.player_required_audit"));
            return 0;
        }
        String displayName = target == null ? targetUuid.toString() : target.getName().getString();
        runtime.adminAdjustments(targetUuid, limit).whenComplete((adjustments, failure) ->
                source.getServer().execute(() -> {
                    if (failure != null) {
                        Throwable cause = failure;
                        while (cause.getCause() != null) cause = cause.getCause();
                        source.sendError(Text.translatable("craftbalance.command.audit_failure", cause.getMessage()));
                        return;
                    }
                    if (adjustments.isEmpty()) {
                        source.sendFeedback(() -> Text.translatable("craftbalance.command.audit_empty", displayName), false);
                        return;
                    }
                    var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                            .withZone(runtime.config().timezone());
                    source.sendFeedback(() -> Text.translatable("craftbalance.command.audit_header",
                            adjustments.size(), displayName), false);
                    for (var adjustment : adjustments) {
                        String admin = adjustment.adminUuid().getMostSignificantBits() == 0
                                && adjustment.adminUuid().getLeastSignificantBits() == 0
                                ? "-" : adjustment.adminUuid().toString();
                        source.sendFeedback(() -> Text.translatable("craftbalance.command.audit_entry",
                                formatter.format(Instant.ofEpochMilli(adjustment.changedAtEpochMillis())),
                                Text.translatable(adjustment.type().equals("normal")
                                        ? "craftbalance.command.type_normal" : "craftbalance.command.type_recovered"),
                                adjustment.adminUuid().getMostSignificantBits() == 0
                                        && adjustment.adminUuid().getLeastSignificantBits() == 0
                                        ? Text.translatable("craftbalance.command.console") : admin,
                                format(adjustment.normalBeforeMillis()), format(adjustment.normalAfterMillis()),
                                format(adjustment.recoveredBeforeMillis()), format(adjustment.recoveredAfterMillis())), false);
                    }
                }));
        return 1;
    }

    private int set(ServerCommandSource source, String name, String type, int minutes) {
        markInvoker(source);
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(name);
        java.util.UUID targetUuid = target == null ? parseUuid(name) : target.getUuid();
        if (targetUuid == null) {
            source.sendError(Text.translatable("craftbalance.command.player_required_set"));
            return 0;
        }
        try {
            ServerPlayerEntity admin = source.getEntity() instanceof ServerPlayerEntity player ? player : null;
            java.util.UUID adminUuid = admin == null ? new java.util.UUID(0L, 0L) : admin.getUuid();
            String displayName = target == null ? targetUuid.toString() : target.getName().getString();
            String balanceLabel = type.equals("normal") ? "normal" : "recuperado";
            runtime.replaceBalance(adminUuid, targetUuid, type, minutes).whenComplete((ignored, failure) ->
                    source.getServer().execute(() -> {
                        if (failure != null) {
                            Throwable cause = failure;
                            while (cause.getCause() != null) cause = cause.getCause();
                            source.sendError(Text.translatable("craftbalance.command.set_storage_failure",
                                    cause.getMessage()));
                            return;
                        }
                        Text typeText = Text.translatable(balanceLabel.equals("normal")
                                ? "craftbalance.command.type_normal" : "craftbalance.command.type_recovered");
                        source.sendFeedback(() -> Text.translatable("craftbalance.command.set_success",
                                typeText, displayName, minutes), true);
                        if (target != null) target.sendMessage(Text.translatable("craftbalance.command.set_notice",
                                typeText, minutes), false);
                    }));
            return 1;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            source.sendError(Text.translatable("craftbalance.command.set_failure", exception.getMessage()));
            return 0;
        }
    }

    private static java.util.UUID parseUuid(String text) {
        try { return java.util.UUID.fromString(text); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private void markInvoker(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) runtime.markActivity(player);
    }

    private static String format(long millis) {
        long seconds = Math.max(0, millis / 1_000L);
        long hours = seconds / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long rest = seconds % 60;
        return hours > 0 ? hours + " h " + minutes + " min" : minutes + " min " + rest + " s";
    }
}
