package dev.mmlf.launcher.runtime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** A runtime a backend started, however it runs: its state, how it ended, and how to stop it. */
public interface RuntimeHandle {
    /**
     * Where a runtime is in its life. Process runtimes report only {@code RUNNING}; in-JVM runtimes report the phases
     * their bridge announces: the loader is starting ({@code STARTING}), the bridge is up inside the loader
     * ({@code LOADER_INIT}), the game ticks ({@code GAME_INIT}), its world is loaded ({@code IN_WORLD}), it was asked to
     * stop or is closing ({@code STOPPING}). {@code RUNNING} is a process runtime past its start.
     */
    enum State { STARTING, LOADER_INIT, GAME_INIT, IN_WORLD, RUNNING, STOPPING, EXITED, FAILED }
    /** How a runtime ended: exit code (0 for a normal stop) and, for a failure, why. */
    record Exit(int code,String failure) {
        public boolean failed() { return failure!=null || code!=0; }
    }
    String id();
    /** {@code process} or {@code injvm}. */
    String backend();
    State state();
    default boolean alive() { State s=state(); return s!=State.EXITED && s!=State.FAILED; }
    /** Completes once the runtime has ended. */
    CompletableFuture<Exit> exit();
    /**
     * Completes with the reason when the runtime stopped making progress while still alive (a start-up phase that
     * never ended). It may still exit later; nothing forces it. Backends that cannot tell never complete it.
     */
    default CompletableFuture<String> stalled() { return new CompletableFuture<>(); }
    /** Asks the runtime to stop and waits up to {@code timeout}; forces it where the backend can. */
    void stop(Duration timeout);
}
