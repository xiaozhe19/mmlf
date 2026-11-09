package dev.mmlf.common;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static dev.mmlf.common.Messages.*;

public final class RuntimeClient implements AutoCloseable {
    public final String id;
    private final Session session;
    private final Hello hello;
    private final Consumer<Wire> receiver;
    private volatile Transport transport;
    private volatile boolean registered;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"mmlf-link"); t.setDaemon(true); return t; });
    public volatile double rttMillis;
    public volatile double coordinatorOffsetNanos;
    /** Eight seconds of 1 Hz pings: always inside the calibration lifetime while pongs keep arriving. */
    private final FrameTiming.ClockFilter clocks = new FrameTiming.ClockFilter(8);
    /** Survives reconnects: messages sent while the link is down go out once it is back. */
    private final ReliableLink reliable;
    public static final int RELIABLE_CAPACITY=Integer.getInteger("mmlf.reliableCapacity",1024);
    public static final long RELIABLE_RETENTION_NANOS=TimeUnit.SECONDS.toNanos(Long.getLong("mmlf.reliableRetentionSeconds",60));
    public FrameTiming.Clock calibration() { return clocks.best(System.nanoTime()); }
    public RuntimeClient(Session session, String id, String loader, boolean primary, Consumer<Wire> receiver) {
        this.session=session; this.id=id;
        try {MmlfLog.configure(id,session.root(),MmlfLog.configuredLevel());} catch(java.io.IOException e) {throw new IllegalStateException("Cannot initialize runtime log",e);} this.hello=new Hello(session.token(),loader,"1.21.1",primary); this.receiver=receiver;
        reliable=new ReliableLink(id,hello.incarnation(),RELIABLE_CAPACITY,RELIABLE_RETENTION_NANOS);
    }
    public void start() { clock.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS); }
    private void tick() {
        try {
            Transport t=transport;
            if (t == null || t.isClosed()) {
                registered=false;
                Transport next=Transport.forSession(session);
                transport=next;
                next.connect(new Transport.Listener() {
                    public void opened() { MmlfLog.info("connection","connected",Map.of("loader",hello.loader(),"primary",hello.primary())); next.send(Wire.of("hello",id,"coordinator",hello).encode()); }
                    public void text(String text) { handleText(text); }
                    public void closed(int c,boolean remote) { registered=false; clocks.clear(); MmlfLog.warn("connection","disconnected",Map.of("code",c,"remote",remote)); receiver.accept(Wire.of("disconnected","coordinator",id,Map.of())); }
                });
            } else if (registered) send("ping","coordinator",new Ping(System.nanoTime(),0));
        } catch (RuntimeException e) { MmlfLog.warn("connection","reconnect_failed",Map.of("exception",e.getClass().getName())); }
    }
    private void handleText(String text) {
        try {
            Wire w=Wire.parse(text); MmlfLog.wire("receive",w);
            if (w.type().equals("welcome")) {
                registered=true;MmlfLog.info("connection","registered",Map.of("protocol",w.version()));
                Welcome welcome=w.body(Welcome.class);
                if(welcome.incarnations()!=null) welcome.incarnations().forEach((peer,incarnation)->transmit(reliable.peerOnline(peer,incarnation,System.nanoTime())));
            }
            if (w.type().equals("runtime_joined") && w.data().has("incarnation"))
                transmit(reliable.peerOnline(w.data().get("runtime").getAsString(),w.data().get("incarnation").getAsString(),System.nanoTime()));
            if (w.type().equals("runtime_left")) reliable.peerOffline(w.data().get("runtime").getAsString());
            if (w.type().equals("ack")) { reliable.acknowledged(w.source(),w.body(ReliableLink.Ack.class)); return; }
            if (w.type().equals("pong")) {
                Ping p=w.body(Ping.class); long now=System.nanoTime();
                FrameTiming.Clock sample=FrameTiming.fromPing(p.sent(),p.remote(),now);
                FrameTiming.Clock best=clocks.add(sample);
                rttMillis=sample.uncertaintyNanos()*2/1e6; coordinatorOffsetNanos=best==null?sample.offsetNanos():best.offsetNanos();
            }
            ReliableLink.Inbound inbound=reliable.receive(w);
            if(inbound.ack()!=null) transmit(List.of(inbound.ack()));
            for(Wire delivered:inbound.deliver()) receiver.accept(delivered);
        } catch (RuntimeException e) {
            Throwable root=e; while(root.getCause()!=null && root.getCause()!=root) root=root.getCause();
            int start=text.indexOf("\"type\":\""), end=start<0?-1:text.indexOf('"',start+8);
            MmlfLog.warn("protocol","invalid_message",Map.of("exception",e.getClass().getSimpleName(),"message",String.valueOf(e.getMessage()),
                "cause",root==e?"":root.toString(),"type",end<0?"unknown":text.substring(start+8,end),"at",String.valueOf(root.getStackTrace().length>0?root.getStackTrace()[0]:"")));
        }
    }
    public boolean ready() { return registered; }
    /** A snapshot or request: dropped while the link is down, since a newer one or the requester's timeout replaces it. */
    public void send(String type,String target,Object body) {
        Transport t=transport;
        if (t!=null && t.isOpen() && registered) {Wire wire=Wire.of(type,id,target,body);MmlfLog.wire("send",wire);t.send(wire.encode());}
    }
    /**
     * A message that must arrive exactly once and in order, to one peer or ({@code "*"}) to each known peer. Kept until
     * acknowledged and sent again after a reconnect; dropped only past the bounds, which {@link ReliableLink} logs.
     */
    public void sendReliable(String type,String target,Object body) { transmit(reliable.send(type,target,body,System.nanoTime())); }
    private void transmit(List<Wire> wires) {
        Transport t=transport;
        if(t==null || !t.isOpen() || !registered) return;
        for(Wire wire:wires) { MmlfLog.wire("send",wire); t.send(wire.encode()); }
    }
    public void close() { clock.shutdownNow(); Transport t=transport; if(t!=null) t.close(); }
    /** Ends the current connection as if it had dropped; the next tick reconnects. For tests. */
    void dropConnection() { Transport t=transport; if(t!=null) t.close(); }
}
