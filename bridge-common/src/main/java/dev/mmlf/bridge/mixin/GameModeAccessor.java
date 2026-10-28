package dev.mmlf.bridge.mixin;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Breaking progress, for the bridge's mining diagnostics. */
@Mixin(MultiPlayerGameMode.class)
public interface GameModeAccessor {
 @Accessor("destroyProgress") float mmlf$destroyProgress();
}
