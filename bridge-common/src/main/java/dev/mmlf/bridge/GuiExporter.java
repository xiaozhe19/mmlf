package dev.mmlf.bridge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mmlf.common.*;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.LongFunction;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/**
 * Asynchronous colour read-back of a hosted screen's own render target, like the world {@link FrameExporter} but without
 * depth or camera: three pixel-pack buffers rotate, and only the newest one whose GPU fence has passed is published.
 * The render thread never waits; a read-back unfinished after a second is abandoned.
 */
final class GuiExporter {
    private static final int RING=3;
    private static final long STUCK_NANOS=1_000_000_000L;
    private static final class Slot { int buffer; long capacity, fence, started, frameId; boolean busy; int width, height; byte[] metadata; }
    private final Slot[] ring=new Slot[RING];
    private final long[] readyIds=new long[RING];
    private final boolean[] readySlots=new boolean[RING];
    private int next;
    private long sequence, lastPublished;
    private ByteBuffer zeroDepth;
    private final SampleWindow readbackMs=new SampleWindow(1024);
    private long metricSince;

    /** Starts a read-back of {@code target}; {@code metadata} receives the frame ID this capture is given. */
    void capture(RenderTarget target,LongFunction<byte[]> metadata) {
        int width=target.width,height=target.height;
        if(!FrameFile.fits(width,height,FrameFile.MAX_PIXELS)) return;
        Slot slot=ring[next]; if(slot==null) slot=ring[next]=new Slot();
        long now=System.nanoTime();
        if(slot.busy) {
            if(now-slot.started<STUCK_NANOS) { MmlfLog.sampled("gui.capture","ring_full",1000,Map.of("slot",next)); return; }
            release(slot); MmlfLog.warn("gui.capture","readback_abandoned",Map.of("frameId",slot.frameId));
        }
        long n=(long)width*height*4;
        try(PixelTransfer transfer=new PixelTransfer()) {
            if(slot.buffer==0) slot.buffer=GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,slot.buffer);
            if(slot.capacity!=n) { GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER,n,GL15.GL_STREAM_READ); slot.capacity=n; }
            int read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,target.frameBufferId);
            GL11.glReadPixels(0,0,width,height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,0L);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
            slot.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        }
        slot.busy=true; slot.started=now; slot.width=width; slot.height=height; slot.frameId=++sequence; slot.metadata=metadata.apply(slot.frameId);
        next=(next+1)%RING;
    }

    /** Publishes the newest completed read-back, if any. Returns its frame ID or -1. */
    long drain(FrameFile file) {
        for(int k=0;k<RING;k++) {
            Slot candidate=ring[k]; readySlots[k]=false;
            if(candidate==null || !candidate.busy) continue;
            int status=GL32.glClientWaitSync(candidate.fence,0,0L);
            if(status!=GL32.GL_ALREADY_SIGNALED && status!=GL32.GL_CONDITION_SATISFIED) continue;
            readySlots[k]=true; readyIds[k]=candidate.frameId;
        }
        int selected=CompletedFrameSelector.newest(readyIds,readySlots,lastPublished);
        for(int k=0;k<RING;k++) if(readySlots[k] && k!=selected) release(ring[k]);
        if(selected<0) return -1;
        Slot slot=ring[selected]; int n=slot.width*slot.height*4;
        try(PixelTransfer transfer=new PixelTransfer()) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,slot.buffer);
            ByteBuffer mapped=GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER,0,n,GL30.GL_MAP_READ_BIT);
            if(mapped==null) { MmlfLog.sampled("gui.capture","map_failed",1000,Map.of("frameId",slot.frameId)); return -1; }
            try {
                if(zeroDepth==null || zeroDepth.capacity()<n) zeroDepth=BufferUtils.createByteBuffer(n);
                zeroDepth.clear().limit(n);
                int index=file.publish(slot.frameId,slot.width,slot.height,mapped.slice(0,n),zeroDepth,slot.metadata);
                if(index<0) { MmlfLog.sampled("gui.capture","slots_busy",1000,Map.of("frameId",slot.frameId)); return -1; }
                lastPublished=slot.frameId;
                long finished=System.nanoTime(); readbackMs.add((finished-slot.started)/1e6);
                if(metricSince==0) metricSince=finished;
                if(finished-metricSince>=10_000_000_000L) {
                    var r=readbackMs.drain();
                    MmlfLog.info("metrics","gui_capture",Map.of("samples",r.count(),"p50Ms",r.p50(),"p95Ms",r.p95(),"p99Ms",r.p99()));
                    metricSince=finished;
                }
                return slot.frameId;
            } finally { GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER); }
        } catch(Exception e) { MmlfLog.sampled("gui.capture","publish_failed",1000,Map.of("exception",e.toString())); return -1; }
        finally { release(slot); }
    }

    /** Drops pending read-backs, e.g. when the hosted screen closes. GPU buffers are kept for the next screen. */
    void reset() { for(Slot slot:ring) if(slot!=null && slot.busy) release(slot); }

    private static void release(Slot slot) {
        if(slot.fence!=0) GL32.glDeleteSync(slot.fence);
        slot.fence=0; slot.busy=false; slot.metadata=null;
    }
}
