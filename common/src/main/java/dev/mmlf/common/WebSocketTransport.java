package dev.mmlf.common;

import java.net.URI;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/** Loopback WebSocket to a Coordinator in another process. */
final class WebSocketTransport implements Transport {
    private final URI uri;
    private volatile WebSocketClient socket;
    WebSocketTransport(URI uri) { this.uri=uri; }
    public void connect(Listener listener) {
        socket=new WebSocketClient(uri) {
            public void onOpen(ServerHandshake h) { listener.opened(); }
            public void onMessage(String text) { listener.text(text); }
            public void onClose(int code,String reason,boolean remote) { listener.closed(code,remote); }
            public void onError(Exception e) { MmlfLog.error("connection","socket_error",java.util.Map.of("exception",e.getClass().getName())); }
        };
        socket.setConnectionLostTimeout(5);
        socket.connect();
    }
    public boolean isOpen() { WebSocketClient s=socket; return s!=null && s.isOpen(); }
    public boolean isClosed() { WebSocketClient s=socket; return s==null || s.isClosed(); }
    public void send(String frame) { WebSocketClient s=socket; if(s!=null && s.isOpen()) s.send(frame); }
    public void close() { WebSocketClient s=socket; if(s!=null) s.close(); }
}
