package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Background runtimes must not play their own music; all other sound categories remain untouched. */
@Mixin(SoundEngine.class)
public abstract class BackgroundMusicMixin {
    @Inject(method="play",at=@At("HEAD"),cancellable=true)
    private void mmlf$play(SoundInstance sound,CallbackInfo ci) {
        if(BridgeCore.suppressBackgroundMusic(sound.getSource())) ci.cancel();
    }
    @Inject(method="playDelayed",at=@At("HEAD"),cancellable=true)
    private void mmlf$delayed(SoundInstance sound,int delay,CallbackInfo ci) {
        if(BridgeCore.suppressBackgroundMusic(sound.getSource())) ci.cancel();
    }
    @Inject(method="queueTickingSound",at=@At("HEAD"),cancellable=true)
    private void mmlf$ticking(TickableSoundInstance sound,CallbackInfo ci) {
        if(BridgeCore.suppressBackgroundMusic(sound.getSource())) ci.cancel();
    }
}
