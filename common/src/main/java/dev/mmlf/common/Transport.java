package dev.mmlf.common;

/**
 * The channel between one runtime and the Coordinator: text frames only, so the protocol above it ({@link Wire},
 * {@link ReliableLink}) is the same whether the Coordinator is in another process or in this JVM.
 */
public interface Transport {
    interface Listener {
        void opened();
        void text(String frame);
        void closed(int code,boolean remote);
    }
    /** Starts connecting; the listener is told when the connection opens and when it ends. Called at most once. */
    void connect(Listener listener);
    boolean isOpen();
    /** True once this transport has ended (or failed to connect) and a new one must be created. */
    boolean isClosed();
    void send(String frame);
    void close();

    /** A fresh transport for the endpoint the session names: {@code ws://127.0.0.1:port} or {@code injvm://local}. */
    static Transport forSession(Session session) {
        return switch(java.net.URI.create(session.uri()).getScheme()) {
            case "ws" -> new WebSocketTransport(java.net.URI.create(session.uri()));
            case "injvm" -> new InJvmTransport(session.identity());
            default -> throw new IllegalArgumentException("unsupported MMLF endpoint "+session.uri());
        };
    }
}
