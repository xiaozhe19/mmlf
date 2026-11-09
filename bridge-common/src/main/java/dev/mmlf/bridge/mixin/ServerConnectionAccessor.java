package dev.mmlf.bridge.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether the server waits for the client to confirm a teleport (use packets are dropped meanwhile). */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerConnectionAccessor {
    @Accessor("awaitingPositionFromClient") Vec3 mmlf$awaitingPosition();
}
