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
            source.sendFeedback(() -> Text.literal("CraftBalance todavía está cargando tus datos o no hay datos disponibles."), false);
            return 0;
        }
        String paused = runtime.config().enabled() ? "" : " | consumo pausado";
        source.sendFeedback(() -> Text.literal("Saldo normal: " + format(balance.normalBalanceMillis())
                + " | recuperado: " + format(balance.recoveredBalanceMillis())
                + " | jugado hoy: " + format(balance.realPlayedTodayMillis())
                + paused
                + " / " + runtime.config().maxRealMinutes() + " min"), false);
        if (runtime.config().enabled()) {
            source.sendFeedback(() -> Text.literal("Tiempo jugable estimado al ritmo actual: "
                    + format(runtime.estimatedPlayableMillis(player.getUuid()))
                    + " (aprox.; depende del grupo y del límite diario)"), false);
        }
        if (runtime.config().enabled()
                && !TimePolicy.canRejoin(balance.normalBalanceMillis(), balance.recoveredBalanceMillis(), runtime.config())) {
            String reason = runtime.rejoinBlockReason(balance, System.currentTimeMillis());
            source.sendFeedback(() -> Text.literal(reason), false);
        }
        return 1;
    }

    private int showStatus(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        runtime.markActivity(player);
        String consumption = runtime.config().enabled()
                ? "tu tasa actual de consumo: " + BalanceFormatting.percent(runtime.consumptionFor(player.getUuid()))
                : "consumo pausado por configuración";
        source.sendFeedback(() -> Text.literal("Jugadores considerados activos para otros: " + runtime.activePlayers()
                + " | " + consumption), false);
        return 1;
    }

    private int help(ServerCommandSource source) {
        markInvoker(source);
        source.sendFeedback(() -> Text.literal("/cb [tiempo|estado|ayuda] · Admin: /cb reload, /cb info <jugador|uuid>, /cb auditoria <jugador|uuid> [cantidad], /cb set <jugador|uuid> <normal|recuperado> <minutos>"), false);
        return 1;
    }

    private int reload(ServerCommandSource source) {
        markInvoker(source);
        try {
            runtime.reloadConfig();
            source.sendFeedback(() -> Text.literal("Configuración de CraftBalance recargada y validada."), true);
            return 1;
        } catch (Exception exception) {
            source.sendError(Text.literal("No se aplicó la configuración: " + exception.getMessage()));
            return 0;
        }
    }

    private int info(ServerCommandSource source, String name) {
        markInvoker(source);
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(name);
        java.util.UUID targetUuid = target == null ? parseUuid(name) : target.getUuid();
        if (targetUuid == null) {
            source.sendError(Text.literal("Indica un jugador conectado o su UUID para consultar un jugador desconectado."));
            return 0;
        }
        var balance = runtime.balance(targetUuid);
        if (balance == null) { source.sendError(Text.literal("No hay datos cargados para ese jugador.")); return 0; }
        String displayName = target == null ? targetUuid.toString() : target.getName().getString();
        var config = runtime.config();
        boolean underRealLimit = balance.realPlayedTodayMillis() < config.maxRealMinutes() * 60_000L;
        boolean canRejoin = !config.enabled() || (underRealLimit
                && TimePolicy.canRejoin(balance.normalBalanceMillis(), balance.recoveredBalanceMillis(), config));
        String accessStatus;
        if (!config.enabled()) {
            accessStatus = "puede volver sí (sistema desactivado)";
        } else if (!underRealLimit) {
            accessStatus = "puede volver no (límite real diario agotado hasta "
                    + runtime.nextResetLabel(balance, System.currentTimeMillis()) + ")";
        } else if (!canRejoin && !config.recoveryEnabled()) {
            accessStatus = "puede volver no (recuperación desactivada; reinicio "
                    + runtime.nextResetLabel(balance, System.currentTimeMillis()) + ")";
        } else if (!canRejoin && config.minimumRejoinMinutes() == 0) {
            accessStatus = "puede volver no (necesita saldo recuperado positivo)";
        } else if (!canRejoin) {
            long missing = Math.max(0, config.minimumRejoinMinutes() * 60_000L
                    - balance.recoveredBalanceMillis());
            accessStatus = "puede volver no (faltan " + format(missing) + " recuperados)";
        } else {
            accessStatus = "puede volver sí";
        }
        String lastDisconnect = balance.lastDisconnectEpochMillis() <= 0 ? "sin marca"
                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(config.timezone())
                        .format(Instant.ofEpochMilli(balance.lastDisconnectEpochMillis()));
        String playableEstimate = config.enabled()
                ? format(runtime.estimatedPlayableMillis(targetUuid)) + " aprox. al ritmo actual"
                : "consumo pausado";
        source.sendFeedback(() -> Text.literal(displayName + " · normal "
                + format(balance.normalBalanceMillis()) + " · recuperado " + format(balance.recoveredBalanceMillis())
                + " · generado hoy " + format(balance.recoveryGeneratedTodayMillis())
                + " · jugado hoy " + format(balance.realPlayedTodayMillis())
                + " · jugable estimado " + playableEstimate
                + " · día " + balance.dailyDate()
                + " · normal agotado " + (balance.normalExhaustedToday() ? "sí" : "no")
                + " · " + accessStatus
                + " · última desconexión " + lastDisconnect), false);
        return 1;
    }

    private int audit(ServerCommandSource source, String name, int limit) {
        markInvoker(source);
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(name);
        java.util.UUID targetUuid = target == null ? parseUuid(name) : target.getUuid();
        if (targetUuid == null) {
            source.sendError(Text.literal("Indica un jugador conectado o su UUID para consultar la auditoría."));
            return 0;
        }
        String displayName = target == null ? targetUuid.toString() : target.getName().getString();
        runtime.adminAdjustments(targetUuid, limit).whenComplete((adjustments, failure) ->
                source.getServer().execute(() -> {
                    if (failure != null) {
                        Throwable cause = failure;
                        while (cause.getCause() != null) cause = cause.getCause();
                        source.sendError(Text.literal("No se pudo consultar la auditoría: " + cause.getMessage()));
                        return;
                    }
                    if (adjustments.isEmpty()) {
                        source.sendFeedback(() -> Text.literal("No hay ajustes auditados para " + displayName + "."), false);
                        return;
                    }
                    var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                            .withZone(runtime.config().timezone());
                    source.sendFeedback(() -> Text.literal("Últimos " + adjustments.size()
                            + " ajustes auditados de " + displayName + ":"), false);
                    for (var adjustment : adjustments) {
                        String admin = adjustment.adminUuid().getMostSignificantBits() == 0
                                && adjustment.adminUuid().getLeastSignificantBits() == 0
                                ? "consola" : adjustment.adminUuid().toString();
                        source.sendFeedback(() -> Text.literal(formatter.format(
                                Instant.ofEpochMilli(adjustment.changedAtEpochMillis())) + " · "
                                + adjustment.type() + " · admin " + admin
                                + " · normal " + format(adjustment.normalBeforeMillis()) + " → "
                                + format(adjustment.normalAfterMillis())
                                + " · recuperado " + format(adjustment.recoveredBeforeMillis()) + " → "
                                + format(adjustment.recoveredAfterMillis())), false);
                    }
                }));
        return 1;
    }

    private int set(ServerCommandSource source, String name, String type, int minutes) {
        markInvoker(source);
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(name);
        java.util.UUID targetUuid = target == null ? parseUuid(name) : target.getUuid();
        if (targetUuid == null) {
            source.sendError(Text.literal("Indica un jugador conectado o su UUID para ajustar un jugador desconectado."));
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
                            source.sendError(Text.literal("No se aplicó el ajuste; no se pudo guardar ni auditar: "
                                    + cause.getMessage()));
                            return;
                        }
                        source.sendFeedback(() -> Text.literal("Saldo " + balanceLabel + " de " + displayName
                                + " fijado en " + minutes + " min y auditado."), true);
                        if (target != null) target.sendMessage(Text.literal("Un administrador ajustó tu saldo "
                                + balanceLabel + " a " + minutes + " min."), false);
                    }));
            return 1;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            source.sendError(Text.literal("No se aplicó el ajuste: " + exception.getMessage()));
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
