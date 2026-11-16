package dev.mmlf.bridge.collab;
import dev.mmlf.bridge.transport.PoseChannel;

import dev.mmlf.bridge.platform.Experiments;
import dev.mmlf.bridge.transport.LanguageSync;
import dev.mmlf.bridge.transport.RemoteWaitScreen;
import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.WorldFollow;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import dev.mmlf.common.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;
import java.util.Map;
import static dev.mmlf.common.MessageRouter.Scope.*;

/**
 * Collaboration layer: the one logical player. The primary sends what owners must follow (hotbar, held item, footing,
 * game mode, play settings); a background follows it, its player standing where the primary's stands (moved from BridgeCore, design S7).
 */
public final class PlayerFollower {
    private PlayerFollower() {}
    static long lastResize;
    /**
     * Primary: the one logical player as owners must follow it. Owners run native logic for the same player, so its
     * hotbar slot, held item, footing, game mode and language follow the primary's: what a use consumes, how fast a
     * block breaks and what screens say match what the player sees here. Sent from the menus too, so a background has
     * switched language before it opens a world rather than reloading its resources in the middle of loading one. A
     * snapshot, sent on every change and once a second: a lost one is replaced by the next.
     */
    public static void sendPlayer(Minecraft mc) {
        long now=System.nanoTime();
        var player=mc.player;
        int selected=player==null?-1:player.getInventory().selected;
        String held=player==null?null:RemoteItems.held(player.getMainHandItem());
        boolean grounded=player==null || player.onGround();
        String mode=mc.gameMode==null?"survival":mc.gameMode.getPlayerMode().getName();
        String key=selected+"|"+held+"|"+grounded+"|"+mode+"|"+mc.options.languageCode;
        if(key.equals(lastPlayer) && now-playerSent<1_000_000_000L) return;
        if(!key.equals(lastPlayer)) MmlfLog.debug("player","state_sync",Map.of("selected",selected,"remoteHeld",held!=null && RemoteItemRef.parse(held).isPresent(),"gameMode",mode));
        lastPlayer=key; playerSent=now;
        SessionLink.send("player","*",new PlayerState(++playerVersion,selected,held,grounded,mode,mc.options.languageCode));
    }
    /**
     * Primary: how the game is played, which a background must match. Paused: its server stops with this one (machines,
     * mobs and their damage wait while the player is in a menu); waiting for the background's own world is no pause for
     * it. View and simulation distance: its blocks are loaded, run and drawn as far as this world's. Difficulty: its
     * mobs exist and hit as hard as in this world. Sent on change and every two seconds.
     */
    public static void sendPlay(Minecraft mc) {
        boolean paused=mc.isPaused() && !(mc.screen instanceof RemoteWaitScreen);
        var server=mc.getSingleplayerServer();
        String difficulty=server==null?"":server.getWorldData().getDifficulty().getKey();
        boolean spawning=server!=null && server.overworld().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING);
        String key=paused+"|"+mc.options.renderDistance().get()+"|"+mc.options.simulationDistance().get()+"|"+difficulty+"|"+spawning;
        long now=System.nanoTime();
        if(key.equals(lastPlay) && now-playSent<2_000_000_000L) return;
        lastPlay=key; playSent=now;
        SessionLink.sendReliable("event","*",new Event("mmlf:play",Map.of("paused",Boolean.toString(paused),"renderDistance",Integer.toString(mc.options.renderDistance().get()),
            "simulationDistance",Integer.toString(mc.options.simulationDistance().get()),"difficulty",difficulty,"mobSpawning",Boolean.toString(spawning))));
    }
    static String lastPlay="";
    static long playSent;
    /** Background: the primary is paused (its server stops ours through {@code Minecraft.isPaused}). */
    public static volatile boolean primaryPaused;
    public static boolean primaryPaused() { return !SessionLink.isPrimary() && primaryPaused && SessionLink.connected() && !SessionLink.stopping(); }
    /**
     * Background, leaving its world: the primary's pause no longer holds its server, which must run to handle the
     * client's leaving and stop; a paused integrated server handles no disconnect. The primary's next state says again.
     */
    public static void releasePause() { primaryPaused=false; }
    public static void followPlay(Minecraft mc,Map<String,String> values) {
        boolean paused=Boolean.parseBoolean(values.get("paused"));
        if(paused!=primaryPaused) MmlfLog.info("connection","pause_follow",Map.of("paused",paused));
        primaryPaused=paused;
        mc.execute(()->{
            if(!Experiments.SMALL_VIEW) try {
                int render=Integer.parseInt(values.get("renderDistance")),simulation=Integer.parseInt(values.get("simulationDistance"));
                if(mc.options.renderDistance().get()!=render) { mc.options.renderDistance().set(render); MmlfLog.info("connection","view_follow",Map.of("renderDistance",render)); }
                if(mc.options.simulationDistance().get()!=simulation) mc.options.simulationDistance().set(simulation);
            } catch(RuntimeException ignored) { }
            // Scenes set their own difficulty; a background following the primary's saves plays at the primary's.
            var server=mc.getSingleplayerServer();
            var difficulty=net.minecraft.world.Difficulty.byName(String.valueOf(values.get("difficulty")));
            if(server!=null && difficulty!=null && Worlds.followsSave() && WorldFollow.following()) server.execute(()->{
                if(server.getWorldData().getDifficulty()==difficulty) return;
                server.setDifficulty(difficulty,true);
                MmlfLog.info("connection","difficulty_follow",Map.of("difficulty",difficulty.getKey()));
            });
            // Natural spawning follows the primary's rule: this runtime spawns its mods' share of the world (MobSpawning).
            boolean spawning=MobSpawning.ENABLED && Boolean.parseBoolean(values.get("mobSpawning"));
            if(server!=null && values.containsKey("mobSpawning") && Worlds.followsSave() && WorldFollow.following()) server.execute(()->{
                var rule=server.overworld().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING);
                if(rule.get()==spawning) return;
                rule.set(spawning,server);
                MmlfLog.info("spawn","spawning_follow",Map.of("mobSpawning",spawning));
            });
        });
    }
    static String lastPlayer="";
    static long playerSent;
    /** Primary: the version of the last state it sent. Owner: of the last state it applied, 0 until the primary's first. */
    public static volatile long playerVersion;
    /** Owner, network thread: applies a newer player state; an older one, overtaken on the way, is ignored. */
    public static void followPlayer(Minecraft mc,PlayerState state) {
        synchronized(PlayerFollower.class) {
            if(state.version()<=playerVersion) { MmlfLog.sampled("player","stale_state",1000,Map.of("version",state.version(),"applied",playerVersion)); return; }
            playerVersion=state.version();
        }
        primaryGrounded=state.grounded();
        mc.execute(()->{
            if(state.held()!=null) RemoteItems.updateHeld(state.held(),state.selected());
            followGameMode(mc,state.gameMode());
            LanguageSync.followLanguage(mc,state.language(),SessionLink.session().primary());
        });
        var server=mc.getSingleplayerServer();
        if(server!=null && state.selected()>=0 && state.selected()<9) server.execute(()->{
            if(!server.getPlayerList().getPlayers().isEmpty()) server.getPlayerList().getPlayers().getFirst().getInventory().selected=state.selected();
        });
    }
    /** A connected background, whose player's position comes from the primary. */
    public static boolean followsPrimary() { return !SessionLink.isPrimary() && SessionLink.connected(); }
    /** Owner: the bridge player plays in the primary player's game mode. */
    static void followGameMode(Minecraft mc,String mode) {
        var server=mc.getSingleplayerServer(); if(mode==null || server==null) return;
        var type=net.minecraft.world.level.GameType.byName(mode,null); if(type==null) return;
        server.execute(()->{
            if(server.getPlayerList().getPlayers().isEmpty()) return;
            var player=server.getPlayerList().getPlayers().getFirst();
            if(player.gameMode.getGameModeForPlayer()==type) return;
            player.setGameMode(type);
            MmlfLog.info("connection","game_mode_follow",Map.of("mode",mode));
        });
    }
    static long dimensionFollowAt;
    /**
     * Background, server thread: its player is put where the primary's stands, without a teleport. A teleport waits for
     * the client to confirm it, and until then vanilla drops every use packet; done each frame, it dropped placements the
     * client had already predicted (the block appeared, then vanished). This runtime's own client sets the same position
     * each frame, so nothing needs confirming; the movement check is reset so its next movement packet starts from here,
     * and the chunks around the new position are tracked as after any move.
     */
    public static void follow(net.minecraft.server.level.ServerPlayer player,Pose p) {
        if(player.distanceToSqr(p.playerX(),p.playerY(),p.playerZ())<1.0E-8) return;
        player.absMoveTo(p.playerX(),p.playerY(),p.playerZ());
        player.connection.resetPosition();
        player.serverLevel().getChunkSource().move(player);
    }
    /**
     * Background: the primary is in another dimension. Its copy of the player goes there too (no portal is made or
     * used), so the chunks around the primary load here, its own blocks there run and are drawn, and interactions there
     * reach it. Retried every two seconds while the change is under way.
     */
    public static void followDimension(Minecraft mc,Pose p) {
        var server=mc.getSingleplayerServer(); long now=System.nanoTime();
        if(server==null || p.dimension()==null || now-dimensionFollowAt<2_000_000_000L) return;
        dimensionFollowAt=now;
        server.execute(()->{
            if(server.getPlayerList().getPlayers().isEmpty()) return;
            var player=server.getPlayerList().getPlayers().getFirst();
            if(player.level().dimension().location().toString().equals(p.dimension())) return;
            var target=RedstoneBridge.level(server,p.dimension());
            if(target==null) { MmlfLog.sampled("connection","dimension_unknown",10000,Map.of("dimension",p.dimension())); return; }
            MmlfLog.info("connection","dimension_follow",Map.of("from",player.level().dimension().location().toString(),"to",p.dimension()));
            player.teleportTo(target,p.playerX(),p.playerY(),p.playerZ(),p.yaw(),p.pitch());
        });
    }
    /**
     * Background runtimes, every frame before the crosshair pick: the player stands exactly on the primary player, with
     * the previous-position fields set too, so the native ray and model are not a tick of interpolation behind.
     */
    public static void followPrimaryFrame() {
        PoseChannel.poll();
        Pose p=PoseChannel.lastRemotePose; Minecraft mc=Minecraft.getInstance(); var player=mc.player;
        if(SessionLink.isPrimary() || p==null || player==null || mc.level==null || !mc.level.dimension().location().toString().equals(p.dimension())) return;
        player.setPos(p.playerX(),p.playerY(),p.playerZ());
        player.xo=player.xOld=p.playerX(); player.yo=player.yOld=p.playerY(); player.zo=player.zOld=p.playerZ();
        player.setYRot(p.yaw()); player.setXRot(p.pitch());
        player.yRotO=p.yaw(); player.xRotO=p.pitch();
        player.yHeadRot=player.yHeadRotO=p.yaw();
    }
    /** Remote runtimes render at the primary framebuffer size so colour, depth and hand pixels share one projection. */
    public static void matchPrimaryWindow(Minecraft mc,Pose p) {
        var target=mc.getMainRenderTarget();
        // render_scale experiment: the same view at a fraction of the primary's pixels; the compositor samples it by UV.
        int wantWidth=Math.max(1,(int)Math.round(p.width()*Experiments.SCALE)),wantHeight=Math.max(1,(int)Math.round(p.height()*Experiments.SCALE));
        if(target.width==wantWidth && target.height==wantHeight) return;
        long now=System.nanoTime(); if(now-lastResize<500_000_000L) return; lastResize=now;
        long window=mc.getWindow().getWindow();
        int[] ww=new int[1],wh=new int[1],fw=new int[1],fh=new int[1];
        org.lwjgl.glfw.GLFW.glfwGetWindowSize(window,ww,wh); org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window,fw,fh);
        double sx=fw[0]>0?(double)ww[0]/fw[0]:1,sy=fh[0]>0?(double)wh[0]/fh[0]:1;
        int width=(int)Math.round(wantWidth*sx),height=(int)Math.round(wantHeight*sy);
        MmlfLog.info("render.capture","match_primary_size",Map.of("fromWidth",target.width,"fromHeight",target.height,"toWidth",wantWidth,"toHeight",wantHeight,"windowWidth",width,"windowHeight",height));
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(window,width,height);
    }
    /**
     * A background's player only follows the primary's position, so its own physics never lands it. Breaking speed,
     * and the server's check of a finished break, depend on standing on the ground: the primary player's state is used.
     */
    static volatile boolean primaryGrounded=true;
    public static Boolean followedGround(net.minecraft.world.entity.Entity entity) {
        if(SessionLink.isPrimary() || !SessionLink.connected()) return null;
        return entity==Minecraft.getInstance().player ? primaryGrounded : null;
    }
}
