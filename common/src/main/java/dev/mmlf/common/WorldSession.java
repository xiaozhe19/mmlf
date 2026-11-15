package dev.mmlf.common;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One paired world: the world the primary has open and each background's world that follows it. Everything that
 * belongs to that world lives here ({@link Scoped}) and goes when it closes; nothing of it reaches the next world.
 * <p>
 * The mode is fixed when the world opens: a later change to the session (a scene ending, the test platform starting)
 * only decides the next world's mode. The epoch is the primary's number for this world, the same on every runtime.
 */
public final class WorldSession {
    public enum Mode {
        /** A developer or test-platform scene: every runtime builds the scene floor in a world of its own. */
        SCENE,
        /** The primary's save: a background opens its world inside it and reads the primary's map (shared world). */
        FOLLOWED_SAVE
    }
    public enum State {
        /** Opening: the world is being loaded or generated. */
        OPENING,
        /** Loaded and played. */
        READY,
        /** The primary started leaving; nothing of this world is shown or heard there any more, even if still open. */
        LEAVING,
        /** Closed; its state is gone. */
        CLOSED
    }
    /** World-scoped state of one module; closed with its world. */
    public interface Scoped { default void close() {} }
    /** Identifies one module's state; {@code create} makes it the first time it is asked for in a world. */
    public static final class Key<T extends Scoped> {
        private final String name; private final Supplier<T> create;
        public Key(String name,Supplier<T> create) { this.name=name; this.create=create; }
        @Override public String toString() { return name; }
    }

    private final long epoch;
    private final Mode mode;
    private final String save;
    private final Object owner;
    private volatile State state=State.OPENING;
    /** Read without locking (collision and block reads ask on every call); made and closed under {@link #order}'s lock. */
    private final Map<Key<?>,Scoped> scoped=new ConcurrentHashMap<>();
    private final List<Scoped> order=new ArrayList<>();

    WorldSession(long epoch,Mode mode,String save,Object owner) { this.epoch=epoch; this.mode=mode; this.save=save; this.owner=owner; }

    public long epoch() { return epoch; }
    public Mode mode() { return mode; }
    /** The primary's save folder name (or the scene's), as announced. */
    public String save() { return save; }
    /** What this runtime opened the world with (its integrated server on the primary), or null. */
    public Object owner() { return owner; }
    public State state() { return state; }
    public boolean closed() { return state==State.CLOSED; }
    public boolean leaving() { return state==State.LEAVING; }

    /**
     * This world's state of a module, made on first use. Asked of a closed world it is made fresh and not kept: a late
     * message writes into it and nothing reads it again, so nothing of an old world reaches the next one.
     */
    @SuppressWarnings("unchecked")
    public <T extends Scoped> T get(Key<T> key) {
        Scoped known=scoped.get(key);
        if(known!=null && state!=State.CLOSED) return (T)known;
        synchronized(order) {
            if(state==State.CLOSED) return key.create.get();
            Scoped value=scoped.get(key);
            if(value==null) { value=key.create.get(); scoped.put(key,value); order.add(value); }
            return (T)value;
        }
    }

    // ---- transitions, by WorldSessions ----

    boolean advance(State next) {
        State now=state;
        boolean allowed=switch(next) {
            case OPENING -> false;
            case READY -> now==State.OPENING;
            case LEAVING -> now==State.OPENING || now==State.READY;
            case CLOSED -> now!=State.CLOSED;
        };
        if(allowed) state=next;
        return allowed;
    }
    /** Closes every module's state, the latest made first; one failing does not keep the others open. */
    List<Throwable> closeScoped() {
        List<Scoped> values;
        synchronized(order) { values=new ArrayList<>(order); order.clear(); scoped.clear(); }
        List<Throwable> failures=new ArrayList<>();
        for(int i=values.size()-1;i>=0;i--) try { values.get(i).close(); } catch(RuntimeException e) { failures.add(e); }
        return failures;
    }
    @Override public String toString() { return "world#"+epoch+"("+mode+","+state+")"; }
}
