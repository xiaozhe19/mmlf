package dev.mmlf.coordinator;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** Loopback-only session router; the runtime that owns an object controls its state. */
public final class Coordinator extends WebSocketServer {
    private final String token;
    private final Object stateLock = new Object();
    private final Map<WebSocket,String> identities = new ConcurrentHashMap<>();
    private final Map<String,WebSocket> peers = new ConcurrentHashMap<>();
    private final WorldIndex world = new WorldIndex();
    private volatile String primary;
    private volatile boolean primarySeen;
    private long metricStart=System.nanoTime(),metricMessages;
    public boolean hasPrimary() { return primary != null; }
    public boolean hadPrimary() { return primarySeen; }
    private final Map<String,Pending> pending=new HashMap<>();
    private final Map<String,Long> recentActions=new HashMap<>();
    private static final class Pending {
        final RayRequest request; final Set<String> expected; final Map<String,RayHit> hits=new HashMap<>();
        Pending(RayRequest request,Set<String> expected) { this.request=request; this.expected=expected; }
    }
    public Coordinator(int port, String token) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.token = Objects.requireNonNull(token);
        setConnectionLostTimeout(10);
    }
    public void onOpen(WebSocket c, ClientHandshake h) { }
    public void onClose(WebSocket c,int code,String reason,boolean remote) {
        synchronized (stateLock) {
        String id=identities.remove(c);
        if(id!=null && peers.remove(id,c)) {
            world.removeOwner(id);
            if(id.equals(primary)) primary=null;
            broadcast(Wire.of("runtime_left","coordinator",null,Map.of("runtime",id)).encode());
        }
    }
    }
    public void onMessage(WebSocket c,String text) {
        synchronized (stateLock) {
        try {
            Wire m=Wire.parse(text);
            metricMessages++;long metricNow=System.nanoTime();
            if(metricNow-metricStart>=5_000_000_000L) {MmlfLog.info("metrics","coordinator",Map.of("messagesPerSecond",metricMessages/((metricNow-metricStart)/1e9),"runtimes",peers.size(),"pendingActions",pending.size()));metricMessages=0;metricStart=metricNow;}
            MmlfLog.wire("coordinator_receive",m);
            String id=identities.get(c);
            if(id==null) {
                if(!m.type().equals("hello") || !m.source().matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("hello required");
                Hello h=m.body(Hello.class);
                if(!token.equals(h.token()) || !"1.21.1".equals(h.minecraft()) || peers.containsKey(m.source()) || (h.primary() && primary!=null)) throw new IllegalArgumentException("registration rejected");
                id=m.source(); MmlfLog.info("coordinator","runtime_registered",Map.of("runtimeId",id,"loader",h.loader(),"primary",h.primary()));identities.put(c,id); peers.put(id,c); if(h.primary()) { primary=id; primarySeen=true; }
                c.send(Wire.of("welcome","coordinator",id,new Welcome(peers.size()-1,primary,Set.copyOf(peers.keySet()))).encode());
                for(ObjectState state:world.snapshot()) c.send(Wire.of("object","coordinator",id,state).encode());
                broadcast(Wire.of("runtime_joined","coordinator",null,Map.of("runtime",id)).encode());
                return;
            }
            if(!id.equals(m.source())) throw new IllegalArgumentException("spoofed source");
            switch(m.type()) {
                case "ping" -> c.send(Wire.of("pong","coordinator",id,new Ping(m.body(Ping.class).sent(),System.nanoTime())).encode());
                case "object" -> { ObjectState s=m.body(ObjectState.class); world.update(id,s); MmlfLog.debug("world","object_update",Map.of("owner",id,"objectId",s.id(),"revision",s.revision(),"type",s.type()));relay(c,m); }
                case "ray_query" -> beginRay(id,m);
                case "ray_hit" -> collectHit(id,m);
                case "event", "pose", "frame" -> relay(c,m);
                default -> throw new IllegalArgumentException("unknown message type");
            }
        } catch(RuntimeException e) {
            MmlfLog.warn("coordinator","request_rejected",Map.of("exception",e.getClass().getSimpleName(),"reason",String.valueOf(e.getMessage())));
            c.send(Wire.of("error","coordinator",null,Map.of("message",String.valueOf(e.getMessage()))).encode());
            if (!identities.containsKey(c)) c.close(1008,"registration rejected");
        }
    }
    }
    private void beginRay(String source,Wire m) {
        if (!source.equals(primary)) throw new IllegalArgumentException("only primary can initiate interaction");
        RayRequest request=m.body(RayRequest.class);
        if(request.actionId()==null || request.pose()==null || request.reach()<=0 || request.reach()>6 || pending.containsKey(request.actionId()))
            throw new IllegalArgumentException("invalid ray request");
        long now=System.nanoTime();
        recentActions.values().removeIf(t->now-t>java.util.concurrent.TimeUnit.MINUTES.toNanos(1));
        if(recentActions.putIfAbsent(request.actionId(),now)!=null) throw new IllegalArgumentException("duplicate action");
        Pending p=new Pending(request,Set.copyOf(peers.keySet())); pending.put(request.actionId(),p);
        Wire query=Wire.of("ray_request","coordinator","*",request);
        for(String id:p.expected) peers.get(id).send(query.encode());
        CompletableFuture.delayedExecutor(1500,java.util.concurrent.TimeUnit.MILLISECONDS).execute(()-> {
            synchronized(stateLock) { if(pending.remove(request.actionId(),p)) {
                WebSocket sender=peers.get(source);
                if(sender!=null) sender.send(Wire.of("error","coordinator",source,Map.of("message","ray query timed out")).encode());
            }}
        });
    }
    private void collectHit(String source,Wire m) {
        RayHit hit=m.body(RayHit.class); Pending p=pending.get(hit.actionId());
        if(p==null || !p.expected.contains(source) || p.hits.containsKey(source)) throw new IllegalArgumentException("unexpected ray result");
        if(!hit.miss() && (!Double.isFinite(hit.distance()) || hit.distance()<0 || hit.distance()>p.request.reach()))
            throw new IllegalArgumentException("invalid hit distance");
        if(!hit.miss() && hit.objectId()!=null && !world.valid(source,hit)) throw new IllegalArgumentException("unowned or stale target");
        p.hits.put(source,hit);
        if(p.hits.size()!=p.expected.size()) return;
        pending.remove(hit.actionId());
        Map.Entry<String,RayHit> best=p.hits.entrySet().stream().filter(e->!e.getValue().miss()).min(Comparator.comparingDouble(e->e.getValue().distance())).orElse(null);
        if(best==null) {MmlfLog.debug("interaction","no_target",Map.of("actionId",hit.actionId(),"action",String.valueOf(p.request.action()),"cameraId",p.request.pose().cameraId()));return;}
        if(best.getValue().objectId()==null && !best.getKey().equals(primary)) {MmlfLog.debug("interaction","unindexed_target",Map.of("actionId",hit.actionId(),"hitOwner",best.getKey(),"position",best.getValue().x()+","+best.getValue().y()+","+best.getValue().z()));return;}
        String destination=best.getKey(); RayHit routedHit=best.getValue();
        var remoteItem=RemoteItemRef.parse(p.request.heldItem());
        if("use".equals(p.request.action()) && remoteItem.isPresent()) {
            String owner=remoteItem.get().owner();
            if(!peers.containsKey(owner)) throw new IllegalArgumentException("item runtime offline");
            if(!owner.equals(destination)) {
                if(routedHit.objectId()!=null) throw new IllegalArgumentException("cross-owner mod-item interaction unsupported");
                destination=owner;
            }
        }
        WebSocket target=peers.get(destination);
        MmlfLog.info("interaction","routed",Map.of("actionId",hit.actionId(),"owner",destination,"hitOwner",best.getKey(),"action",p.request.action()==null?"use":p.request.action(),"distance",best.getValue().distance()));
        if(target!=null) target.send(Wire.of("interaction","coordinator",destination,new Interaction(hit.actionId(),p.request.pose(),routedHit,p.request.action(),p.request.heldItem(),p.request.secondaryUse())).encode());
    }
    private void relay(WebSocket sender, Wire m) {
        if(m.target()==null || m.target().equals("*")) peers.values().stream().filter(p->p!=sender).forEach(p->p.send(m.encode()));
        else { WebSocket dest=peers.get(m.target()); if(dest!=null && dest!=sender) dest.send(m.encode()); }
    }
    public void onError(WebSocket c,Exception e) { System.err.println("MMLF coordinator: "+e.getClass().getSimpleName()); }
    public void onStart() { System.out.println("MMLF coordinator listening on loopback port "+getPort()); }
}
