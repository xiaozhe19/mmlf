package dev.mmlf.bridge.mixin;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Release builds: the "MMLF 测试平台" button on the title screen ({@link dev.mmlf.bridge.TestPlatform}). */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
 protected TitleScreenMixin(Component title) { super(title); }
 @Inject(method="init",at=@At("TAIL"))
 private void mmlf$testPlatform(CallbackInfo ci) { dev.mmlf.bridge.TestPlatform.addTo(this,this::addRenderableWidget); }
}
