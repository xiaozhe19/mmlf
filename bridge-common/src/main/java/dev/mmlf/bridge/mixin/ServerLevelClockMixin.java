package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.WorldClock;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explicit time and weather changes, sent to the session's other runtimes ({@link WorldClock}). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelClockMixin {
    @Inject(method="setDayTime",at=@At("HEAD"))
    private void mmlf$time(long time,CallbackInfo ci) { WorldClock.dayTimeSet((ServerLevel)(Object)this,time); }
    @Inject(method="setWeatherParameters",at=@At("HEAD"))
    private void mmlf$weather(int clear,int weather,boolean raining,boolean thundering,CallbackInfo ci) { WorldClock.weatherSet((ServerLevel)(Object)this,clear,weather,raining,thundering); }
}
