package dev.mmlf.bridge.mixin;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mmlf.bridge.GuiHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/**
 * Screens poll modifier keys (Shift-click, Ctrl-drop) from GLFW, which knows nothing of replayed input. While a screen
 * is hosted, a key the primary holds counts as down here as well.
 */
@Mixin(InputConstants.class)
public abstract class HostedKeyStateMixin {
 @Inject(method="isKeyDown",at=@At("HEAD"),cancellable=true,remap=false)
 private static void mmlf$held(long window,int key,CallbackInfoReturnable<Boolean> ci) { if(GuiHost.keyHeld(key)) ci.setReturnValue(true); }
}
