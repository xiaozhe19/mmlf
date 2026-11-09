package dev.mmlf.bridge.render;

import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.RenderInstanceCodec;
import dev.mmlf.common.RenderInstanceStore;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Generic dynamic instance submission and full-snapshot transport, independent of any particular renderer. */
public final class RenderInstanceChannel {
    private static final RenderInstanceStore local=new RenderInstanceStore();
    private static volatile RenderInstanceStore.Snapshot remote;
    private static long lastWrite,exported=-1,lastRead,seenModified,remoteGeneration=-1,remoteVersion=-1;
    private RenderInstanceChannel() {}

    static void reset(long expectedGeneration) {
        // Adopt the geometry generation so mesh and instance tokens stay identical, without failing the client tick.
        try { local.resetScene(expectedGeneration); }
        catch(IllegalArgumentException e) {
            local.resetScene();
            MmlfLog.warn("render.submission","instance_generation_mismatch",Map.of("expected",expectedGeneration,"actual",local.generation()));
        }
        remote=null; exported=-1; seenModified=0; remoteGeneration=remoteVersion=-1;
    }
    /** A producer captures this token when beginning work; updates from an old scene are rejected. */
    public static long generation() { return local.generation(); }
    public static boolean submit(long generation,RenderInstanceStore.Instance instance) { return local.submit(generation,instance); }
    public static boolean remove(long generation,String id,long revision) { return local.remove(generation,id,revision); }
    public static RenderInstanceStore.Snapshot localSnapshot() { return local.snapshot(); }
    public static RenderInstanceStore.Snapshot remoteSnapshot() { return remote; }

    static void tick(Minecraft mc,String remoteRuntime) {
        long now=System.currentTimeMillis();
        if(!GeometryLayer.isRenderHost()) {
            var snapshot=local.snapshot();
            if(snapshot.version()==exported || now-lastWrite<250) return;
            lastWrite=now;
            try {
                byte[] bytes=RenderInstanceCodec.encode(new RenderInstanceCodec.Batch(BridgeCore.runtimeId(),mc.level.dimension().location().toString(),snapshot));
                Path target=BridgeCore.session().root().resolve(BridgeCore.runtimeId()+".instances"),tmp=target.resolveSibling(target.getFileName()+".tmp");
                Files.write(tmp,bytes); Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                exported=snapshot.version();
                MmlfLog.info("render.submission","instance_export",Map.of("instances",snapshot.instances().size(),"version",snapshot.version()));
            } catch(IOException e) { MmlfLog.sampled("render.submission","instance_export_failed",5000,Map.of("reason",e.toString())); }
            return;
        }
        if(now-lastRead<100) return; lastRead=now;
        Path path=BridgeCore.session().root().resolve(remoteRuntime+".instances");
        try {
            if(!Files.isRegularFile(path)) return;
            if(Files.size(path)>RenderInstanceCodec.MAX_BYTES) throw new IOException("instance snapshot too large");
            long modified=Files.getLastModifiedTime(path).toMillis(); if(modified==seenModified) return;
            var batch=RenderInstanceCodec.decode(Files.readAllBytes(path));
            if(!remoteRuntime.equals(batch.runtime()) || !mc.level.dimension().location().toString().equals(batch.dimension())) throw new IOException("instance scene mismatch");
            var snapshot=batch.snapshot();
            if(snapshot.generation()>remoteGeneration || snapshot.generation()==remoteGeneration && snapshot.version()>remoteVersion) {
                remote=snapshot; remoteGeneration=snapshot.generation(); remoteVersion=snapshot.version();
                MmlfLog.info("render.submission","instance_received",Map.of("runtime",remoteRuntime,"instances",snapshot.instances().size(),"version",snapshot.version()));
            }
            seenModified=modified;
        } catch(IOException e) { MmlfLog.sampled("render.submission","instance_poll_failed",5000,Map.of("reason",e.toString())); }
    }
}
