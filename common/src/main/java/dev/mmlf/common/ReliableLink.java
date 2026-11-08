package dev.mmlf.common;

import java.util.*;

/**
 * Exactly-once, in-order delivery of runtime-to-runtime messages across reconnects. Each process has an incarnation;
 * a stream runs from one sender incarnation to one receiver incarnation and numbers its messages from 1. The sender
 * keeps every message until the receiver acknowledges it and sends the unacknowledged ones again whenever the peer
 * (re)joins; the receiver hands each sequence number on once, in order, and acknowledges cumulatively. Messages meant
 * for an earlier incarnation of a peer are dropped, never delivered to the process that replaced it.
 *
 * Bounded: a sender keeps at most {@code capacity} messages per peer for at most {@code retentionNanos}; a receiver
 * holds at most {@code capacity} out-of-order messages per stream. Whatever is dropped is logged and, on the receiving
 * side, reported as a gap, so a loss is never silent. Not thread safe on its own: every method is synchronized.
 */
public final class ReliableLink {
    /** Delivery header of a reliable message. {@code floor} is the oldest sequence the sender can still send. */
    public record Delivery(String stream, String to, long seq, long floor) {}
    public record Ack(String stream, long seq) {}
    /** What a received message yields: the messages to hand on, in order, and the acknowledgement to send (or null). */
    public record Inbound(List<Wire> deliver, Wire ack) {}

    private record Pending(long seq, Wire wire, long createdNanos) {}
    private static final class Outbound {
        final String incarnation; long nextSeq=1; final ArrayDeque<Pending> pending=new ArrayDeque<>();
        Outbound(String incarnation) { this.incarnation=incarnation; }
        long floor() { return pending.isEmpty() ? nextSeq : pending.peekFirst().seq(); }
    }
    private static final class InboundStream {
        long delivered; final TreeMap<Long,Wire> held=new TreeMap<>();
    }

    private final String self, incarnation;
    private final int capacity;
    private final long retentionNanos;
    private final Map<String,Outbound> outbound=new HashMap<>();
    private final Map<String,String> known=new HashMap<>();
    private final Set<String> online=new HashSet<>();
    private final Map<String,InboundStream> inbound=new HashMap<>();
    private long lost, duplicates;

    public ReliableLink(String self,String incarnation,int capacity,long retentionNanos) {
        if(capacity<1 || retentionNanos<=0) throw new IllegalArgumentException("invalid bounds");
        this.self=Objects.requireNonNull(self); this.incarnation=Objects.requireNonNull(incarnation);
        this.capacity=capacity; this.retentionNanos=retentionNanos;
    }
    public String incarnation() { return incarnation; }

    /**
     * A peer is registered with this incarnation. Returns the messages to send it now: everything still unacknowledged.
     * A new incarnation replaces the old one, whose unacknowledged messages are dropped and logged.
     */
    public synchronized List<Wire> peerOnline(String runtime,String peerIncarnation,long now) {
        if(runtime==null || runtime.equals(self) || peerIncarnation==null) return List.of();
        online.add(runtime);
        String previous=known.put(runtime,peerIncarnation);
        Outbound out=outbound.get(runtime);
        if(previous!=null && !previous.equals(peerIncarnation)) {
            inbound.remove(previous);
            if(out!=null && !out.pending.isEmpty()) {
                lost+=out.pending.size();
                MmlfLog.warn("protocol","reliable_dropped_restarted_peer",Map.of("peer",runtime,"messages",out.pending.size()));
            }
            outbound.remove(runtime); return List.of();
        }
        if(out==null) return List.of();
        expire(runtime,out,now);
        // Resent with the current floor, so a receiver waiting for an expired message learns it will not come.
        long floor=out.floor();
        return out.pending.stream().map(p->p.wire().withDelivery(new Delivery(incarnation,out.incarnation,p.seq(),floor))).toList();
    }
    /** A peer left; what it has not acknowledged is kept for its return. */
    public synchronized void peerOffline(String runtime) { online.remove(runtime); }
    public synchronized boolean isOnline(String runtime) { return online.contains(runtime); }

    /**
     * Numbers a message for {@code target}, or for every known peer when the target is {@code "*"}. Returns the wires to
     * transmit now; they are kept until acknowledged whether or not they can be transmitted.
     */
    public synchronized List<Wire> send(String type,String target,Object body,long now) {
        if(target==null || target.equals(self) || target.equals("coordinator")) throw new IllegalArgumentException("reliable target must be a peer");
        List<String> targets=target.equals("*") ? known.keySet().stream().sorted().toList() : List.of(target);
        List<Wire> out=new ArrayList<>(targets.size());
        for(String peer:targets) {
            String peerIncarnation=known.get(peer);
            if(peerIncarnation==null) { lost++; MmlfLog.warn("protocol","reliable_unknown_peer",Map.of("peer",peer,"type",type)); continue; }
            Outbound stream=outbound.computeIfAbsent(peer,p->new Outbound(peerIncarnation));
            expire(peer,stream,now);
            if(stream.pending.size()>=capacity) {
                Pending dropped=stream.pending.removeFirst(); lost++;
                MmlfLog.error("protocol","reliable_outbox_full",Map.of("peer",peer,"droppedSeq",dropped.seq(),"type",dropped.wire().type()));
            }
            long seq=stream.nextSeq++;
            Wire base=Wire.of(type,self,peer,body);
            // The floor is that of the stream after this message is kept: everything below it is gone for good.
            long floor=stream.pending.isEmpty() ? seq : stream.pending.peekFirst().seq();
            Wire wire=base.withDelivery(new Delivery(incarnation,peerIncarnation,seq,floor));
            stream.pending.addLast(new Pending(seq,wire,now));
            if(online.contains(peer)) out.add(wire);
        }
        return out;
    }
    /** The peer {@code source} acknowledged everything up to {@code ack.seq()} of our stream to it. */
    public synchronized void acknowledged(String source,Ack ack) {
        Outbound stream=outbound.get(source);
        if(stream==null || ack==null || !incarnation.equals(ack.stream())) return;
        while(!stream.pending.isEmpty() && stream.pending.peekFirst().seq()<=ack.seq()) stream.pending.removeFirst();
    }

    /** Handles one received message; messages without a delivery header pass straight through. */
    public synchronized Inbound receive(Wire wire) {
        Delivery d=wire.delivery();
        if(d==null) return new Inbound(List.of(wire),null);
        if(!incarnation.equals(d.to()) || d.stream()==null || d.seq()<1) {
            MmlfLog.warn("protocol","reliable_misaddressed",Map.of("source",wire.source(),"type",wire.type()));
            return new Inbound(List.of(),null);
        }
        InboundStream stream=inbound.computeIfAbsent(d.stream(),s->new InboundStream());
        List<Wire> deliver=new ArrayList<>();
        if(d.seq()<=stream.delivered || stream.held.containsKey(d.seq())) duplicates++;
        else stream.held.put(d.seq(),wire);
        if(d.floor()>stream.delivered+1) {
            // The sender no longer has what we are missing below its floor: report it and move on.
            long missing=d.floor()-1-stream.delivered;
            stream.held.headMap(d.floor()).clear();
            lost+=missing; stream.delivered=d.floor()-1;
            MmlfLog.error("protocol","reliable_gap",Map.of("source",wire.source(),"missing",missing));
        }
        while(!stream.held.isEmpty() && stream.held.firstKey()==stream.delivered+1) {
            deliver.add(stream.held.pollFirstEntry().getValue()); stream.delivered++;
        }
        while(stream.held.size()>capacity) {
            var dropped=stream.held.pollLastEntry(); lost++;
            MmlfLog.error("protocol","reliable_reorder_full",Map.of("source",wire.source(),"droppedSeq",dropped.getKey()));
        }
        Wire ack=Wire.of("ack",self,wire.source(),new Ack(d.stream(),stream.delivered));
        return new Inbound(deliver,ack);
    }
    /** Messages known lost (dropped by bounds or a restarted peer) and duplicates discarded, since creation. */
    public synchronized long lost() { return lost; }
    public synchronized long duplicates() { return duplicates; }
    public synchronized int pending(String runtime) { Outbound o=outbound.get(runtime); return o==null?0:o.pending.size(); }

    private void expire(String peer,Outbound stream,long now) {
        int expired=0;
        while(!stream.pending.isEmpty() && now-stream.pending.peekFirst().createdNanos()>retentionNanos) { stream.pending.removeFirst(); expired++; }
        if(expired>0) { lost+=expired; MmlfLog.error("protocol","reliable_expired",Map.of("peer",peer,"messages",expired)); }
    }
}
