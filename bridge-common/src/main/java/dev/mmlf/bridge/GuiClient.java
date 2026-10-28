package dev.mmlf.bridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import java.util.*;

/**
 * Primary side of a hosted screen: follows the owner's lifecycle reports, shows its newest frame through
 * {@link RemoteGuiScreen} and sends the player's input to it in order. Only screens answering this primary's own
 * interactions, or replacing the one already shown by the same owner, are accepted.
 */
public final class GuiClient {
    private static final long EXPECT_NANOS=5_000_000_000L;
    private static final GuiLifecycle lifecycle=new GuiLifecycle();
    private static final GuiInputSequencer.Sender sender=new GuiInputSequencer.Sender();
    private static final LinkedHashMap<String,Long> requested=new LinkedHashMap<>();
    private static String openDimension;
    private static long openRequestedAt;
    private static boolean openMeasured;
    private static int sentScale, sentWidth, sentHeight;
    // frame source and texture
    private static String sourceRuntime; private static FrameFile source; private static long nextOpen;
    private static FrameFile.Latest cursor; private static FrameFile.Pixels pixels, spare;
    private static GuiFrame frame;
    private static int texture, textureWidth, textureHeight;
    // latency: input sequence -> primary send time
    private static final TreeMap<Long,Long> pendingInputs=new TreeMap<>();
    private static final SampleWindow inputToFrameMs=new SampleWindow(1024), openMs=new SampleWindow(256);
    private static long metricSince;
    private GuiClient() {}

    /** Render thread, before the ray query of a use: a screen answering this action may open. */
    public static void requested(String actionId) {
        long now=System.nanoTime();
        synchronized(requested) {
            requested.put(actionId,now);
            requested.values().removeIf(t->now-t>EXPECT_NANOS);
            while(requested.size()>64) requested.remove(requested.keySet().iterator().next());
        }
    }
    private static Long requestedAt(String actionId) {
        if(actionId==null) return null;
        synchronized(requested) { Long at=requested.get(actionId); return at!=null && System.nanoTime()-at<EXPECT_NANOS ? at : null; }
    }
    public static boolean open() { return lifecycle.isOpen(); }
    /** An owner's screen is active and at least one of its frames has arrived. */
    public static boolean showing() { return lifecycle.state()==GuiLifecycle.State.ACTIVE && frame!=null; }
    static boolean stalled() { return lifecycle.stalled(System.nanoTime()); }
    static String runtime() { return lifecycle.runtime(); }

    public static void receive(Wire message) {
        GuiState state=message.body(GuiState.class);
        if(state==null || !message.source().equals(state.runtime())) { MmlfLog.warn("gui","report_rejected",Map.of("source",message.source())); return; }
        Minecraft mc=Minecraft.getInstance(); mc.execute(()->apply(mc,state));
    }
    private static void apply(Minecraft mc,GuiState state) {
        long now=System.nanoTime(); Long asked=requestedAt(state.actionId());
        var outcome=lifecycle.apply(state,asked!=null,now);
        MmlfLog.info("gui","report",Map.of("instanceId",state.instanceId(),"runtime",state.runtime(),"state",state.state(),"outcome",outcome.name(),"screen",String.valueOf(state.screen()),"reason",String.valueOf(state.reason())));
        switch(outcome) {
            case OPENED, SWITCHED -> {
                sender.begin(state.instanceId()); sentScale=0; frame=null; cursor=null; pendingInputs.clear();
                if(outcome==GuiLifecycle.Outcome.OPENED) {
                    openRequestedAt=asked==null?now:asked; openMeasured=false;
                    openDimension=mc.level==null?null:mc.level.dimension().location().toString();
                    InventoryBridge.grant(state.runtime());
                }
                if(!(mc.screen instanceof RemoteGuiScreen)) mc.setScreen(new RemoteGuiScreen());
                sendWindow(mc);
            }
            case CLOSED, FAILED -> finish(mc,lifecycle.reason());
            default -> { }
        }
    }

    /** Ends the local side: the inventory is settled, the local screen closes, the texture stays for reuse. */
    private static void finish(Minecraft mc,String reason) {
        String runtime=lifecycle.runtime();
        InventoryBridge.ownerEnded(runtime,reason);
        MmlfLog.info("gui","closed",Map.of("runtime",String.valueOf(runtime),"state",lifecycle.state().name(),"reason",String.valueOf(reason)));
        lifecycle.reset(); sender.begin(null); frame=null; pendingInputs.clear();
        if(mc.screen instanceof RemoteGuiScreen screen) { screen.closedByOwner(); mc.setScreen(null); }
    }

    /** Asks the owner to close; the screen stays until it confirms or the close times out. */
    static void requestClose(String why) {
        if(!lifecycle.isOpen()) return;
        String instance=lifecycle.instanceId(), runtime=lifecycle.runtime();
        long now=System.nanoTime();
        if(lifecycle.state()==GuiLifecycle.State.ACTIVE || lifecycle.state()==GuiLifecycle.State.OPENING) {
            sendAll(sender.releaseAll(0,0,now));
            sendAll(sender.edge(GuiInputSequencer.CLOSE,0,0,-1,0,0,-1,-1,0,null,now));
        }
        lifecycle.requestClose(why,now);
        MmlfLog.info("gui","close_requested",Map.of("instanceId",String.valueOf(instance),"reason",why));
    }

    public static void runtimeLeft(String runtime) {
        if(runtime.equals(lifecycle.runtime()) && lifecycle.fail("runtime_left",System.nanoTime())) finish(Minecraft.getInstance(),"runtime_left");
        if(runtime.equals(sourceRuntime)) closeSource();
        InventoryBridge.ownerEnded(runtime,"runtime_left");
    }
    public static void disconnected() {
        if(lifecycle.fail("disconnected",System.nanoTime())) finish(Minecraft.getInstance(),"disconnected");
        closeSource();
    }

    public static void tick(Minecraft mc) {
        if(!lifecycle.isOpen()) return;
        long now=System.nanoTime();
        var expired=lifecycle.expire(now);
        if(expired!=null) { MmlfLog.warn("gui","expired",Map.of("state",expired.name(),"reason",String.valueOf(lifecycle.reason()))); finish(mc,lifecycle.reason()); return; }
        if(lifecycle.state()==GuiLifecycle.State.CLOSING) return;
        if(!(mc.screen instanceof RemoteGuiScreen)) { requestClose("primary_screen_replaced"); return; }
        if(mc.player==null || !mc.player.isAlive()) { requestClose("player_dead"); return; }
        if(mc.level==null || !mc.level.dimension().location().toString().equals(openDimension)) { requestClose("dimension_changed"); return; }
        if(!mc.isWindowActive() && sender.holding()) sendAll(sender.releaseAll(0,0,now));
        sendWindow(mc);
        if(metricSince==0) metricSince=now;
        if(now-metricSince>=10_000_000_000L) {
            var input=inputToFrameMs.drain(); var opened=openMs.drain();
            if(input.count()>0 || opened.count()>0) MmlfLog.info("metrics","gui",Map.of("inputToFrameSamples",input.count(),"inputToFrameP50Ms",input.p50(),"inputToFrameP95Ms",input.p95(),"inputToFrameP99Ms",input.p99(),
                "openSamples",opened.count(),"openP50Ms",opened.p50(),"openP95Ms",opened.p95()));
            metricSince=now;
        }
    }

    // ---- input from RemoteGuiScreen ----

    static void move(double nx,double ny) { sender.move(nx,ny,System.nanoTime()); }
    static void edge(String kind,double nx,double ny,int button,double sx,double sy,int key,int scancode,int modifiers,String text) {
        if(!lifecycle.accepts(sender.instance())) return;
        sendAll(sender.edge(kind,nx,ny,button,sx,sy,key,scancode,modifiers,text,System.nanoTime()));
    }
    static boolean holdingKey(int key) { return sender.heldKey(key); }
    /** Pending pointer movement goes out once per rendered frame. */
    static void flushMovement() { if(lifecycle.accepts(sender.instance())) sendAll(sender.flush()); }
    private static void sendWindow(Minecraft mc) {
        var window=mc.getWindow(); int scale=(int)window.getGuiScale();
        if(scale==sentScale && window.getWidth()==sentWidth && window.getHeight()==sentHeight) return;
        if(!lifecycle.accepts(sender.instance()) && lifecycle.state()!=GuiLifecycle.State.OPENING) return;
        sentScale=scale; sentWidth=window.getWidth(); sentHeight=window.getHeight();
        sender.window(scale,sentWidth,sentHeight);
        sendAll(sender.edge(GuiInputSequencer.RESIZE,0,0,-1,0,0,-1,-1,0,null,System.nanoTime()));
    }
    private static void sendAll(List<GuiInput> events) {
        String runtime=lifecycle.runtime(); if(runtime==null) return;
        for(GuiInput event:events) {
            if(!GuiInputSequencer.MOVE.equals(event.kind()) && !GuiInputSequencer.RESIZE.equals(event.kind())) {
                pendingInputs.put(event.sequence(),event.sentNanos());
                while(pendingInputs.size()>256) pendingInputs.pollFirstEntry();
            }
            BridgeCore.send("gui_input",runtime,event);
        }
    }

    // ---- frames ----

    /** Polls the owner's GUI file and draws the newest frame of the current instance over the whole screen. */
    static void draw(GuiGraphics graphics,int width,int height) {
        String runtime=lifecycle.runtime(); if(runtime==null) return;
        poll(runtime);
        if(frame==null || texture==0) return;
        graphics.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0,texture);
        RenderSystem.enableBlend();
        // The owner's target holds premultiplied colour.
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE,GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,GlStateManager.SourceFactor.ONE,GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        var matrix=graphics.pose().last().pose();
        BufferBuilder buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX);
        // Rows arrive bottom first.
        buffer.addVertex(matrix,0,0,0).setUv(0,1);
        buffer.addVertex(matrix,0,height,0).setUv(0,0);
        buffer.addVertex(matrix,width,height,0).setUv(1,0);
        buffer.addVertex(matrix,width,0,0).setUv(1,1);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.defaultBlendFunc(); RenderSystem.disableBlend();
    }
    private static void poll(String runtime) {
        if(!runtime.equals(sourceRuntime)) closeSource();
        if(source==null) {
            long now=System.nanoTime(); if(now-nextOpen<0) return;
            nextOpen=now+250_000_000L;
            try { source=FrameFile.reader(BridgeCore.session().framePath(runtime+"-gui")); sourceRuntime=runtime; cursor=null; }
            catch(java.io.IOException | IllegalArgumentException e) { MmlfLog.sampled("gui","frame_file_unavailable",5000,Map.of("runtime",runtime,"reason",e.toString())); return; }
        }
        try(PixelTransfer transfer=new PixelTransfer()) {
            FrameFile.Latest latest=source.readLatest(cursor,spare);
            if(latest==null) return;
            cursor=latest;
            GuiFrame next=Wire.JSON.fromJson(new String(latest.metadata(),java.nio.charset.StandardCharsets.UTF_8),GuiFrame.class);
            if(next==null || !runtime.equals(next.runtime()) || !BridgeCore.session().identity().equals(next.sessionId()) || next.frameId()!=latest.frameId()
                || next.width()!=latest.width() || next.height()!=latest.height() || !Objects.equals(next.instanceId(),lifecycle.instanceId())) {
                MmlfLog.sampled("gui","frame_rejected",1000,Map.of("runtime",runtime,"frameId",latest.frameId()));
                return;
            }
            spare=pixels; pixels=latest.pixels(); frame=next;
            long now=System.nanoTime();
            lifecycle.frame(next.instanceId(),now);
            uploadTexture(next.width(),next.height(),pixels.color());
            if(!openMeasured) { openMeasured=true; openMs.add((now-openRequestedAt)/1e6); MmlfLog.info("gui","first_frame",Map.of("instanceId",next.instanceId(),"openMs",(now-openRequestedAt)/1e6,"width",next.width(),"height",next.height())); }
            var answered=pendingInputs.headMap(next.inputSequence(),true);
            for(long sent:answered.values()) inputToFrameMs.add((now-sent)/1e6);
            answered.clear();
        } catch(Exception e) { MmlfLog.sampled("gui","frame_read_failed",1000,Map.of("exception",e.toString())); }
    }
    private static void uploadTexture(int width,int height,java.nio.ByteBuffer color) {
        if(texture==0) texture=TextureUtil.generateTextureId();
        GlStateManager._bindTexture(texture);
        if(width!=textureWidth || height!=textureHeight) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
            textureWidth=width; textureHeight=height;
        }
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,width,height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,color);
    }
    private static void closeSource() {
        if(source!=null) try { source.close(); } catch(java.io.IOException ignored) {}
        source=null; sourceRuntime=null; cursor=null; frame=null;
    }
}
