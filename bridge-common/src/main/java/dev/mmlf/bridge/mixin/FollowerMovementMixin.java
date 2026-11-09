package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.BridgeCore;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A background runtime's player stands where the primary's player stands; that position is authoritative. Its own
 * world has blocks the primary's player walks past (this runtime's objects, mirrored neighbours), so vanilla's
 * movement check would keep teleporting it back, and while a teleport awaits confirmation the server silently drops
 * every use packet: placements the client already predicted then roll back.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class FollowerMovementMixin {
    @Inject(method="isPlayerCollidingWithAnythingNew",at=@At("HEAD"),cancellable=true)
    private void mmlf$follower(LevelReader level,AABB box,double x,double y,double z,CallbackInfoReturnable<Boolean> cir) {
        if(BridgeCore.followsPrimary()) cir.setReturnValue(false);
    }
}
