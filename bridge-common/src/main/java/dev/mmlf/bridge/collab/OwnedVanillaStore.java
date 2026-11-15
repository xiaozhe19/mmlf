package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.MmlfLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Background: the vanilla blocks it owns ({@code Ownership.ownVanilla}) are kept with its world, one
 * {@code dimension packed-position} per line, so they are still its own after the world is opened again. Loaded before
 * the first chunk of a world is looked at; saved with each save point ({@link SaveSync}) and when the world closes; what was loaded goes with the world ({@link Worlds}).
 */
final class OwnedVanillaStore {
    private OwnedVanillaStore() {}
    /** This world's part of OwnedVanillaStore: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        volatile MinecraftServer loadedFor;
        final Map<String,Set<BlockPos>> dimensions=new java.util.concurrent.ConcurrentHashMap<>();
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("owned_vanilla",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    private static final String FILE="mmlf-owned-vanilla.txt";

    private static Path file(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve(FILE); }

    static void recorded(String dimension,BlockPos pos) {
        state().dimensions.computeIfAbsent(dimension,unused->java.util.concurrent.ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }
    static Set<BlockPos> positions(String dimension) { return state().dimensions.getOrDefault(dimension,Set.of()); }

    /** Server thread: once per opened world, before anything reads ownership. */
    static synchronized void ensureLoaded(MinecraftServer server) {
        if(server==null || state().loadedFor==server || SessionLink.isPrimary()) return;
        state().loadedFor=server;
        int before=state().dimensions.values().stream().mapToInt(Set::size).sum();
        MmlfLog.info("ownership","owned_vanilla_reset",Map.of("cleared",before,"epoch",(dev.mmlf.bridge.world.Worlds.current()==null?-1L:dev.mmlf.bridge.world.Worlds.current().epoch())));
        state().dimensions.clear();
        Path path=file(server);
        if(!Files.isRegularFile(path)) return;
        int count=0;
        try {
            for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
                int space=line.lastIndexOf(' '); if(space<0) continue;
                try {
                    BlockPos pos=BlockPos.of(Long.parseLong(line.substring(space+1).trim()));
                    recorded(line.substring(0,space),pos);
                    count++;
                } catch(NumberFormatException ignored) { }
            }
            MmlfLog.info("world","owned_vanilla_loaded",Map.of("positions",count));
        } catch(Exception e) { MmlfLog.warn("world","owned_vanilla_load_failed",Map.of("exception",e.toString())); }
    }
    static boolean save(MinecraftServer server) {
        if(server==null || SessionLink.isPrimary()) return true;
        var lines=new ArrayList<String>();
        state().dimensions.forEach((dimension,positions)->{
            for(BlockPos pos:positions) lines.add(dimension+" "+pos.asLong());
        });
        try {
            Path path=file(server);
            if(lines.isEmpty()) { Files.deleteIfExists(path); return true; }
            Path temporary=path.resolveSibling(FILE+".tmp");
            Files.write(temporary,lines,StandardCharsets.UTF_8);
            Files.move(temporary,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch(Exception e) {
            MmlfLog.warn("world","owned_vanilla_save_failed",Map.of("exception",e.toString()));
            return false;
        }
    }
}
