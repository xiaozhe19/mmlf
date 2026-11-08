package dev.mmlf.common;

import dev.mmlf.common.Messages.SignalState;
import java.util.*;

/**
 * Redstone signals across runtimes, without either loader's types. The sending side ({@link Outbox}) remembers what it
 * last published per boundary position and yields only changes; the receiving side ({@link Inbox}) holds what each
 * other runtime published and answers what a position gives a neighbour. Both are confined to one thread (a runtime's
 * server thread) by their callers.
 */
public final class SignalTable {
    private SignalTable() {}

    /** Sender: the signals last published, by location. */
    public static final class Outbox {
        private final Map<String,SignalState> sent=new HashMap<>();
        /**
         * Compares this tick's boundary with what was published and returns the changes: changed or new signals, and
         * silent entries for positions that left the boundary or went quiet. Positions never published and silent now
         * produce nothing.
         */
        public List<SignalState> diff(Collection<SignalState> boundary) {
            List<SignalState> changes=new ArrayList<>();
            Set<String> present=new HashSet<>();
            for(SignalState now:boundary) {
                present.add(now.location());
                SignalState before=sent.get(now.location());
                if(before==null ? now.silent() : same(before,now)) continue;
                changes.add(now);
                if(now.silent()) sent.remove(now.location()); else sent.put(now.location(),now);
            }
            for(var it=sent.entrySet().iterator();it.hasNext();) {
                var entry=it.next();
                if(present.contains(entry.getKey())) continue;
                SignalState gone=entry.getValue();
                changes.add(new SignalState(gone.dimension(),gone.x(),gone.y(),gone.z(),new int[6],new int[6],-1));
                it.remove();
            }
            return changes;
        }
        /** A peer joined again and has nothing: everything is published anew on the next diff. */
        public void reset() { sent.clear(); }
        public int size() { return sent.size(); }
    }

    /** Receiver: what each other runtime's positions give their neighbours. */
    public static final class Inbox {
        private final Map<String,Map<String,SignalState>> byOwner=new HashMap<>();
        /** Applies one change from {@code owner}; returns whether what the position gives changed. */
        public boolean apply(String owner,SignalState change) {
            var signals=byOwner.computeIfAbsent(owner,o->new HashMap<>());
            SignalState before=change.silent() ? signals.remove(change.location()) : signals.put(change.location(),change);
            if(before==null) return !change.silent();
            return !same(before,change);
        }
        /** The signal the position gives a neighbour asking from {@code direction}; the strongest if several runtimes publish it. */
        public int signal(String dimension,int x,int y,int z,int direction,boolean strong) {
            if(byOwner.isEmpty()) return 0;
            String location=dimension+":"+x+":"+y+":"+z; int best=0;
            for(var signals:byOwner.values()) {
                SignalState s=signals.get(location);
                if(s!=null) best=Math.max(best,(strong?s.strong():s.weak())[direction]);
            }
            return best;
        }
        /** What a comparator reads from the position, or -1 when no runtime publishes an analog output there. */
        public int analog(String dimension,int x,int y,int z) {
            if(byOwner.isEmpty()) return -1;
            String location=dimension+":"+x+":"+y+":"+z; int best=-1;
            for(var signals:byOwner.values()) { SignalState s=signals.get(location); if(s!=null) best=Math.max(best,s.analog()); }
            return best;
        }
        public boolean isEmpty() { return byOwner.values().stream().allMatch(Map::isEmpty); }
        /** Forgets a runtime that left; returns the positions it gave signals at, whose neighbours must update. */
        public List<SignalState> forget(String owner) {
            var signals=byOwner.remove(owner);
            return signals==null ? List.of() : List.copyOf(signals.values());
        }
        public List<SignalState> forgetAll() {
            List<SignalState> all=new ArrayList<>(); byOwner.values().forEach(m->all.addAll(m.values())); byOwner.clear(); return all;
        }
    }

    private static boolean same(SignalState a,SignalState b) { return Arrays.equals(a.weak(),b.weak()) && Arrays.equals(a.strong(),b.strong()) && a.analog()==b.analog(); }

    /** Neighbour states, sender side: what changed since the last publication, and positions that left the boundary. */
    public static final class NeighborOutbox {
        private final Map<String,Messages.NeighborState> sent=new HashMap<>();
        /** A position no longer published goes out once with a null state: the receiver drops it and sees its own block again. */
        public List<Messages.NeighborState> diff(Collection<Messages.NeighborState> boundary) {
            List<Messages.NeighborState> changes=new ArrayList<>(); Set<String> present=new HashSet<>();
            for(var now:boundary) {
                present.add(now.location());
                var before=sent.put(now.location(),now);
                if(before==null || !before.state().equals(now.state())) changes.add(now);
            }
            for(var it=sent.entrySet().iterator();it.hasNext();) {
                var entry=it.next(); if(present.contains(entry.getKey())) continue;
                var gone=entry.getValue(); changes.add(new Messages.NeighborState(gone.dimension(),gone.x(),gone.y(),gone.z(),null)); it.remove();
            }
            return changes;
        }
        public void reset() { sent.clear(); }
    }
}
