package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.SessionStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** The session panel over the world; with a screen open the screen hook draws it above that screen instead. */
@Mixin(Gui.class)
public abstract class GuiMixin {
 @Inject(method="render",at=@At("TAIL"))
 private void mmlf$status(GuiGraphics graphics,DeltaTracker delta,CallbackInfo ci) { if(Minecraft.getInstance().screen==null) SessionStatus.render(graphics); }
}
