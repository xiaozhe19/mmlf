package dev.mmlf.bridge.mixin;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.mmlf.bridge.GuiHost;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * While a hosted screen draws into its transparent target, alpha accumulates coverage (ONE, ONE_MINUS_SRC_ALPHA) instead
 * of being overwritten, so the target holds premultiplied colour the primary can blend over its own world.
 */
@Mixin(GlStateManager.class)
public abstract class BlendCaptureMixin {
 @Inject(method="_blendFunc",at=@At("HEAD"),cancellable=true,remap=false)
 private static void mmlf$single(int src,int dst,CallbackInfo ci) {
  if(!GuiHost.capturing()) return;
  ci.cancel(); GlStateManager._blendFuncSeparate(src,dst,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
 }
 @ModifyVariable(method="_blendFuncSeparate",at=@At("HEAD"),argsOnly=true,ordinal=2,remap=false)
 private static int mmlf$srcAlpha(int value) { return GuiHost.capturing() ? GL11.GL_ONE : value; }
 @ModifyVariable(method="_blendFuncSeparate",at=@At("HEAD"),argsOnly=true,ordinal=3,remap=false)
 private static int mmlf$dstAlpha(int value) { return GuiHost.capturing() ? GL11.GL_ONE_MINUS_SRC_ALPHA : value; }
}
