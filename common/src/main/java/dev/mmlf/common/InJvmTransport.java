package dev.mmlf.common;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Connection to a Coordinator in this JVM but another class loader, through the port it published in
 * {@link InJvm#registry()}. Frames stay Strings across the boundary, so each side keeps its own protocol classes.
 * Inbound frames arrive on the port's delivery thread for this connection, never on the sender's stack.
 */
final class InJvmTransport implements Transport {
    private final String sessionIdentity;
    private volatile Consumer<String> outbound;
    private volatile boolean open, closed;
    private volatile Listener listener;
    InJvmTransport(String sessionIdentity) { this.sessionIdentity=sessionIdentity; }

    public void connect(Listener listener) {
        this.listener=listener;
        BiFunction<String,Consumer<String>,Consumer<String>> port=InJvm.port(sessionIdentity);
        if(port==null) { closed=true; listener.closed(1006,false); return; }
        try {
            outbound=port.apply(Thread.currentThread().getName(),this::inbound);
        } catch(RuntimeException e) {
            MmlfLog.warn("connection","injvm_connect_failed",Map.of("exception",e.getClass().getName()));
            closed=true; listener.closed(1006,false); return;
        }
        open=true;
        listener.opened();
    }
    private void inbound(String frame) {
        if(closed) return;
        if(frame==null) { end(1001,true); return; }
        listener.text(frame);
    }
    private synchronized void end(int code,boolean remote) {
        if(closed) return;
        open=false; closed=true;
        listener.closed(code,remote);
    }
    public boolean isOpen() { return open; }
    public boolean isClosed() { return closed; }
    public void send(String frame) { Consumer<String> out=outbound; if(open && out!=null) out.accept(frame); }
    public void close() {
        Consumer<String> out=outbound;
        if(open && out!=null) out.accept(null);
        end(1000,false);
    }
}
