package dev.mmlf.common;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * The other runtimes of the session as the Coordinator reports them, and which of them reported a loaded world.
 * <p>
 * Reports arrive on the link's delivery thread. The set of online runtimes is kept for the client thread: it is changed
 * there, through {@code clientThread}, before any listener runs, so a listener's own client-thread work sees it changed.
 * Listeners run on the delivery thread, in registration order, and move work to other threads themselves.
 * Peers only keeps the sets: what other modules do when a runtime comes or goes is theirs, as listeners.
 */
public final class Peers {
    public interface Listener {
        /** The Coordinator registered this runtime; {@code runtimes} is everyone online, this runtime included. */
        default void welcomed(Collection<String> runtimes) {}
        default void joined(String runtime) {}
        default void left(String runtime) {}
        /** This runtime lost its link; every other runtime is gone with it. */
        default void disconnected() {}
    }

    private final Executor clientThread;
    /** Client thread only. */
    private final Set<String> online=new HashSet<>();
    private final Set<String> onlineView=Collections.unmodifiableSet(online);
    private final Set<String> worldReady=ConcurrentHashMap.newKeySet();
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();

    public Peers(Executor clientThread) { this.clientThread=clientThread; }
    public void listen(Listener listener) { listeners.add(listener); }

    // ---- delivery thread: the Coordinator's reports ----

    public void welcomed(Collection<String> runtimes) {
        List<String> copy=runtimes==null?List.of():List.copyOf(runtimes);
        clientThread.execute(()->{ online.clear(); online.addAll(copy); });
        for(Listener l:listeners) l.welcomed(copy);
    }
    public void joined(String runtime) {
        clientThread.execute(()->online.add(runtime));
        for(Listener l:listeners) l.joined(runtime);
    }
    public void left(String runtime) {
        worldReady.remove(runtime);
        clientThread.execute(()->online.remove(runtime));
        for(Listener l:listeners) l.left(runtime);
    }
    public void disconnected() {
        worldReady.clear();
        clientThread.execute(online::clear);
        for(Listener l:listeners) l.disconnected();
    }

    // ---- world readiness, any thread ----

    /** {@code runtime} reported a loaded world. */
    public void worldReady(String runtime) { worldReady.add(runtime); }
    /** Every runtime reports its world again: the worlds were closed. */
    public void worldsClosed() { worldReady.clear(); }
    public boolean anyWorldReady() { return !worldReady.isEmpty(); }
    public Set<String> worldReadyRuntimes() { return Set.copyOf(worldReady); }

    // ---- client thread ----

    /** Everyone online, this runtime included; a live view for the client thread. */
    public Set<String> online() { return onlineView; }
    public boolean anyOtherThan(String self) { for(String p:online) if(!p.equals(self)) return true; return false; }
}
