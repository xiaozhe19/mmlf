package dev.mmlf.common;

import java.util.*;
import static dev.mmlf.common.Messages.*;

/** Coordinator index: state authority stays at its owner, never at the display runtime. */
public final class WorldIndex {
    private final Map<String,ObjectState> objects = new HashMap<>();
    private final Map<String,String> locations = new HashMap<>();
    public synchronized void update(String sender, ObjectState next) {
        if (!sender.equals(next.owner()) || !next.id().equals(objectId(sender, next.dimension(), next.x(), next.y(), next.z())))
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
    }
    public synchronized boolean valid(String owner, RayHit hit) {
        ObjectState o = objects.get(hit.objectId());
        return o != null && o.owner().equals(owner) && o.revision() == hit.revision();
    }
}
