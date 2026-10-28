package dev.mmlf.common;

import dev.mmlf.common.Messages.InventorySnapshot;

/**
 * Who may change the player inventory. Exactly one runtime is its authority: the primary, except while an owner hosts a
 * screen and holds a lease. During a lease the primary only accepts the leaseholder's snapshots, newest version first,
 * and remembers the last confirmed one; a lease ends by the owner returning the inventory or, if the owner fails, by
 * rolling back to that last confirmed snapshot. Nothing is merged, so an item never exists on both sides.
 */
public final class InventoryLease {
    public enum State { PRIMARY_OWNED, LEASED }
    public static final String GRANT="grant", UPDATE="update", RETURN="return", MIRROR="mirror";
    /** Outside a lease: slots an owner's own game changed (a pickup, a use), each with what it held before. */
    public static final String OWNER_CHANGE="owner_change";

    private State state=State.PRIMARY_OWNED;
    private String leaseId, holder;
    private long version;
    private InventorySnapshot confirmed;

    public synchronized State state() { return state; }
    public synchronized boolean leased() { return state==State.LEASED; }
    public synchronized String holder() { return holder; }
    public synchronized String leaseId() { return leaseId; }
    public synchronized InventorySnapshot confirmed() { return confirmed; }

    /** Hands the inventory to {@code owner}; {@code current} is what the primary holds now and the rollback point. */
    public synchronized boolean grant(String owner,String id,InventorySnapshot current) {
        if(state!=State.PRIMARY_OWNED || owner==null || id==null || current==null) return false;
        state=State.LEASED; holder=owner; leaseId=id; version=current.version(); confirmed=current;
        return true;
    }
    public enum Verdict { CONFIRMED, RETURNED, REJECTED }
    /** A snapshot reported by the leaseholder. A returned snapshot ends the lease; the caller applies it. */
    public synchronized Verdict accept(String source,InventorySnapshot snapshot) {
        if(state!=State.LEASED || snapshot==null || source==null || !source.equals(holder) || !source.equals(snapshot.authority())
            || !leaseId.equals(snapshot.leaseId()) || snapshot.slots()==null) return Verdict.REJECTED;
        boolean returning=RETURN.equals(snapshot.reason());
        if(snapshot.version()<=version && !(returning && snapshot.version()==version)) return Verdict.REJECTED;
        version=snapshot.version(); confirmed=snapshot;
        if(!returning) return Verdict.CONFIRMED;
        end(); return Verdict.RETURNED;
    }
    /** Ends a lease whose holder failed; returns the snapshot the primary must apply (the last confirmed), or null. */
    public synchronized InventorySnapshot rollback(String failedHolder) {
        if(state!=State.LEASED || (failedHolder!=null && !failedHolder.equals(holder))) return null;
        InventorySnapshot last=confirmed; end(); return last;
    }
    /** Version the primary should use for its next own snapshot, above every version seen. */
    public synchronized long nextVersion() { return ++version; }
    private void end() { state=State.PRIMARY_OWNED; holder=null; leaseId=null; }

    /** Owner side: numbers the snapshots of the lease it holds. */
    public static final class Holding {
        private String leaseId; private long version;
        public synchronized boolean active() { return leaseId!=null; }
        public synchronized String leaseId() { return leaseId; }
        public synchronized void begin(String id,long grantedVersion) { leaseId=id; version=grantedVersion; }
        public synchronized long next() { return ++version; }
        public synchronized void end() { leaseId=null; }
    }
}
