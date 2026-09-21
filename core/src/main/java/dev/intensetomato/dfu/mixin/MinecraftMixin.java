package dev.intensetomato.dfu.mixin;

import dev.intensetomato.dfu.DiamondFireUtilities;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void dfu$openLobbyMenu(CallbackInfo ci) { if (DiamondFireUtilities.handleLobbyUse()) ci.cancel(); }
}
