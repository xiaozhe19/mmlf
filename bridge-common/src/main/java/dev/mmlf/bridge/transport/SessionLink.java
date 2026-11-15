package dev.mmlf.bridge.transport;

import dev.mmlf.common.FrameTiming;
import dev.mmlf.common.Peers;
import dev.mmlf.common.RuntimeClient;
import dev.mmlf.common.Session;
import dev.mmlf.common.Wire;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/**
 * Transport layer: this runtime's place in its session. The session it joined, whether it is the primary, its link to
 * the Coordinator and the other runtimes online ({@link Peers}). Set once when the session is joined; nothing here
 * belongs to a world.
 * <p>
 * Messages sent while unlinked are dropped, as every bridge message; reliable ones are kept by the link
 * ({@link RuntimeClient#sendReliable}).
 */
public final class SessionLink {
    private SessionLink() {}
    private static Session session;
    /** {@link Session#identity()} hashes the token; read every frame, so kept. */
    private static String identity;
    private static boolean primary;
    public static RuntimeClient client;
    private static final Peers peers=new Peers(task->Minecraft.getInstance().execute(task));
    /** This runtime is stopping (its game is closing, or the session ended); set once. */
    private static volatile boolean stopping;
    public static boolean stopping() { return stopping; }
    /** Marks this runtime as stopping; true only for the first call. */
    public static synchronized boolean markStopping() { if(stopping) return false; stopping=true; return true; }

    /** Joins {@code session}; the returned link is not started yet. */
    public static RuntimeClient open(Session joined,String id,String loader,Consumer<Wire> receiver) {
        session=joined;
        identity=joined.identity();
        primary=id.equals(joined.primary());
        client=new RuntimeClient(joined,id,loader,primary,receiver);
        return client;
    }

    /** A session was joined in this run (the link may be down). */
    public static boolean joined() { return client!=null; }
    /** Linked to the Coordinator and registered with it. */
    public static boolean connected() { return client!=null && client.ready(); }
    public static Session session() { return session; }
    public static String identity() { return identity; }
    public static boolean isPrimary() { return primary; }
    public static String runtimeId() { return client==null?"unknown":client.id; }
    public static String primaryId() { return session==null?null:session.primary(); }
    public static Peers peers() { return peers; }
    public static RuntimeClient client() { return client; }
    public static FrameTiming.Clock calibration() { return client==null?null:client.calibration(); }

    /** A snapshot or request; dropped while the link is down. */
    public static void send(String type,String target,Object body) { if(client!=null) client.send(type,target,body); }
    /** Delivered exactly once and in order, also across a reconnect. */
    public static void sendReliable(String type,String target,Object body) { if(client!=null) client.sendReliable(type,target,body); }
    /** A report only the primary acts on, delivered once even across a reconnect. */
    public static void sendToPrimary(String type,Object body) { if(client!=null && !primary && session!=null) client.sendReliable(type,session.primary(),body); }
}
