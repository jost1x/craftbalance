package dev.craftbalance;

import dev.craftbalance.config.ConfigManager;
import dev.craftbalance.server.BalanceCommands;
import dev.craftbalance.server.BalanceRuntime;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CraftBalanceMod implements ModInitializer {
    public static final String MOD_ID = "craftbalance";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static BalanceRuntime runtime;

    @Override
    public void onInitialize() {
        try {
            var configDirectory = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
            ConfigManager.loadOrCreate(configDirectory);
            runtime = new BalanceRuntime(configDirectory, LOGGER);
            registerEvents();
            LOGGER.info("CraftBalance {} inicializado para Minecraft 1.21.1.", version());
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo iniciar CraftBalance: configuración inválida o ilegible.", exception);
        }
    }

    private static void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(runtime::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> runtime.stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> runtime.tick(runtime.nowMillis()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                runtime.onJoin(handler.player, runtime.nowMillis()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                runtime.onDisconnect(handler.player, runtime.nowMillis()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                new BalanceCommands(runtime).register(dispatcher));

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) runtime.markActivity(serverPlayer);
            return TypedActionResult.pass(player.getStackInHand(hand));
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer
                    && !serverPlayer.isSpectator()) runtime.markActivity(serverPlayer);
            return ActionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) runtime.markActivity(serverPlayer);
            return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) runtime.markActivity(serverPlayer);
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) runtime.markActivity(serverPlayer);
            return ActionResult.PASS;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) runtime.markActivity(serverPlayer);
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> runtime.markActivity(sender));
    }

    public static void markCommandActivity(ServerPlayerEntity player) {
        if (runtime != null) runtime.markActivity(player);
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("desconocida");
    }
}
