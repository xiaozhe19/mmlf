package dev.mmlf.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * JVM-wide rendezvous for runtimes that share one JVM but not one class loader. Each loader has its own copy of this
 * class, so everything stored here is a JDK type: the registry itself, String-keyed maps and functional interfaces
 * carrying String frames. Minecraft, Gson or MMLF objects must never be stored or passed through it.
 *
 * <p>Ports: {@code BiFunction<String,Consumer<String>,Consumer<String>>}. {@code connect(name, inbound)} returns the
 * outbound sink; a {@code null} frame in either direction closes the connection.
 */
public final class InJvm {
    private static final String KEY="mmlf.injvm.registry";
    private InJvm() {}

    @SuppressWarnings("unchecked")
    public static ConcurrentMap<String,Object> registry() {
        synchronized(System.getProperties()) {
            Object value=System.getProperties().get(KEY);
            if(value==null) { value=new ConcurrentHashMap<String,Object>(); System.getProperties().put(KEY,value); }
            return (ConcurrentMap<String,Object>) value;
        }
    }
    public static String portKey(String sessionIdentity) { return "port:"+sessionIdentity; }
    /** Launch description for a runtime started inside this JVM, keyed by its loader (one runtime per loader). */
    public static String contextKey(String loader) { return "context:"+loader; }

    @SuppressWarnings("unchecked")
    public static BiFunction<String,Consumer<String>,Consumer<String>> port(String sessionIdentity) {
        Object value=registry().get(portKey(sessionIdentity));
        return value instanceof BiFunction<?,?,?> f ? (BiFunction<String,Consumer<String>,Consumer<String>>) f : null;
    }
    /** String-only context of a runtime the in-JVM backend started; null when this runtime was not started that way. */
    @SuppressWarnings("unchecked")
    public static Map<String,String> context(String loader) {
        Object value=registry().get(contextKey(loader));
        return value instanceof Map<?,?> m ? Map.copyOf((Map<String,String>) m) : null;
    }
}
