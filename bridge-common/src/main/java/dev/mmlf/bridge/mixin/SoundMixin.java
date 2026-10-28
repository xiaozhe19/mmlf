package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Holder;
import net.minecraft.sounds.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class SoundMixin {
 @Inject(method="playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",at=@At("HEAD"))
 private void mmlf$sound(Player player,double x,double y,double z,Holder<SoundEvent> sound,SoundSource source,float volume,float pitch,long seed,CallbackInfo ci) {
  BridgeCore.captureSound(x,y,z,sound,source,volume,pitch);
 }
}
