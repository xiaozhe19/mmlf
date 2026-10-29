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
 * opens a world of its own for the same save, created with the same seed and world type the first time. A background's
 * world holds only what that runtime owns, so it never needs the primary's save files.
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

    static boolean enabled(Session session) { return session!=null && !Files.isRegularFile(session.root().resolve("scenario.json")); }

    static void tick(Minecraft mc,boolean primary) {
        if(primary) announce(mc); else follow(mc);
    }

    /**
     * Announces the open single-player save again every few seconds, so a background that joins late still follows.
     * Starts as soon as the integrated server exists: the background loads its world while the primary loads its own.
     */
    private static void announce(Minecraft mc) {
        var server=mc.getSingleplayerServer();
        if(server==null) {
            if(announced!=null) { BridgeCore.remoteWorldsClosed(); BridgeCore.send("event","*",new Event("mmlf:world_closed",Map.of())); MmlfLog.info("world","closed_sent",Map.of("save",announced)); announced=null; }
            return;
        }
        Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String save=root.getFileName().toString();
        long now=System.nanoTime();
        if(save.equals(announced) && now-announcedAt<ANNOUNCE_NANOS) return;
        if(!save.equals(announced)) MmlfLog.info("world","open_sent",Map.of("save",save));
        announced=save; announcedAt=now;
        // The overworld itself may not exist yet; its stem is part of the save's registries from the start.
        var stem=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
        boolean flat=stem!=null && stem.generator() instanceof net.minecraft.world.level.levelgen.FlatLevelSource;
        BridgeCore.send("event","*",new Event("mmlf:world",Map.of("save",save,"seed",Long.toString(server.getWorldData().worldGenOptions().seed()),
            "flat",Boolean.toString(flat),"name",server.getWorldData().getLevelName())));
    }

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
            // Its mobs and weather would act on a player only the primary's world should affect.
            server.execute(()->{
                server.setDifficulty(Difficulty.PEACEFUL,true);
                server.overworld().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
                server.overworld().getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
            });
        }
        if(pending==null) return;
        if(mc.level!=null) { if(!pending.get("save").equals(opened)) close(mc); return; }
        if(!(mc.screen instanceof TitleScreen) || BridgeCore.languageBusy()) return;
        Map<String,String> world=pending; pending=null;
        String save=world.get("save");
        opened=save; configured=false;
        mc.options.pauseOnLostFocus=false;
        if(mc.getLevelSource().levelExists(save)) {
            MmlfLog.info("world","open",Map.of("save",save));
            mc.createWorldOpenFlows().openWorld(save,()->{ opened=null; mc.setScreen(new TitleScreen()); });
            return;
        }
        long seed;
        try { seed=Long.parseLong(world.get("seed")); } catch(RuntimeException e) { seed=0; }
        boolean flat=Boolean.parseBoolean(world.get("flat"));
        MmlfLog.info("world","create",Map.of("save",save,"flat",flat));
        var settings=new LevelSettings(world.getOrDefault("name",save),GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
        var preset=flat?WorldPresets.FLAT:WorldPresets.NORMAL;
        mc.createWorldOpenFlows().createFreshLevel(save,settings,new WorldOptions(seed,true,false),
            registries->registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(preset).createWorldDimensions(),mc.screen);
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
    }
}
