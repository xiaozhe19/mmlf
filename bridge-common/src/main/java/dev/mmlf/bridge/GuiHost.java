package dev.mmlf.bridge;

import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.lwjgl.glfw.GLFW;
import java.util.Map;
import java.util.UUID;

/**
 * Owner side of a hosted screen. A native screen this runtime opens in answer to the primary's interaction keeps
 * running here: it is drawn into its own transparent render target, read back without waiting and published in
 * {@code <runtime>-gui.frames}, and the primary's input is replayed through Minecraft's own mouse and keyboard handlers.
 * Nothing here knows which mod the screen belongs to.
 */
public final class GuiHost {
    /** A screen opening this long after a routed use is taken as its answer. */
    private static final long INTERACTION_WINDOW_NANOS=2_000_000_000L;
    private static final long FRAME_INTERVAL_NANOS=16_000_000L;
    private static String pendingAction; private static long pendingSince;
    private static String instance, action; private static Screen hosted;
    private static long reportSequence; private static boolean activeSent;
    private static final GuiInputSequencer.Receiver inputs=new GuiInputSequencer.Receiver();
    private static long appliedInput, lastInputReceived;
    private static TextureTarget target;
    private static final GuiExporter exporter=new GuiExporter();
    private static FrameFile file;
    private static boolean capturing; private static int depth; private static long lastCapture;
    private static final SampleWindow inputHandleMs=new SampleWindow(1024);
    private static long metricSince;
    private GuiHost() {}

    /** Runs before the native use of a routed interaction, on the render thread. */
    public static void noteInteraction(String actionId) { pendingAction=actionId; pendingSince=System.nanoTime(); }
    /** The hosted screen opened by {@code actionId}, if it has opened yet. */
    public static String instanceFor(String actionId) { return actionId!=null && actionId.equals(action) ? instance : null; }
    public static boolean hosting() { return instance!=null; }
    /** While a screen is hosted its window counts as focused, so pointer movement and drags reach it. */
    public static boolean forceWindowActive() { return instance!=null && !BridgeCore.isPrimary(); }
    /** True for a key the primary holds while this runtime hosts its screen. */
    public static boolean keyHeld(int key) { return instance!=null && inputs.holds(key); }

    /** Every {@code Minecraft.setScreen} on a background runtime. */
    public static void screenChanged(Screen screen) {
        if(BridgeCore.isPrimary() || !BridgeCore.connected() || screen==hosted) return;
        if(screen==null) { if(instance!=null) end("closed"); return; }
        if(system(screen)) {
            if(instance!=null) end("system_screen");
            MmlfLog.debug("gui","system_screen_not_hosted",Map.of("screen",screen.getClass().getName()));
            return;
        }
        boolean asked=pendingAction!=null && System.nanoTime()-pendingSince<INTERACTION_WINDOW_NANOS;
        if(!asked && instance==null) {
            MmlfLog.info("gui","unsolicited_screen_ignored",Map.of("screen",screen.getClass().getName()));
            return;
        }
        String previous=instance;
        instance=UUID.randomUUID().toString(); hosted=screen; reportSequence=0; activeSent=false; appliedInput=0;
        if(asked) { action=pendingAction; pendingAction=null; }
        inputs.begin(instance); exporter.reset(); lastCapture=0;
        MmlfLog.info("gui","hosted_open",Map.of("instanceId",instance,"actionId",String.valueOf(action),"screen",screen.getClass().getName(),"container",screen instanceof AbstractContainerScreen<?>,"replaces",String.valueOf(previous)));
        report("OPENING",null);
    }

    /** Screens of the runtime's own game flow are never shown on the primary. */
    private static boolean system(Screen screen) {
        return screen instanceof PauseScreen || screen instanceof ChatScreen || screen instanceof DeathScreen || screen instanceof ReceivingLevelScreen
            || screen instanceof LevelLoadingScreen || screen instanceof GenericMessageScreen || screen instanceof ProgressScreen || screen instanceof TitleScreen
            || screen instanceof ConnectScreen || screen instanceof DisconnectedScreen || screen instanceof WaitingScreen
            || screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    private static void report(String state,String reason) {
        Minecraft mc=Minecraft.getInstance(); var window=mc.getWindow();
        String screen=hosted==null?null:hosted.getClass().getName();
        BridgeCore.send("gui",BridgeCore.primaryId(),new GuiState(instance,action,BridgeCore.runtimeId(),state,screen,hosted instanceof AbstractContainerScreen<?>,
            (int)window.getGuiScale(),mc.getMainRenderTarget().width,mc.getMainRenderTarget().height,++reportSequence,reason));
    }

    /** Ends the hosted screen: held input is released, the inventory goes back to the primary, then CLOSED is reported. */
    private static void end(String reason) {
        String ended=instance; String endedAction=action; long sequence=reportSequence;
        releaseHeld();
        instance=null; hosted=null; action=null; activeSent=false; exporter.reset();
        MmlfLog.info("gui","hosted_closed",Map.of("instanceId",ended,"reason",reason));
        InventoryBridge.returnLease(()->BridgeCore.send("gui",BridgeCore.primaryId(),new GuiState(ended,endedAction,BridgeCore.runtimeId(),"CLOSED",null,false,0,0,0,sequence+1,reason)));
    }

    /** The primary went away: the screen it was showing closes here too. */
    public static void primaryLost(String reason) {
        Minecraft mc=Minecraft.getInstance();
        InventoryBridge.primaryLost();
        if(instance==null) return;
        MmlfLog.warn("gui","hosted_abandoned",Map.of("instanceId",instance,"reason",reason));
        releaseHeld(); instance=null; action=null; Screen screen=hosted; hosted=null; exporter.reset();
        if(screen!=null && mc.screen==screen) closeNative(mc);
    }

    private static void closeNative(Minecraft mc) {
        if(mc.player!=null && mc.screen instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
        else if(mc.screen!=null) mc.screen.onClose();
    }

    public static void tick(Minecraft mc) {
        if(instance==null) return;
        if(hosted!=mc.screen) { screenChanged(mc.screen); return; }
        long now=System.nanoTime();
        if(metricSince==0) metricSince=now;
        if(now-metricSince>=10_000_000_000L) {
            var handled=inputHandleMs.drain();
            if(handled.count()>0) MmlfLog.info("metrics","gui_input_owner",Map.of("samples",handled.count(),"handleP50Ms",handled.p50(),"handleP95Ms",handled.p95(),"handleP99Ms",handled.p99(),"sequenceGaps",inputs.gaps()));
            metricSince=now;
        }
    }

    // ---- capture ----

    /** {@code Screen.renderWithTooltip} start: earlier GUI batches go to the main target, this screen to its own. */
    public static void beginCapture(Screen screen,GuiGraphics graphics) {
        if(depth++>0 || instance==null || screen!=hosted) return;
        long now=System.nanoTime();
        if(now-lastCapture<FRAME_INTERVAL_NANOS) return;
        lastCapture=now;
        Minecraft mc=Minecraft.getInstance(); var main=mc.getMainRenderTarget();
        graphics.flush();
        if(target==null) { target=new TextureTarget(main.width,main.height,true,Minecraft.ON_OSX); target.setClearColor(0,0,0,0); }
        else if(target.width!=main.width || target.height!=main.height) target.resize(main.width,main.height,Minecraft.ON_OSX);
        target.clear(Minecraft.ON_OSX); target.bindWrite(true);
        capturing=true;
    }
    /** {@code Screen.renderWithTooltip} end: flush into the hosted target, start its read-back, restore the main target. */
    public static void endCapture(Screen screen,GuiGraphics graphics) {
        if(--depth>0 || !capturing) { if(depth<0) depth=0; return; }
        Minecraft mc=Minecraft.getInstance();
        try {
            graphics.flush();
            capturing=false;
            final String id=instance; final long input=appliedInput; final int scale=(int)mc.getWindow().getGuiScale();
            final long captured=System.nanoTime();
            exporter.capture(target,frameId->Wire.JSON.toJson(new GuiFrame(BridgeCore.runtimeId(),BridgeCore.session().identity(),id,frameId,target.width,target.height,scale,captured,input)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(file==null) file=FrameFile.writer(BridgeCore.session().framePath(BridgeCore.runtimeId()+"-gui"));
            long published=exporter.drain(file);
            if(published>0 && !activeSent && instance!=null) { activeSent=true; report("ACTIVE",null); }
        } catch(Exception e) {
            MmlfLog.error("gui.capture","failed",Map.of("exception",e.toString()));
        } finally { capturing=false; mc.getMainRenderTarget().bindWrite(true); }
    }
    /** True while the hosted screen draws into its own target: blur is skipped and blending keeps coverage in alpha. */
    public static boolean capturing() { return capturing; }

    // ---- input ----

    public static void receive(GuiInput input) {
        Minecraft mc=Minecraft.getInstance(); long received=System.nanoTime();
        mc.execute(()->apply(mc,input,received));
    }
    private static void apply(Minecraft mc,GuiInput input,long received) {
        var verdict=inputs.accept(input);
        if(verdict!=GuiInputSequencer.Receiver.Verdict.ACCEPT) {
            MmlfLog.debug("gui","input_rejected",Map.of("verdict",verdict.name(),"instanceId",String.valueOf(input.instanceId()),"sequence",input.sequence()));
            return;
        }
        if(hosted==null || mc.screen!=hosted) return;
        long window=mc.getWindow().getWindow();
        var mouse=(dev.mmlf.bridge.mixin.MouseHandlerAccessor)mc.mouseHandler;
        double x=GuiCoordinates.toWindow(input.x(),mc.getWindow().getScreenWidth()), y=GuiCoordinates.toWindow(input.y(),mc.getWindow().getScreenHeight());
        try {
            switch(input.kind()) {
                case GuiInputSequencer.MOVE -> mouse.mmlf$onMove(window,x,y);
                case GuiInputSequencer.PRESS -> { mouse.mmlf$onMove(window,x,y); mouse.mmlf$onPress(window,input.button(),GLFW.GLFW_PRESS,input.modifiers()); }
                case GuiInputSequencer.RELEASE -> { mouse.mmlf$onMove(window,x,y); mouse.mmlf$onPress(window,input.button(),GLFW.GLFW_RELEASE,input.modifiers()); }
                case GuiInputSequencer.SCROLL -> { mouse.mmlf$onMove(window,x,y); mouse.mmlf$onScroll(window,input.scrollX(),input.scrollY()); }
                case GuiInputSequencer.KEY_DOWN -> key(mc,window,input,GLFW.GLFW_PRESS);
                case GuiInputSequencer.KEY_REPEAT -> key(mc,window,input,GLFW.GLFW_REPEAT);
                case GuiInputSequencer.KEY_UP -> key(mc,window,input,GLFW.GLFW_RELEASE);
                case GuiInputSequencer.CHAR -> {
                    if(input.text()!=null && input.text().length()<=64)
                        input.text().codePoints().forEach(cp->((dev.mmlf.bridge.mixin.KeyboardHandlerAccessor)mc.keyboardHandler).mmlf$charTyped(window,cp,input.modifiers()));
                }
                case GuiInputSequencer.RESIZE -> match(mc,input);
                case GuiInputSequencer.RELEASE_ALL -> { }
                case GuiInputSequencer.CLOSE -> closeNative(mc);
                default -> { }
            }
        } catch(RuntimeException e) {
            MmlfLog.error("gui","input_failed",Map.of("kind",input.kind(),"exception",e.toString()));
        }
        appliedInput=input.sequence();
        inputHandleMs.add((System.nanoTime()-received)/1e6);
        MmlfLog.sampled("gui","input_applied",1000,Map.of("kind",input.kind(),"sequence",input.sequence()));
    }
    /** Keys that act on this hidden window itself (fullscreen, screenshot, debug overlay) are not replayed. */
    private static void key(Minecraft mc,long window,GuiInput input,int action) {
        int key=input.key();
        if(key==GLFW.GLFW_KEY_F11 || key==GLFW.GLFW_KEY_F2 || key==GLFW.GLFW_KEY_F3 || key==GLFW.GLFW_KEY_F1) return;
        mc.keyboardHandler.keyPress(window,key,input.scancode(),action,input.modifiers());
    }
    /** The hosted screen uses the primary's GUI scale so it lays out as it would have there. */
    private static void match(Minecraft mc,GuiInput input) {
        int scale=input.guiScale();
        if(scale<1 || scale>16 || (int)mc.getWindow().getGuiScale()==scale) return;
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
        MmlfLog.info("gui","scale_matched",Map.of("guiScale",scale,"primaryWidth",input.width(),"primaryHeight",input.height()));
    }
    private static void releaseHeld() {
        Minecraft mc=Minecraft.getInstance(); long window=mc.getWindow().getWindow();
        var keys=inputs.heldKeys(); var buttons=inputs.drainButtons();
        if(hosted==null || mc.screen!=hosted) return;
        try {
            for(int[] key:keys) mc.keyboardHandler.keyPress(window,key[0],key[1],GLFW.GLFW_RELEASE,0);
            var mouse=(dev.mmlf.bridge.mixin.MouseHandlerAccessor)mc.mouseHandler;
            for(int button:buttons) mouse.mmlf$onPress(window,button,GLFW.GLFW_RELEASE,0);
        } catch(RuntimeException e) { MmlfLog.warn("gui","release_failed",Map.of("exception",e.toString())); }
    }
}
