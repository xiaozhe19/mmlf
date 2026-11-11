package dev.mmlf.coordinator;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import static dev.mmlf.common.Messages.bulkId;
import static dev.mmlf.common.Messages.isBulkId;
import java.util.*;
import java.util.concurrent.*;

/**
 * Session router independent of any transport; the runtime that owns an object controls its state. Front-ends
 * ({@link InJvmPort}) hand it text frames per {@link Peer}.
 */
public final class CoordinatorCore {
    /** One connected runtime as seen by the router. Sends must not call back into the router on the caller's stack. */
    public interface Peer {
        void send(String frame);
        void close(int code,String reason);
    }
    private final String token;
    private final Object stateLock = new Object();
    private final Map<Peer,String> identities = new ConcurrentHashMap<>();
    private final Map<String,Peer> peers = new ConcurrentHashMap<>();
    private final Set<Peer> connections = ConcurrentHashMap.newKeySet();
    private final Map<String,String> incarnations = new ConcurrentHashMap<>();
    private final WorldIndex world = new WorldIndex();
    /** Entity ids each runtime last reported owning; entity hits and damage reports must name one of the sender's. */
    private final Map<String,Set<String>> entities = new ConcurrentHashMap<>();
    private volatile String primary;
    private volatile boolean primarySeen;
    private long metricStart=System.nanoTime(),metricMessages;
    private final Map<String,Pending> pending=new HashMap<>();
    private final Map<String,Long> recentActions=new HashMap<>();
    private static final class Pending {
        final RayRequest request; final Set<String> expected; final Map<String,RayHit> hits=new HashMap<>();
        Pending(RayRequest request,Set<String> expected) { this.request=request; this.expected=expected; }
    }
    public CoordinatorCore(String token) { this.token=Objects.requireNonNull(token); }
    public boolean hasPrimary() { return primary != null; }
    public boolean hadPrimary() { return primarySeen; }
    /** Registered runtime ids. */
    public Set<String> runtimes() { return Set.copyOf(peers.keySet()); }

    public void opened(Peer c) { connections.add(c); }
    public void closed(Peer c) {
        synchronized (stateLock) {
            connections.remove(c);
            String id=identities.remove(c);
            if(id!=null && peers.remove(id,c)) {
                incarnations.remove(id);
                world.removeOwner(id);
                entities.remove(id);
                if(id.equals(primary)) primary=null;
                broadcast(Wire.of("runtime_left","coordinator",null,Map.of("runtime",id)).encode());
            }
        }
    }
    public void message(Peer c,String text) {
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
                if(!token.equals(h.token()) || !"1.21.1".equals(h.minecraft()) || peers.containsKey(m.source()) || (h.primary() && primary!=null)
                    || h.incarnation()==null || !h.incarnation().matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("registration rejected");
                id=m.source(); MmlfLog.info("coordinator","runtime_registered",Map.of("runtimeId",id,"loader",h.loader(),"primary",h.primary(),"incarnation",h.incarnation()));identities.put(c,id); peers.put(id,c); incarnations.put(id,h.incarnation()); if(h.primary()) { primary=id; primarySeen=true; }
                c.send(Wire.of("welcome","coordinator",id,new Welcome(peers.size()-1,primary,Set.copyOf(peers.keySet()),Map.copyOf(incarnations))).encode());
                for(ObjectState state:world.snapshot()) c.send(Wire.of("object","coordinator",id,state).encode());
                for(ChunkBlocks blocks:world.blocksSnapshot()) c.send(Wire.of("blocks","coordinator",id,blocks).encode());
                broadcast(Wire.of("runtime_joined","coordinator",null,Map.of("runtime",id,"incarnation",h.incarnation())).encode());
                return;
            }
            if(!id.equals(m.source())) throw new IllegalArgumentException("spoofed source");
            // A reliable message names its sender's incarnation and exactly one receiver; the receiver checks its own.
            if(m.delivery()!=null && (!incarnations.get(id).equals(m.delivery().stream()) || m.target()==null || m.target().equals("*") || m.target().equals(id)))
                throw new IllegalArgumentException("invalid reliable delivery");
            switch(m.type()) {
                case "ping" -> c.send(Wire.of("pong","coordinator",id,new Ping(m.body(Ping.class).sent(),System.nanoTime())).encode());
                case "object" -> { ObjectState s=m.body(ObjectState.class); world.update(id,s); MmlfLog.debug("world","object_update",Map.of("owner",id,"objectId",s.id(),"revision",s.revision(),"type",s.type()));relay(c,m); }
                case "blocks" -> { world.updateBlocks(id,m.body(ChunkBlocks.class)); relay(c,m); }
                case "ray_query" -> beginRay(id,m);
                case "ray_hit" -> collectHit(id,m);
                case "event" -> relay(c,m);
                case "signals", "neighbors", "transfer" -> { if(m.delivery()==null) throw new IllegalArgumentException(m.type()+" must be reliable"); relay(c,m); }
                case "ack" -> { if(m.target()==null || m.target().equals("*")) throw new IllegalArgumentException("ack needs a target"); relay(c,m); }
                case "player" -> { if(!id.equals(primary)) throw new IllegalArgumentException("only primary owns the player"); relay(c,m); }
                case "interaction_result", "gui" -> { toPrimary(id,m); MmlfLog.debug("gui","relayed",Map.of("type",m.type(),"source",id)); }
                case "entities" -> {
                    Entities snapshot=m.body(Entities.class);
                    if(snapshot.owner()==null || !snapshot.owner().equals(id) || snapshot.entities()==null) throw new IllegalArgumentException("entities must be the sender's own");
                    Set<String> ids=new HashSet<>(); for(EntityState e:snapshot.entities()) if(e!=null && e.id()!=null) ids.add(e.id());
                    entities.put(id,Set.copyOf(ids));
                    relay(c,m);
                }
                case "player_damage" -> {
                    PlayerDamage damage=m.body(PlayerDamage.class);
                    if(!ownsEntity(id,damage.attacker()) || !(damage.amount()>0) || damage.amount()>1000) throw new IllegalArgumentException("damage must come from the sender's own entity");
                    toPrimary(id,m);
                }
                case "player_xp" -> {
                    int amount=m.body(PlayerExperience.class).amount();
                    if(amount<=0 || amount>100_000) throw new IllegalArgumentException("invalid experience");
                    toPrimary(id,m);
                }
                case "gui_input" -> fromPrimary(id,m);
                case "inventory" -> { if(id.equals(primary)) fromPrimary(id,m); else toPrimary(id,m); }
                default -> throw new IllegalArgumentException("unknown message type");
            }
        } catch(RuntimeException e) {
            MmlfLog.warn("coordinator","request_rejected",Map.of("exception",e.getClass().getSimpleName(),"reason",String.valueOf(e.getMessage())));
            c.send(Wire.of("error","coordinator",null,Map.of("message",String.valueOf(e.getMessage()))).encode());
            if (!identities.containsKey(c)) c.close(1008,"registration rejected");
        }
        }
    }
    private boolean ownsEntity(String runtime,String entity) { return entity!=null && entities.getOrDefault(runtime,Set.of()).contains(entity); }
    /** Every open connection, registered or not. */
    private void broadcast(String frame) { for(Peer p:connections) p.send(frame); }
    private void beginRay(String source,Wire m) {
        if (!source.equals(primary)) throw new IllegalArgumentException("only primary can initiate interaction");
        RayRequest request=m.body(RayRequest.class);
        if(request.actionId()==null || request.pose()==null || request.reach()<=0 || request.reach()>6 || pending.containsKey(request.actionId()))
            throw new IllegalArgumentException("invalid ray request");
        long now=System.nanoTime();
        recentActions.values().removeIf(t->now-t>TimeUnit.MINUTES.toNanos(1));
        if(recentActions.putIfAbsent(request.actionId(),now)!=null) throw new IllegalArgumentException("duplicate action");
        Pending p=new Pending(request,Set.copyOf(peers.keySet())); pending.put(request.actionId(),p);
        Wire query=Wire.of("ray_request","coordinator","*",request);
        for(String id:p.expected) peers.get(id).send(query.encode());
        CompletableFuture.delayedExecutor(1500,TimeUnit.MILLISECONDS).execute(()-> {
            synchronized(stateLock) { if(pending.remove(request.actionId(),p)) {
                Peer sender=peers.get(source);
                if(sender!=null) sender.send(Wire.of("error","coordinator",source,Map.of("message","ray query timed out")).encode());
            }}
        });
    }
    private void collectHit(String source,Wire m) {
        RayHit hit=m.body(RayHit.class); Pending p=pending.get(hit.actionId());
        if(p==null || !p.expected.contains(source) || p.hits.containsKey(source)) throw new IllegalArgumentException("unexpected ray result");
        // The primary's own entities are its world's; any other runtime may only answer with an entity it reported.
        if(hit.entityHit() && !hit.miss() && !source.equals(primary) && !ownsEntity(source,hit.entity())) {
            MmlfLog.warn("interaction","unowned_entity_hit",Map.of("actionId",hit.actionId(),"runtime",source,"entity",hit.entity()));
            hit=new RayHit(hit.actionId(),null,0,0,0,0,0,"UP",0,0,0,true);
        }
        if(!hit.miss() && (!Double.isFinite(hit.distance()) || hit.distance()<0 || hit.distance()>p.request.reach()))
            throw new IllegalArgumentException("invalid hit distance");
        if(!hit.miss() && hit.objectId()!=null) {
            boolean owned=isBulkId(hit.objectId())
                ? hit.objectId().equals(bulkId(source)) && world.ownsBlock(source,p.request.pose().dimension(),hit.x(),hit.y(),hit.z())
                : world.valid(source,hit);
            if(!owned) throw new IllegalArgumentException("unowned or stale target");
        }
        p.hits.put(source,hit);
        if(p.hits.size()!=p.expected.size()) return;
        pending.remove(hit.actionId());
        Map.Entry<String,RayHit> best=p.hits.entrySet().stream().filter(e->!e.getValue().miss()).min(Comparator.comparingDouble(e->e.getValue().distance())).orElse(null);
        if(best==null && ("use".equals(p.request.action()) || p.request.action()==null)) {
            // Nothing in reach: the held item is used into the air (food, potions, projectiles, buckets on deep water) by
            // the runtime that owns it, as vanilla does when its own ray misses.
            var remoteItem=RemoteItemRef.parse(p.request.heldItem());
            String destination=remoteItem.map(RemoteItemRef::owner).orElse(primary);
            Peer target=peers.get(destination); RayHit miss=p.hits.get(destination);
            if(target==null || miss==null) throw new IllegalArgumentException("item runtime offline");
            MmlfLog.info("interaction","routed",Map.of("actionId",hit.actionId(),"owner",destination,"hitOwner","none","action","use_air","distance",0));
            target.send(Wire.of("interaction","coordinator",destination,new Interaction(hit.actionId(),p.request.pose(),miss,p.request.action(),p.request.heldItem(),p.request.secondaryUse(),p.request.hand(),p.request.selectedSlot())).encode());
            return;
        }
        if(best==null) {MmlfLog.debug("interaction","no_target",Map.of("actionId",hit.actionId(),"action",String.valueOf(p.request.action()),"cameraId",p.request.pose().cameraId()));return;}
        if(best.getValue().objectId()==null && !best.getValue().entityHit() && !best.getKey().equals(primary)) {MmlfLog.debug("interaction","unindexed_target",Map.of("actionId",hit.actionId(),"hitOwner",best.getKey(),"position",best.getValue().x()+","+best.getValue().y()+","+best.getValue().z()));return;}
        String destination=best.getKey(); RayHit routedHit=best.getValue();
        var remoteItem=RemoteItemRef.parse(p.request.heldItem());
        if("use".equals(p.request.action()) && remoteItem.isPresent()) {
            String owner=remoteItem.get().owner();
            if(!peers.containsKey(owner)) throw new IllegalArgumentException("item runtime offline");
            if(!owner.equals(destination)) {
                if(routedHit.objectId()!=null || routedHit.entityHit()) throw new IllegalArgumentException("cross-owner mod-item interaction unsupported");
                destination=owner;
            }
        }
        Peer target=peers.get(destination);
        MmlfLog.info("interaction","routed",Map.of("actionId",hit.actionId(),"owner",destination,"hitOwner",best.getKey(),"action",p.request.action()==null?"use":p.request.action(),"distance",best.getValue().distance()));
        if(target!=null) target.send(Wire.of("interaction","coordinator",destination,new Interaction(hit.actionId(),p.request.pose(),routedHit,p.request.action(),p.request.heldItem(),p.request.secondaryUse(),p.request.hand(),p.request.selectedSlot())).encode());
    }
    /** Owner reports about hosted screens and interactions go to the primary only. */
    private void toPrimary(String source,Wire m) {
        if(primary==null || source.equals(primary) || !primary.equals(m.target())) throw new IllegalArgumentException("report must target the primary");
        Peer dest=peers.get(primary); if(dest!=null) dest.send(m.encode());
    }
    /** Screen input and inventory grants come only from the primary and go to one online owner. */
    private void fromPrimary(String source,Wire m) {
        if(!source.equals(primary)) throw new IllegalArgumentException("only primary can control hosted screens");
        if(m.target()==null || m.target().equals(source)) throw new IllegalArgumentException("invalid target");
        if(m.target().equals("*")) { peers.forEach((id,p)->{ if(!id.equals(source)) p.send(m.encode()); }); return; }
        Peer dest=peers.get(m.target());
        if(dest==null) throw new IllegalArgumentException("target runtime offline");
        dest.send(m.encode());
    }
    private void relay(Peer sender, Wire m) {
        if(m.target()==null || m.target().equals("*")) peers.values().stream().filter(p->p!=sender).forEach(p->p.send(m.encode()));
        else { Peer dest=peers.get(m.target()); if(dest!=null && dest!=sender) dest.send(m.encode()); }
    }
}
