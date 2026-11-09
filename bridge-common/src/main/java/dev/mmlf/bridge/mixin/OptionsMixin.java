package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.render.NativeWorld;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A submitting runtime whose content the render host draws draws no clouds: the render host draws its own. */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method="getCloudsType",at=@At("RETURN"),cancellable=true)
    private void mmlf$hidden(CallbackInfoReturnable<CloudStatus> cir) {
        if(NativeWorld.hidden()) cir.setReturnValue(CloudStatus.OFF);
    }
}
