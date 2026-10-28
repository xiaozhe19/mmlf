package dev.mmlf.bridge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Consumer;
import org.lwjgl.opengl.*;

/**
 * Background side of the composited view: copies the world colour and depth into pixel-pack buffers without waiting,
 * and publishes each one once its GPU fence has passed (usually the next frame). Three buffers rotate so the render
 * thread never blocks on the read-back; a buffer still unfinished after a second is abandoned.
 */
final class FrameExporter {
    private static final int RING=3;
    private static final long STUCK_NANOS=1_000_000_000L;
    private static final class Slot {
        int buffer; long capacity; long fence; boolean busy; long started;
        int width, height; Pose pose; long frameId;
    }
    private static final Slot[] ring=new Slot[RING];
    private static int next;
    private static long frameSequence;

    private FrameExporter() {}

    /** Called right after the world pass, before the hand. The pose must be the camera this frame was rendered with. */
    static void capture(RenderTarget target,Pose pose,FrameFile file,Consumer<Frame> announce,String runtime,double offsetNanos) {
        drain(file,announce,runtime,offsetNanos,false);
        int width=target.width,height=target.height;
        if(!FrameFile.fits(width,height,FrameFile.MAX_PIXELS)) return;
        Slot slot=ring[next]; if(slot==null) slot=ring[next]=new Slot();
        long now=System.nanoTime();
        if(slot.busy) {
            if(now-slot.started<STUCK_NANOS) { MmlfLog.sampled("render.capture","ring_full",1000,Map.of("slot",next)); return; }
            release(slot); MmlfLog.warn("render.capture","readback_abandoned",Map.of("frameId",slot.frameId));
        }
        long n=(long)width*height*4;
        try(PixelTransfer transfer=new PixelTransfer()) {
            if(slot.buffer==0) slot.buffer=GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,slot.buffer);
            if(slot.capacity!=2*n) { GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER,2*n,GL15.GL_STREAM_READ); slot.capacity=2*n; }
            int read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,target.frameBufferId);
            GL11.glReadPixels(0,0,width,height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,0L);
            GL11.glReadPixels(0,0,width,height,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,n);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
            slot.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        }
        slot.busy=true; slot.started=now; slot.width=width; slot.height=height; slot.pose=pose; slot.frameId=++frameSequence;
        next=(next+1)%RING;
    }

    /** Publishes every finished read-back, oldest first. With wait=false a buffer whose fence is pending is left for later. */
    static void drain(FrameFile file,Consumer<Frame> announce,String runtime,double offsetNanos,boolean wait) {
        for(int k=0;k<RING;k++) {
            Slot slot=ring[(next+k)%RING];
            if(slot==null || !slot.busy) continue;
            int status=GL32.glClientWaitSync(slot.fence,0,wait?5_000_000L:0L);
            if(status!=GL32.GL_ALREADY_SIGNALED && status!=GL32.GL_CONDITION_SATISFIED) continue;
            int n=slot.width*slot.height*4;
            try(PixelTransfer transfer=new PixelTransfer()) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,slot.buffer);
                ByteBuffer mapped=GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER,0,2L*n,GL30.GL_MAP_READ_BIT);
                if(mapped==null) { MmlfLog.sampled("render.capture","map_failed",1000,Map.of("frameId",slot.frameId)); release(slot); continue; }
                try {
                    int index=file.publish(slot.frameId,slot.width,slot.height,mapped.slice(0,n),mapped.slice(n,n));
                    announce.accept(new Frame(runtime,slot.frameId,slot.pose,index,slot.width,slot.height,slot.started,true,"opengl-window-depth",offsetNanos));
                    MmlfLog.sampled("render.capture","frame",1000,Map.of("frameId",slot.frameId,"slot",index,"width",slot.width,"height",slot.height,"readbackMs",(System.nanoTime()-slot.started)/1e6));
                } finally { GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER); }
            } catch(Exception e) { MmlfLog.sampled("render.capture","publish_failed",1000,Map.of("exception",e.toString())); }
            release(slot);
        }
    }

    private static void release(Slot slot) {
        if(slot.fence!=0) GL32.glDeleteSync(slot.fence);
        slot.fence=0; slot.busy=false; slot.pose=null;
    }
}
