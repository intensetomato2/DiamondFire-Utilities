package dev.intensetomato.dfu.mixin;

import dev.intensetomato.dfu.api.DfuEvents;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void dfu$captureActionBar(Component message, boolean animateColor, CallbackInfo ci) { if (message != null) DfuEvents.fireActionBar(message.getString()); }
}
