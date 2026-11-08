package dev.mmlf.coordinator;

import java.net.InetSocketAddress;
import java.util.Objects;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * Loopback WebSocket front-end of {@link CoordinatorCore}: the transport for runtimes in other processes. Runtimes in
 * the same JVM reach the same core through {@link InJvmPort}.
 */
public final class Coordinator extends WebSocketServer {
    private final CoordinatorCore core;
    public Coordinator(int port, String token) { this(port,new CoordinatorCore(token)); }
    public Coordinator(int port, CoordinatorCore core) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.core=Objects.requireNonNull(core);
        setConnectionLostTimeout(10);
    }
    public CoordinatorCore core() { return core; }
    public boolean hasPrimary() { return core.hasPrimary(); }
    public boolean hadPrimary() { return core.hadPrimary(); }

    private record SocketPeer(WebSocket socket) implements CoordinatorCore.Peer {
        public void send(String frame) { if(socket.isOpen()) socket.send(frame); }
        public void close(int code,String reason) { socket.close(code,reason); }
    }
    private static CoordinatorCore.Peer peer(WebSocket c) { return c.<CoordinatorCore.Peer>getAttachment(); }
    public void onOpen(WebSocket c, ClientHandshake h) { SocketPeer p=new SocketPeer(c); c.setAttachment(p); core.opened(p); }
    public void onClose(WebSocket c,int code,String reason,boolean remote) { CoordinatorCore.Peer p=peer(c); if(p!=null) core.closed(p); }
    public void onMessage(WebSocket c,String text) { CoordinatorCore.Peer p=peer(c); if(p!=null) core.message(p,text); }
    public void onError(WebSocket c,Exception e) { System.err.println("MMLF coordinator: "+e.getClass().getSimpleName()); }
    public void onStart() { System.out.println("MMLF coordinator listening on loopback port "+getPort()); }
}
