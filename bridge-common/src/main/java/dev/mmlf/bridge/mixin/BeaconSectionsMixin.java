package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.BeaconSections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A background's beacons show the beam their server computed over the one shared map ({@link BeaconSections}). */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconSectionsMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private static void mmlf$computed(Level level,BlockPos pos,BlockState state,BeaconBlockEntity beacon,CallbackInfo ci) {
        BeaconSections.computed(level,pos,beacon.getBeamSections());
    }
    /** The beam is followed up to the top of the world, not to this runtime's own surface ({@link BeaconSections#height}). */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private static int mmlf$height(Level level,net.minecraft.world.level.levelgen.Heightmap.Types type,int x,int z,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original) {
        return BeaconSections.height(level,original.call(level,type,x,z));
    }
    @Inject(method="getBeamSections",at=@At("HEAD"),cancellable=true)
    private void mmlf$shared(CallbackInfoReturnable<List<BeaconBlockEntity.BeaconBeamSection>> cir) {
        var beacon=(BeaconBlockEntity)(Object)this;
        List<BeaconBlockEntity.BeaconBeamSection> shared=BeaconSections.shown(beacon.getLevel(),beacon.getBlockPos());
        if(shared!=null) cir.setReturnValue(shared);
    }
}
