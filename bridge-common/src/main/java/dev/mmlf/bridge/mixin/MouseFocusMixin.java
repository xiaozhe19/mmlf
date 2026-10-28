package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.GuiHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** A hidden background window never has focus; while it hosts a screen, pointer movement and drags must still reach it. */
@Mixin(MouseHandler.class)
public abstract class MouseFocusMixin {
 @Redirect(method="handleAccumulatedMovement",at=@At(value="INVOKE",target="Lnet/minecraft/client/Minecraft;isWindowActive()Z"))
 private boolean mmlf$active(Minecraft mc) { return mc.isWindowActive() || GuiHost.forceWindowActive(); }
}
