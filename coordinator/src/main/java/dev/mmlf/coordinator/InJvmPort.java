package dev.mmlf.coordinator;

import dev.mmlf.common.InJvm;
import dev.mmlf.common.MmlfLog;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * In-JVM front-end of {@link CoordinatorCore}. Published in {@link InJvm#registry()} under the session identity, it
 * accepts connections from runtimes loaded by other class loaders. Only String frames cross: each side parses them
 * with its own copy of the protocol classes, so no object of one runtime reaches the other. Each direction of a
 * connection is delivered on its own thread, never on the sender's stack, so a send under the router's lock cannot
 * re-enter it and per-connection order is kept.
 */
public final class InJvmPort implements AutoCloseable {
    private final CoordinatorCore core;
    private final String key;
    private final java.util.Set<Connection> connections=ConcurrentHashMap.newKeySet();
    private final BiFunction<String,Consumer<String>,Consumer<String>> port=this::connect;
    private volatile boolean closed;

    public InJvmPort(CoordinatorCore core,String sessionIdentity) {
        this.core=Objects.requireNonNull(core);
        key=InJvm.portKey(sessionIdentity);
        if(InJvm.registry().putIfAbsent(key,port)!=null) throw new IllegalStateException("an in-JVM coordinator already serves this session");
    }

    private Consumer<String> connect(String name,Consumer<String> inbound) {
        if(closed) throw new IllegalStateException("coordinator closed");
        Connection c=new Connection(name,Objects.requireNonNull(inbound));
        connections.add(c);
        core.opened(c);
        MmlfLog.info("coordinator","injvm_connected",Map.of("connection",name));
        return c::fromClient;
    }

    /** One client connection; {@code toClient} and {@code toCore} are single threads keeping each direction ordered. */
    private final class Connection implements CoordinatorCore.Peer {
        final String name; final Consumer<String> inbound;
        final ExecutorService toClient, toCore;
        volatile boolean open=true;
        Connection(String name,Consumer<String> inbound) {
            this.name=name; this.inbound=inbound;
            toClient=Executors.newSingleThreadExecutor(r->daemon(r,"mmlf-injvm-out-"+name));
            toCore=Executors.newSingleThreadExecutor(r->daemon(r,"mmlf-injvm-in-"+name));
        }
        void fromClient(String frame) {
            if(!open) return;
            if(frame==null) { toCore.execute(()->shutdown(false)); return; }
            try { toCore.execute(()->{ if(open) core.message(this,frame); }); } catch(RejectedExecutionException ignored) { }
        }
        public void send(String frame) {
            if(!open) return;
            try { toClient.execute(()->deliver(frame)); } catch(RejectedExecutionException ignored) { }
        }
        public void close(int code,String reason) { toCore.execute(()->shutdown(true)); }
        private void deliver(String frame) {
            try { inbound.accept(frame); }
            catch(RuntimeException e) { MmlfLog.warn("coordinator","injvm_delivery_failed",Map.of("connection",name,"exception",e.getClass().getName())); }
        }
        /** Runs on {@code toCore}; tells the client when the coordinator is the side that closes. */
        void shutdown(boolean notifyClient) {
            if(!open) return;
            open=false;
            connections.remove(this);
            core.closed(this);
            if(notifyClient) toClient.execute(()->deliver(null));
            toClient.shutdown(); toCore.shutdown();
            MmlfLog.info("coordinator","injvm_disconnected",Map.of("connection",name));
        }
    }
    private static Thread daemon(Runnable r,String name) { Thread t=new Thread(r,name); t.setDaemon(true); return t; }

    public int connections() { return connections.size(); }
    /** Ends every connection from the coordinator's side, as a lost link would; clients reconnect. */
    public void dropConnections() { for(Connection c:connections) c.close(1001,"dropped"); }

    @Override public void close() {
        if(closed) return;
        closed=true;
        InJvm.registry().remove(key,port);
        for(Connection c:connections) c.close(1001,"coordinator closed");
    }
}
