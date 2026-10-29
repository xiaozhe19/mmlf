package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.GuiHost;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Hosted screens draw into their own target; the loaders' screen render events still wrap this call. */
@Mixin(Screen.class)
public abstract class ScreenMixin {
 @Inject(method="renderWithTooltip",at=@At("HEAD"))
 private void mmlf$begin(GuiGraphics graphics,int x,int y,float delta,CallbackInfo ci) { GuiHost.beginCapture((Screen)(Object)this,graphics); }
 @Inject(method="renderWithTooltip",at=@At("RETURN"))
 private void mmlf$end(GuiGraphics graphics,int x,int y,float delta,CallbackInfo ci) { GuiHost.endCapture((Screen)(Object)this,graphics); dev.mmlf.bridge.SessionStatus.render(graphics); }
 /** Blurring rebinds the main target and would read this runtime's world; the primary draws its own background. */
 @Inject(method="renderBlurredBackground",at=@At("HEAD"),cancellable=true)
 private void mmlf$blur(float delta,CallbackInfo ci) { if(GuiHost.capturing()) ci.cancel(); }
}
