package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.CoGeneration;
import dev.mmlf.bridge.collab.ContainerBridge;
import dev.mmlf.bridge.collab.SaveSync;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One save for both runtimes ({@link SaveSync}): the primary's saves are barriers; a background saves only with them. */
@Mixin(MinecraftServer.class)
public abstract class ServerSaveMixin {
    @Inject(method="saveEverything",at=@At("HEAD"),cancellable=true)
    private void mmlf$beforeSave(boolean suppressLog,boolean flush,boolean force,CallbackInfoReturnable<Boolean> cir) {
        MinecraftServer server=(MinecraftServer)(Object)this;
        if(SaveSync.backgroundSkips(server)) { cir.setReturnValue(false); return; }
        SaveSync.primaryBefore(server);
    }
    @Inject(method="saveEverything",at=@At("RETURN"))
    private void mmlf$afterSave(boolean suppressLog,boolean flush,boolean force,CallbackInfoReturnable<Boolean> cir) {
        MinecraftServer server=(MinecraftServer)(Object)this;
        if(!SaveSync.backgroundSkips(server) && dev.mmlf.bridge.transport.SessionLink.isPrimary())
            SaveSync.primaryAfter(server,flush,CoGeneration.primarySaved(server) & ContainerBridge.save(server));
    }
    @Inject(method="saveAllChunks",at=@At("HEAD"),cancellable=true)
    private void mmlf$chunks(boolean suppressLog,boolean flush,boolean force,CallbackInfoReturnable<Boolean> cir) {
        if(SaveSync.backgroundSkips((MinecraftServer)(Object)this)) cir.setReturnValue(false);
    }
    @Inject(method="stopServer",at=@At("HEAD"))
    private void mmlf$beforeStop(CallbackInfo ci) {
        CoGeneration.serverStopping((MinecraftServer)(Object)this);
        SaveSync.primaryBefore((MinecraftServer)(Object)this);
    }
    @Inject(method="stopServer",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z",shift=At.Shift.AFTER))
    private void mmlf$afterStop(CallbackInfo ci) {
        MinecraftServer server=(MinecraftServer)(Object)this;
        boolean transfersSaved=ContainerBridge.save(server);
        SaveSync.primaryAfter(server,true,CoGeneration.primarySaved(server) & transfersSaved);
    }
}
