package dev.mmlf.bridge;

import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Session;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.Map;

/**
 * Release sessions, which have no demo scenario: the primary plays its own menus and worlds, and each background runtime
 * opens a world of its own for the same save, created the first time with the same seed and world type, so its
 * dimensions and biomes are the primary's. With the shared world ({@link SharedWorld}) it reads the primary's map and
 * generates no terrain of its own. A background's world holds only what that runtime owns, so it never needs the
 * primary's save files.
 */
final class WorldFollow {
    private static final long ANNOUNCE_NANOS=5_000_000_000L;
    /** Primary: the save it last announced and when. */
    private static String announced;
    private static long announcedAt;
    /** Background: the save to open next and the one it has open or is opening. */
    private static Map<String,String> pending;
    private static String opened;
    private static boolean configured;
    private WorldFollow() {}

    /** Primary: a save of this session has been announced, so the background follows saves ({@link TestPlatform} is then off). */
    private static volatile boolean announcedAny;
    static boolean announcedAny() { return announcedAny; }
    /** Background: a world of the primary's save is open here. */
    static boolean following() { return opened!=null && configured; }
    static boolean enabled(Session session) { return session!=null && !Files.isRegularFile(session.root().resolve("scenario.json")); }

    static void tick(Minecraft mc,boolean primary) {
        if(primary) { autoOpen(mc); announce(mc); } else follow(mc);
    }

    /** Primary, development {@code MMLF_WORLD=<save>}: opened once from the title screen. */
    private static boolean autoOpened;
    /**
     * Opens the named save, or creates it the first time as a normal survival world with cheats allowed and the seed of
     * {@code MMLF_WORLD_SEED} (a fixed default, so repeated runs see the same terrain). {@code MMLF_WORLD=menu} leaves the
     * choice to the player.
     */
    private static void autoOpen(Minecraft mc) {
        if(autoOpened || mc.level!=null || !(mc.screen instanceof TitleScreen) || SessionBootstrap.release()) return;
        String save;
        try { save=dev.mmlf.launcher.SessionHost.realWorld(); } catch(IllegalArgumentException e) { autoOpened=true; MmlfLog.warn("world","auto_open_rejected",Map.of("reason",e.getMessage())); return; }
        if(save==null || save.equals("menu")) return;
        autoOpened=true;
        mc.options.pauseOnLostFocus=false;
        if(mc.getLevelSource().levelExists(save)) {
            MmlfLog.info("world","auto_open",Map.of("save",save));
            mc.createWorldOpenFlows().openWorld(save,()->mc.setScreen(new TitleScreen()));
            return;
        }
        long seed=20261110L;
        String text=System.getenv("MMLF_WORLD_SEED");
        if(text!=null && !text.isBlank()) try { seed=Long.parseLong(text.trim()); } catch(NumberFormatException e) { seed=text.trim().hashCode(); }
        MmlfLog.info("world","auto_create",Map.of("save",save,"seed",seed));
        var settings=new LevelSettings(save,GameType.SURVIVAL,false,Difficulty.NORMAL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(save,settings,new WorldOptions(seed,true,false),
            registries->registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).createWorldDimensions(),mc.screen);
    }

    /**
     * Announces the open single-player save again every few seconds, so a background that joins late still follows.
     * Starts as soon as the integrated server exists: the background loads its world while the primary loads its own.
     */
    private static void announce(Minecraft mc) {
        var server=mc.getSingleplayerServer();
        if(server==null) {
            synchronized(WorldFollow.class) {
                if(announced!=null) { BridgeCore.remoteWorldsClosed(); BridgeCore.sendReliable("event","*",new Event("mmlf:world_closed",Map.of())); MmlfLog.info("world","closed_sent",Map.of("save",announced)); announced=null; }
            }
            return;
        }
        announce(server);
    }
    /**
     * The open save, from any thread. Also called by a generation worker waiting for the background
     * ({@code CoGeneration}): while a new world prepares its spawn, the client thread waits for the server without
     * ticking, so the client-tick announcement would only go out after the chunks that wait for the background.
     */
    static synchronized void announce(net.minecraft.server.MinecraftServer server) {
        if(server==null || !BridgeCore.isPrimary() || BridgeCore.session()==null || !enabled(BridgeCore.session())) return;
        Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String save=root.getFileName().toString();
        long now=System.nanoTime();
        if(save.equals(announced) && now-announcedAt<ANNOUNCE_NANOS) return;
        if(!save.equals(announced)) MmlfLog.info("world","open_sent",Map.of("save",save));
        announcedAny=true;
        announced=save; announcedAt=now;
        // The overworld itself may not exist yet; its stem is part of the save's registries from the start.
        var stem=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
        boolean flat=stem!=null && stem.generator() instanceof net.minecraft.world.level.levelgen.FlatLevelSource;
        String id=worldId(root,true);
        BridgeCore.send("event","*",new Event("mmlf:world",Map.of("save",save,"seed",Long.toString(server.getWorldData().worldGenOptions().seed()),
            "flat",Boolean.toString(flat),"name",server.getWorldData().getLevelName(),"worldId",id==null?"":id,"root",root.toString())));
    }

    /**
     * Identity of a save beyond its folder name: a background's save follows exactly one primary save. A primary save
     * recreated under the same name, or a background save left from another one, would otherwise put old blocks of
     * this runtime into a different world. Kept in a small file in the save; null when it cannot be read or written.
     */
    private static final String ID_FILE="mmlf-world-id";
    private static String worldId(Path saveRoot,boolean create) {
        Path file=saveRoot.resolve(ID_FILE);
        try {
            if(Files.isRegularFile(file)) { String id=Files.readString(file).trim(); if(!id.isEmpty()) return id; }
            if(!create) return null;
            String id=java.util.UUID.randomUUID().toString();
            Files.writeString(file,id);
            return id;
        } catch(java.io.IOException e) { MmlfLog.warn("world","world_id_unavailable",Map.of("path",file.toString(),"exception",e.toString())); return null; }
    }
    /** Background: the primary's save identity it follows; matches worlds it kept in its own saves before (migration). */
    private static String followedId;

    /** Background, network thread: remembers the primary's save; the render thread opens it. */
    static void receive(Minecraft mc,String topic,Map<String,String> values) {
        mc.execute(()->{
            if(topic.equals("mmlf:world_closed")) { pending=null; close(mc); return; }
            String save=values.get("save");
            if(save==null || save.isBlank() || save.contains("/") || save.contains("\\") || save.contains("..")) return;
            if(!save.equals(opened)) pending=values;
        });
    }

    private static void follow(Minecraft mc) {
        // A hidden window cannot answer the first-run accessibility screen.
        if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
        if(opened!=null && mc.level!=null && mc.getSingleplayerServer()!=null && !configured) {
            configured=true;
            var server=mc.getSingleplayerServer();
            // Its weather and natural spawns would duplicate the primary's world. Difficulty follows the primary's
            // (BridgeCore.followPlay), so this runtime's own hostile mobs exist and hit as hard as there.
            server.execute(()->{
                // Its spawn area is not kept loaded: those chunks wait for the primary's terrain, which it may not have.
                server.overworld().getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);
                server.overworld().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
                server.overworld().getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
            });
        }
        if(pending==null) return;
        if(mc.level!=null) { if(!pending.get("save").equals(opened)) close(mc); return; }
        if(!(mc.screen instanceof TitleScreen) || !BridgeCore.initialLanguageReady() || BridgeCore.languageBusy()) return;
        Map<String,String> world=pending; pending=null;
        String save=world.get("save");
        opened=save; configured=false;
        mc.options.pauseOnLostFocus=false;
        followedId=world.get("worldId")==null || world.get("worldId").isBlank()?null:world.get("worldId");
        // One save: this runtime's world lives inside the primary's save, as <save>/mmlf/<runtime>.
        Path primaryRoot=primaryRoot(world,save);
        if(primaryRoot==null) { MmlfLog.error("world","primary_save_unknown",Map.of("save",save,"root",String.valueOf(world.get("root")))); opened=null; return; }
        var source=net.minecraft.world.level.storage.LevelStorageSource.createDefault(primaryRoot.resolve(CONTAINER));
        var flows=new net.minecraft.client.gui.screens.worldselection.WorldOpenFlows(mc,source);
        String level=BridgeCore.runtimeId();
        if(!source.levelExists(level)) migrate(mc,save,source.getLevelPath(level));
        if(source.levelExists(level)) {
            MmlfLog.info("world","open",Map.of("save",save,"path",source.getLevelPath(level).toString()));
            flows.openWorld(level,()->{ opened=null; mc.setScreen(new TitleScreen()); });
            return;
        }
        long seed;
        try { seed=Long.parseLong(world.get("seed")); } catch(RuntimeException e) { seed=0; }
        boolean flat=Boolean.parseBoolean(world.get("flat"));
        // Same seed and world type, so every dimension and its biomes match the primary's. With the shared world its
        // terrain is never generated here ({@link SharedWorld#hollow}), nor are structures.
        boolean hollow=SharedWorld.enabled();
        MmlfLog.info("world","create",Map.of("save",save,"flat",flat,"hollow",hollow,"path",source.getLevelPath(level).toString()));
        var rules=new GameRules();
        // Not kept loaded around its spawn from the start (see the open world's rules).
        rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,null);
        var settings=new LevelSettings(world.getOrDefault("name",save),GameType.CREATIVE,false,Difficulty.PEACEFUL,true,rules,WorldDataConfiguration.DEFAULT);
        var preset=flat?WorldPresets.FLAT:WorldPresets.NORMAL;
        flows.createFreshLevel(level,settings,new WorldOptions(seed,!hollow,false),
            registries->registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(preset).createWorldDimensions(),mc.screen);
    }

    /** Folder in the primary's save that holds the other runtimes' worlds. */
    static final String CONTAINER="mmlf";
    /**
     * The primary's save folder as announced: an existing folder of that name, else null. A new save has no level.dat
     * until its first save, after its first chunks were generated, which wait for this runtime (co-generation).
     */
    private static Path primaryRoot(Map<String,String> world,String save) {
        String text=world.get("root");
        if(text==null || text.isBlank()) return null;
        try {
            Path root=Path.of(text).toAbsolutePath().normalize();
            if(!root.getFileName().toString().equals(save) || !Files.isDirectory(root)) return null;
            return root;
        } catch(RuntimeException e) { return null; }
    }
    /**
     * A world this runtime kept in its own saves before (the same name, following the same primary save or one from
     * before save identities) moves into the primary's save. A world of another primary save stays where it is.
     */
    private static void migrate(Minecraft mc,String save,Path target) {
        Path old=mc.getLevelSource().getBaseDir().resolve(save);
        if(!Files.isRegularFile(old.resolve("level.dat"))) return;
        String had=worldId(old,false);
        if(had!=null && followedId!=null && !had.equals(followedId)) { MmlfLog.warn("world","migration_skipped",Map.of("save",save,"reason","other_primary_save")); return; }
        try {
            Files.createDirectories(target.getParent());
            try { Files.move(old,target); }
            catch(java.io.IOException acrossVolumes) {
                // Another drive: copied, and the original kept under a new name rather than deleted.
                try(var files=Files.walk(old)) {
                    for(Path from:files.toList()) {
                        if(from.getFileName().toString().equals("session.lock")) continue;
                        Path to=target.resolve(old.relativize(from).toString());
                        if(Files.isDirectory(from)) Files.createDirectories(to); else Files.copy(from,to);
                    }
                }
                Files.move(old,old.resolveSibling(save+"-mmlf-migrated"));
            }
            MmlfLog.info("world","migrated",Map.of("save",save,"from",old.toString(),"to",target.toString()));
        } catch(Exception e) { MmlfLog.error("world","migration_failed",Map.of("save",save,"exception",e.toString())); }
    }

    /** Leaves the open world, saving it, as the pause menu's quit button does. */
    private static void close(Minecraft mc) {
        if(mc.level==null) { opened=null; return; }
        MmlfLog.info("world","close",Map.of("save",String.valueOf(opened)));
        opened=null;
        boolean local=mc.isLocalServer();
        mc.level.disconnect();
        if(local) mc.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
        else mc.disconnect();
        mc.setScreen(new TitleScreen());
        BridgeCore.worldLeft();
        BridgeCore.forgetOwnedVanilla();
        SaveSync.worldClosed();
        CoGeneration.worldClosed();
    }
}
