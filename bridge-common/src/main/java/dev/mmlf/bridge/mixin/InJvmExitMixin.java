package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.InJvmExit;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/**
 * A runtime that shares its JVM with its host must not end that JVM: the game's own exits (closing, crash) end only
 * this runtime's thread instead. Without the in-JVM backend the call is the game's own System.exit, unchanged.
 */
@Mixin(Minecraft.class)
public abstract class InJvmExitMixin {
 @Redirect(method={"destroy","crash"},at=@At(value="INVOKE",target="Ljava/lang/System;exit(I)V"),require=1)
 private static void mmlf$exit(int code) { InJvmExit.exit(code); }
}
