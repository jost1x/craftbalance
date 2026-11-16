package dev.craftbalance.mixin;

import dev.craftbalance.CraftBalanceMod;
import net.minecraft.network.message.LastSeenMessageList;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "handleCommandExecution", at = @At("HEAD"))
    private void craftbalance$markCommandActivity(ChatCommandSignedC2SPacket packet,
                                                   LastSeenMessageList lastSeenMessages,
                                                   CallbackInfo callbackInfo) {
        CraftBalanceMod.markCommandActivity(player);
    }
}
