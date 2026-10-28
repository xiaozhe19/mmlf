package dev.mmlf.bridge.mixin;
import com.mojang.blaze3d.platform.Window;
import dev.mmlf.bridge.BridgeCore;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** A background runtime's window never appears: created invisible where the loader lets us, hidden right after otherwise. */
@Mixin(Window.class)
public abstract class WindowMixin {
 @Shadow @Final private long window;
 @Inject(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwDefaultWindowHints()V",shift=At.Shift.AFTER),require=0)
 private void mmlf$invisible(CallbackInfo ci) { if(BridgeCore.hiddenBackground()) GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE); }
 @Inject(method="<init>",at=@At("RETURN"))
 private void mmlf$hide(CallbackInfo ci) { if(BridgeCore.hiddenBackground()) GLFW.glfwHideWindow(window); }
}
