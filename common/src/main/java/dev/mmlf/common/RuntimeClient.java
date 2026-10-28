package dev.mmlf.common;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static dev.mmlf.common.Messages.*;

public final class RuntimeClient implements AutoCloseable {
    public final String id;
    private final Session session;
    private final Hello hello;
    private final Consumer<Wire> receiver;
    private volatile WebSocketClient socket;
    private volatile boolean registered;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"mmlf-link"); t.setDaemon(true); return t; });
    public volatile double rttMillis;
    public volatile double coordinatorOffsetNanos;
    public RuntimeClient(Session session, String id, String loader, boolean primary, Consumer<Wire> receiver) {
        this.session=session; this.id=id;
        try {MmlfLog.configure(id,session.root(),MmlfLog.configuredLevel());} catch(java.io.IOException e) {throw new IllegalStateException("Cannot initialize runtime log",e);} this.hello=new Hello(session.token(),loader,"1.21.1",primary); this.receiver=receiver;
    }
    public void start() { clock.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS); }
    private void tick() {
        try {
            if (socket == null || socket.isClosed()) {
                registered=false;
                socket = new WebSocketClient(URI.create(session.uri())) {
                    public void onOpen(ServerHandshake h) { MmlfLog.info("connection","connected",Map.of("loader",hello.loader(),"primary",hello.primary())); send(Wire.of("hello",id,"coordinator",hello).encode()); }
                    public void onMessage(String text) {
                        try {
                            Wire w=Wire.parse(text); MmlfLog.wire("receive",w);
                            if (w.type().equals("welcome")) {registered=true;MmlfLog.info("connection","registered",Map.of("protocol",w.version()));}
                            if (w.type().equals("pong")) {
                                Ping p=w.body(Ping.class); long now=System.nanoTime();
                                rttMillis=(now-p.sent())/1e6; coordinatorOffsetNanos=p.remote()-(p.sent()/2.0+now/2.0);
                            }
                            receiver.accept(w);
                        } catch (RuntimeException e) { MmlfLog.warn("protocol","invalid_message",Map.of("exception",e.getClass().getSimpleName())); }
                    }
                    public void onClose(int c,String r,boolean remote) { registered=false; MmlfLog.warn("connection","disconnected",Map.of("code",c,"remote",remote)); receiver.accept(Wire.of("disconnected","coordinator",id,Map.of())); }
                    public void onError(Exception e) { MmlfLog.error("connection","socket_error",Map.of("exception",e.getClass().getName())); }
                };
                socket.setConnectionLostTimeout(5); socket.connect();
            } else if (registered) send("ping","coordinator",new Ping(System.nanoTime(),0));
        } catch (RuntimeException e) { MmlfLog.warn("connection","reconnect_failed",Map.of("exception",e.getClass().getName())); }
    }
    public boolean ready() { return registered; }
    public void send(String type,String target,Object body) {
        WebSocketClient s=socket;
        if (s!=null && s.isOpen() && registered) {Wire wire=Wire.of(type,id,target,body);MmlfLog.wire("send",wire);s.send(wire.encode());}
    }
    public void close() { clock.shutdownNow(); if(socket!=null) socket.close(); }
}
