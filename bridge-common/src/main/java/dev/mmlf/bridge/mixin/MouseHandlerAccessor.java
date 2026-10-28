package dev.mmlf.bridge.mixin;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Minecraft's own GLFW mouse callbacks, so replayed input takes the same path as real input. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
 @Invoker("onMove") void mmlf$onMove(long window,double x,double y);
 @Invoker("onPress") void mmlf$onPress(long window,int button,int action,int modifiers);
 @Invoker("onScroll") void mmlf$onScroll(long window,double x,double y);
}
