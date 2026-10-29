package dev.mmlf.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/**
 * Primary only: what the player sees of the background runtime, whose window is hidden. A panel in the corner follows it
 * from the session manager's first step to a loaded world; a failure opens an error screen instead of stopping the game.
 */
public final class SessionStatus {
    enum Phase { IDLE, MANAGER, LAUNCHING, CONNECTED, READY, LOST, FAILED }
    private static final Map<String,String> NAMES=Map.of("fabric","Fabric","neoforge","NeoForge");
    /** Until a session has been timed on this machine; the measured time replaces it. */
    private static final long DEFAULT_STARTUP_MS=60_000, WORLD_EXPECTED_MS=15_000, WORLD_SLOW_MS=90_000, NOTICE_MS=10_000, READY_SHOWN_MS=5_000;
    private static final int BLUE=0xFF4C9AFF, GREEN=0xFF55D46A, YELLOW=0xFFFFC23D, RED=0xFFFF5555;
    private static volatile Phase phase=Phase.IDLE;
    private static volatile String step="";
    private static volatile float stepProgress;
    private static String remote="远端", error;
    private static Path timing, remoteLog, errorLocation;
    private static long since, worldSince, expectedMs=DEFAULT_STARTUP_MS;
    private static boolean errorShown;
    /** The world the primary was last held in until the remote's was ready; each world holds it once. */
    private static Object heldWorld;
    private SessionStatus() {}

    static String displayName(String runtime) { return NAMES.getOrDefault(runtime,runtime); }
    static boolean failed() { return phase==Phase.FAILED; }

    /** Release builds, before the manager starts: remembers how long the remote took last time, for the progress bar. */
    static void begin(String remoteRuntime,Path gameDirectory) {
        remote=displayName(remoteRuntime);
        timing=gameDirectory.resolve("mmlf").resolve("remote-startup-ms.txt");
        try { expectedMs=Math.clamp(Long.parseLong(Files.readString(timing).trim()),10_000L,300_000L); }
        catch(Exception e) { expectedMs=DEFAULT_STARTUP_MS; }
        enter(Phase.MANAGER);
    }
    /** Manager thread: one of the manager's steps before it starts the remote process. */
    static void managerStep(float progress,String text) { stepProgress=progress; step=text; }
    /** The remote process runs and writes {@code log}; a development host enters here directly. */
    static void launched(String remoteRuntime,Path log) {
        if(phase==Phase.FAILED) return;
        remote=displayName(remoteRuntime); remoteLog=log;
        enter(Phase.LAUNCHING);
    }
    /** Ignored outside a session start, so a plain or background game never shows it. */
    static void fail(String message,Path location) {
        if(phase==Phase.IDLE || phase==Phase.FAILED) return;
        error=message; errorLocation=location; errorShown=false;
        dev.mmlf.common.MmlfLog.error("session","remote_unavailable",Map.of("message",message));
        enter(Phase.FAILED);
    }
    private static void enter(Phase next) { phase=next; since=System.nanoTime(); }
    private static long elapsedMs() { return (System.nanoTime()-since)/1_000_000L; }

    /** The remote is still on its way: launching, or connected without its world. */
    static boolean remotePending(boolean worldReady) { return phase==Phase.LAUNCHING || phase==Phase.MANAGER || phase==Phase.CONNECTED && !worldReady; }

    /**
     * Render thread, every tick of a primary or of a release game still starting its session. With {@code follow} the
     * remote opens the primary's save, and the primary waits on a pausing screen in its world until the remote's is ready.
     */
    static void tick(Minecraft mc,boolean joined,boolean worldReady,boolean follow) {
        switch(phase) {
            case LAUNCHING -> { if(joined) { remember(elapsedMs()); enter(Phase.CONNECTED); } }
            case CONNECTED -> {
                if(!joined) enter(Phase.LOST);
                else if(worldReady && mc.level!=null) enter(Phase.READY);
            }
            case READY -> { if(!joined) enter(Phase.LOST); else if(!worldReady) enter(Phase.CONNECTED); }
            case LOST -> { if(joined) enter(Phase.CONNECTED); }
            case FAILED -> {
                // Not over a world being played: the screen waits for the title screen, the panel says so meanwhile.
                if(!errorShown && mc.screen instanceof TitleScreen) { errorShown=true; mc.setScreen(new SessionErrorScreen(error,errorLocation,mc.screen)); }
            }
            default -> { }
        }
        if(phase!=Phase.CONNECTED || mc.level==null) worldSince=0;
        else if(worldSince==0) worldSince=System.nanoTime();
        if(mc.level==null) heldWorld=null;
        else if(follow && mc.player!=null && mc.screen==null && heldWorld!=mc.level && remotePending(worldReady)) {
            heldWorld=mc.level;
            mc.setScreen(new RemoteWaitScreen(()->remotePending(worldReady())));
        }
    }
    private static boolean worldReady() { return BridgeCore.remoteWorldReady(); }
    private static void remember(long ms) {
        if(timing==null) return;
        try { Files.createDirectories(timing.getParent()); Files.writeString(timing,Long.toString(ms),StandardCharsets.UTF_8); }
        catch(Exception ignored) { }
    }

    record View(String title,String text,float progress,int color) {}
    static View view(Minecraft mc) {
        long ms=elapsedMs(), seconds=ms/1000;
        boolean menus=mc.level==null;
        return switch(phase) {
            case IDLE -> null;
            case MANAGER -> new View("MMLF：正在启动远端 "+remote,(step.isEmpty()?"启动会话管理器":step)+"… "+seconds+" 秒",0.15f*stepProgress,BLUE);
            case LAUNCHING -> {
                float progress=0.15f+0.7f*Math.min(0.97f,(float)ms/expectedMs);
                boolean slow=ms>Math.max(180_000L,3*expectedMs);
                String text="远端 "+remote+" 加载中… "+seconds+" 秒"+(slow && remoteLog!=null?"\n耗时异常，可查看日志："+remoteLog:"");
                yield new View("MMLF：正在启动远端 "+remote,text,progress,slow?YELLOW:BLUE);
            }
            case CONNECTED -> {
                if(menus) yield new View("MMLF：远端 "+remote+" 已连接","进入世界后，远端会同步打开同一存档",1,GREEN);
                long loading=worldSince==0?0:(System.nanoTime()-worldSince)/1_000_000L;
                boolean slow=loading>WORLD_SLOW_MS;
                String text=loading/1000+" 秒"+(slow && remoteLog!=null?"\n耗时异常，远端可能卡住了，可查看日志："+remoteLog:"");
                yield new View("MMLF：远端 "+remote+" 正在加载世界",text,0.85f+0.15f*Math.min(0.9f,(float)loading/WORLD_EXPECTED_MS),slow?YELLOW:BLUE);
            }
            case READY -> menus || ms>READY_SHOWN_MS ? null : new View("MMLF：远端 "+remote+" 已就绪","远端内容已接入当前世界",1,GREEN);
            case LOST -> !menus && ms>NOTICE_MS ? null : new View("MMLF：远端 "+remote+" 已断开","远端内容暂不可用",1,YELLOW);
            case FAILED -> !menus && ms>NOTICE_MS ? null
                : new View("MMLF：远端 "+remote+" 不可用",menus?error:"返回标题界面查看详情",1,RED);
        };
    }

    /** Drawn over menus by the screen hook and over the world by the HUD hook; never by a hidden background window. */
    public static void render(GuiGraphics graphics) {
        if(phase==Phase.IDLE || GuiHost.capturing()) return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.screen instanceof RemoteWaitScreen) return;
        View view=view(mc);
        if(view==null) return;
        var font=mc.font;
        int x=6, y=6, width=Math.min(240,graphics.guiWidth()-12), inner=width-12;
        var lines=font.split(Component.literal(view.text()),inner);
        // A long error is shown in full on its own screen; the panel stays small.
        if(lines.size()>3) lines=lines.subList(0,3);
        int height=5+font.lineHeight+2+lines.size()*font.lineHeight+3+3+5;
        graphics.pose().pushPose();
        graphics.pose().translate(0,0,400);
        graphics.fill(x,y,x+width,y+height,0xC0101010);
        graphics.fill(x,y,x+2,y+height,view.color());
        int line=y+5;
        graphics.drawString(font,view.title(),x+6,line,0xFFFFFFFF);
        line+=font.lineHeight+2;
        for(var text:lines) { graphics.drawString(font,text,x+6,line,0xFFB0B0B0); line+=font.lineHeight; }
        line+=3;
        graphics.fill(x+6,line,x+6+inner,line+3,0xFF3A3A3A);
        graphics.fill(x+6,line,x+6+Math.round(inner*Math.clamp(view.progress(),0f,1f)),line+3,view.color());
        graphics.pose().popPose();
    }
}
