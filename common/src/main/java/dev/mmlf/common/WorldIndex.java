package dev.mmlf.common;

import java.util.*;
import static dev.mmlf.common.Messages.*;

/** Coordinator index: state authority stays at its owner, never at the display runtime. */
public final class WorldIndex {
    private final Map<String,ObjectState> objects = new HashMap<>();
    private final Map<String,String> locations = new HashMap<>();
    public synchronized void update(String sender, ObjectState next) {
        if (!sender.equals(next.owner()) || !next.id().equals(objectId(sender, next.dimension(), next.x(), next.y(), next.z(), next.generation())))
            throw new IllegalArgumentException("object owner/id mismatch");
        if (next.revision() < 1 || next.type() == null || next.state() == null)
            throw new IllegalArgumentException("invalid object state");
        ObjectState old = objects.get(next.id());
        if (old != null && next.revision() <= old.revision()) throw new IllegalArgumentException("stale revision");
        String occupant = locations.get(next.location());
        if (occupant != null && !occupant.equals(next.id())) throw new IllegalArgumentException("occupied position");
        if (next.type().equals("minecraft:air")) {
            objects.remove(next.id()); locations.remove(next.location());
        } else {
            objects.put(next.id(), next); locations.put(next.location(), next.id());
        }
    }
    public synchronized Optional<ObjectState> get(String id) { return Optional.ofNullable(objects.get(id)); }
    public synchronized List<ObjectState> snapshot() { return List.copyOf(objects.values()); }
    public synchronized void removeOwner(String owner) {
        objects.values().removeIf(o -> { if (!o.owner().equals(owner)) return false; locations.remove(o.location()); return true; });
        blocks.remove(owner);
    }

    /** Per owner, per chunk ("dimension@x,z"), its latest {@link ChunkBlocks}. */
    private final Map<String,Map<String,ChunkBlocks>> blocks = new HashMap<>();
    public synchronized void updateBlocks(String sender, ChunkBlocks next) {
        if (!sender.equals(next.owner()) || next.dimension() == null) throw new IllegalArgumentException("blocks owner mismatch");
        int kinds = next.kinds() == null ? 0 : next.kinds().size();
        if (next.positions() == null || next.positions().length != kinds) throw new IllegalArgumentException("invalid chunk blocks");
        for (int[] positions : next.positions()) if (positions == null) throw new IllegalArgumentException("invalid chunk blocks");
        var own = blocks.computeIfAbsent(sender, k -> new HashMap<>());
        if (next.empty()) own.remove(next.chunkKey()); else own.put(next.chunkKey(), next);
    }
    public synchronized List<ChunkBlocks> blocksSnapshot() {
        List<ChunkBlocks> out = new ArrayList<>();
        for (var own : blocks.values()) out.addAll(own.values());
        return out;
    }
    /** Whether {@code owner} last reported one of its plain blocks at the position. */
    public synchronized boolean ownsBlock(String owner, String dimension, int x, int y, int z) {
        var own = blocks.get(owner); if (own == null) return false;
        ChunkBlocks chunk = own.get(dimension + "@" + (x >> 4) + "," + (z >> 4)); if (chunk == null) return false;
        int packed = packInChunk(x, y, z);
        for (int[] positions : chunk.positions()) for (int p : positions) if (p == packed) return true;
        return false;
    }
    public synchronized boolean valid(String owner, RayHit hit) {
        if (isBulkId(hit.objectId())) throw new IllegalArgumentException("a hit on plain blocks is checked with ownsBlock");
        ObjectState o = objects.get(hit.objectId());
        return o != null && o.owner().equals(owner) && o.revision() == hit.revision();
    }
}
