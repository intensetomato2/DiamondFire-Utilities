package dev.intensetomato.dfu.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos") int treecuttersUtilities$getLeftPos();
    @Accessor("topPos") int treecuttersUtilities$getTopPos();
}
