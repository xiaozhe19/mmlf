package dev.mmlf.common;

/**
 * The channel between one runtime and the Coordinator: text frames only, so the protocol above it ({@link Wire},
 * {@link ReliableLink}) stays independent of how frames cross between class loaders.
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

    /** A fresh transport to the session's in-JVM Coordinator. */
    static Transport forSession(Session session) {
        if(!session.inJvm()) throw new IllegalArgumentException("unsupported MMLF endpoint "+session.uri());
        return new InJvmTransport(session.identity());
    }
}
