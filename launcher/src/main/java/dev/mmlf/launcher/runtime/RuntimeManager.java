package dev.mmlf.launcher.runtime;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.coordinator.CoordinatorCore;
import dev.mmlf.launcher.SessionHost;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Owns one session's runtimes, whatever backend runs them: starts them, follows their state, ends the session when
 * the primary is gone, and cleans everything up once. The session (directory, Coordinator) belongs to the manager.
 */
public final class RuntimeManager implements AutoCloseable {
    /** Backend names for {@code -Dmmlf.backend}; in-JVM is the architecture, process the transitional fallback. */
    public static final String IN_JVM="injvm", PROCESS="process";
    private final SessionHost session;
    private final RuntimeBackend backend;
    private final List<RuntimeHandle> runtimes=new CopyOnWriteArrayList<>();
    private final List<BiConsumer<RuntimeHandle,RuntimeHandle.Exit>> exitListeners=new CopyOnWriteArrayList<>();
    private boolean closed;

    public RuntimeManager(SessionHost session,RuntimeBackend backend) { this.session=Objects.requireNonNull(session); this.backend=Objects.requireNonNull(backend); }
    /** The backend this JVM is configured for; in-JVM unless {@code -Dmmlf.backend=process}. */
    public static String configuredBackend() {
        String name=System.getProperty("mmlf.backend",System.getenv().getOrDefault("MMLF_BACKEND",IN_JVM)).toLowerCase(Locale.ROOT);
        if(!name.equals(IN_JVM) && !name.equals(PROCESS)) throw new IllegalArgumentException("unknown MMLF backend "+name);
        return name;
    }
    public static RuntimeBackend backend(String name,SessionHost session) {
        return name.equals(PROCESS)?new ProcessBackend(session):new InJvmBackend(session);
    }

    public SessionHost session() { return session; }
    public RuntimeBackend backend() { return backend; }
    public CoordinatorCore coordinator() { return session.core(); }
    public List<RuntimeHandle> runtimes() { return List.copyOf(runtimes); }
    public void onExit(BiConsumer<RuntimeHandle,RuntimeHandle.Exit> listener) { exitListeners.add(listener); }
    private final List<BiConsumer<RuntimeHandle,String>> stallListeners=new CopyOnWriteArrayList<>();
    /** Told when a runtime stops making progress while still alive (see {@link RuntimeHandle#stalled()}). */
    public void onStall(BiConsumer<RuntimeHandle,String> listener) { stallListeners.add(listener); }

    public synchronized RuntimeHandle start(RuntimeSpec spec) throws Exception {
        if(closed) throw new IllegalStateException("session closed");
        if(runtimes.stream().anyMatch(r->r.id().equals(spec.id()) && r.alive())) throw new IllegalStateException("runtime "+spec.id()+" already running");
        RuntimeHandle handle=backend.start(spec);
        runtimes.add(handle);
        MmlfLog.info("launcher","runtime_started",Map.of("runtimeId",spec.id(),"loader",spec.loader(),"backend",backend.name()));
        handle.exit().whenComplete((exit,error)->{
            RuntimeHandle.Exit result=exit!=null?exit:new RuntimeHandle.Exit(-1,String.valueOf(error));
            MmlfLog.info("launcher","runtime_exited",Map.of("runtimeId",handle.id(),"backend",handle.backend(),"code",result.code(),"failure",String.valueOf(result.failure())));
            for(var listener:exitListeners) try { listener.accept(handle,result); } catch(RuntimeException e) { MmlfLog.error("launcher","exit_listener_failed",Map.of("exception",e.toString())); }
        });
        handle.stalled().thenAccept(reason->{
            for(var listener:stallListeners) try { listener.accept(handle,reason); } catch(RuntimeException e) { MmlfLog.error("launcher","stall_listener_failed",Map.of("exception",e.toString())); }
        });
        return handle;
    }

    public enum End { PRIMARY_EXITED, PRIMARY_DISCONNECTED, REGISTRATION_TIMEOUT, RUNTIMES_EXITED }
    /**
     * Waits until the session is over: the primary's process is gone, the primary left the Coordinator, it never
     * registered within {@code registration}, or (when asked) every started runtime has ended.
     */
    public End supervise(BooleanSupplier primaryAlive,Duration registration,boolean endWhenRuntimesExit) throws InterruptedException {
        CoordinatorCore core=session.core();
        long deadline=System.nanoTime()+registration.toNanos();
        while(true) {
            if(!primaryAlive.getAsBoolean()) return log(End.PRIMARY_EXITED);
            if(core.hadPrimary() && !core.hasPrimary()) return log(End.PRIMARY_DISCONNECTED);
            if(!core.hadPrimary() && System.nanoTime()>deadline) return log(End.REGISTRATION_TIMEOUT);
            if(endWhenRuntimesExit && !runtimes.isEmpty() && runtimes.stream().noneMatch(RuntimeHandle::alive)) return log(End.RUNTIMES_EXITED);
            Thread.sleep(1000);
        }
    }
    private static End log(End end) { MmlfLog.info("launcher","session_end",Map.of("reason",end.name())); return end; }

    /** Stops every runtime this manager started (newest first), then the session itself. Safe to call twice. */
    @Override public void close() {
        synchronized(this) { if(closed) return; closed=true; }
        List<RuntimeHandle> copy=new ArrayList<>(runtimes); Collections.reverse(copy);
        for(RuntimeHandle r:copy) {
            try { if(r.alive()) r.stop(Duration.ofSeconds(10)); }
            catch(RuntimeException e) { MmlfLog.error("launcher","runtime_stop_failed",Map.of("runtimeId",r.id(),"exception",e.toString())); }
        }
        session.close();
    }
}
