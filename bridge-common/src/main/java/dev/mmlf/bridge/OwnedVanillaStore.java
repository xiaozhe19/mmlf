package dev.mmlf.bridge;

import dev.mmlf.common.MmlfLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Background: the vanilla blocks it owns ({@code BridgeCore.ownVanilla}) are kept with its world, one
 * {@code dimension packed-position} per line, so they are still its own after the world is opened again. Loaded before
 * the first chunk of a world is looked at; saved with each save point ({@link SaveSync}) and when the world closes.
 */
final class OwnedVanillaStore {
    private OwnedVanillaStore() {}
    private static final String FILE="mmlf-owned-vanilla.txt";
    private static volatile MinecraftServer loadedFor;
    /** Dimension of each owned position, as recorded. */
    private static final Map<BlockPos,String> dimensions=new java.util.concurrent.ConcurrentHashMap<>();

    private static Path file(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve(FILE); }

    static void recorded(String dimension,BlockPos pos) { dimensions.put(pos.immutable(),dimension); }

    /** Server thread: once per opened world, before anything reads ownership. */
    static synchronized void ensureLoaded(MinecraftServer server) {
        if(server==null || loadedFor==server || BridgeCore.isPrimary()) return;
        loadedFor=server;
        dimensions.clear();
        Path path=file(server);
        if(!Files.isRegularFile(path)) return;
        int count=0;
        try {
            for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
                int space=line.lastIndexOf(' '); if(space<0) continue;
                try {
                    BlockPos pos=BlockPos.of(Long.parseLong(line.substring(space+1).trim()));
                    dimensions.put(pos,line.substring(0,space));
                    BridgeCore.restoreOwnedVanilla(pos);
                    count++;
                } catch(NumberFormatException ignored) { }
            }
            MmlfLog.info("world","owned_vanilla_loaded",Map.of("positions",count));
        } catch(Exception e) { MmlfLog.warn("world","owned_vanilla_load_failed",Map.of("exception",e.toString())); }
    }
    static void save(MinecraftServer server) {
        if(server==null || BridgeCore.isPrimary()) return;
        var lines=new ArrayList<String>();
        for(BlockPos pos:BridgeCore.ownedVanillaPositions()) lines.add(dimensions.getOrDefault(pos,"minecraft:overworld")+" "+pos.asLong());
        try {
            Path path=file(server);
            if(lines.isEmpty()) { Files.deleteIfExists(path); return; }
            Path temporary=path.resolveSibling(FILE+".tmp");
            Files.write(temporary,lines,StandardCharsets.UTF_8);
            Files.move(temporary,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch(Exception e) { MmlfLog.warn("world","owned_vanilla_save_failed",Map.of("exception",e.toString())); }
    }
    /** The world closed: the next one loads its own. */
    static synchronized void closed() { loadedFor=null; dimensions.clear(); }
}
