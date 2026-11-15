package dev.mmlf.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This runtime's current {@link WorldSession}, at most one. Opening a world closes the one before; transitions are
 * serialized here and reported to listeners, in registration order, on the thread that made them.
 */
public final class WorldSessions {
    public interface Listener {
        default void opened(WorldSession world) {}
        default void ready(WorldSession world) {}
        default void leaving(WorldSession world) {}
        /** After every module's state of {@code world} was closed. */
        default void closed(WorldSession world) {}
    }

    private volatile WorldSession current;
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();

    public void listen(Listener listener) { listeners.add(listener); }
    /** The open world, or null. Read it once and keep it: a handler that does writes only to the world it saw. */
    public WorldSession current() { return current; }

    /** Opens a world, closing the current one first. */
    public synchronized WorldSession open(long epoch,WorldSession.Mode mode,String save,Object owner,String reason) {
        WorldSession previous=current;
        if(previous!=null) close(previous,"replaced");
        WorldSession world=new WorldSession(epoch,mode,save,owner);
        current=world;
        MmlfLog.info("world","session_opened",Map.of("epoch",epoch,"mode",mode.name(),"save",String.valueOf(save),"reason",reason));
        for(Listener l:listeners) l.opened(world);
        return world;
    }
    public synchronized void ready(WorldSession world) {
        if(world==null || world!=current || !world.advance(WorldSession.State.READY)) return;
        MmlfLog.info("world","session_ready",Map.of("epoch",world.epoch()));
        for(Listener l:listeners) l.ready(world);
    }
    public synchronized void leaving(WorldSession world,String reason) {
        if(world==null || world!=current || !world.advance(WorldSession.State.LEAVING)) return;
        MmlfLog.info("world","session_leaving",Map.of("epoch",world.epoch(),"reason",reason));
        for(Listener l:listeners) l.leaving(world);
    }
    public synchronized void close(WorldSession world,String reason) {
        if(world==null || !world.advance(WorldSession.State.CLOSED)) return;
        if(world==current) current=null;
        List<Throwable> failures=world.closeScoped();
        for(Throwable t:failures) MmlfLog.warn("world","session_close_failed",Map.of("epoch",world.epoch(),"exception",t.toString()));
        MmlfLog.info("world","session_closed",Map.of("epoch",world.epoch(),"reason",reason));
        for(Listener l:listeners) l.closed(world);
    }
}
