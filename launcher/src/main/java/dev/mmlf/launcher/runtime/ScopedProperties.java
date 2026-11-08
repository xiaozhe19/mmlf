package dev.mmlf.launcher.runtime;

import dev.mmlf.common.MmlfLog;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * System properties seen per runtime. Installed once as the JVM's {@link System#getProperties()}, it keeps the host's
 * properties as the base and gives each runtime started in this JVM an overlay: its own launch's values, and the
 * host-only keys it must not inherit marked absent. A thread belongs to a runtime when it runs in that runtime's thread
 * group (threads inherit their creator's group) or, failing that, when its context class loader descends from one of
 * the runtime's class loaders. Reads and writes from a runtime's threads use its overlay; every other thread sees and
 * changes the host's properties only, so the host never sees a started runtime's values.
 *
 * <p>Limits: a thread neither in the group nor with a runtime loader as context (the JDK's common pool, virtual
 * threads) is treated as the host's; values the JDK caches at boot ({@code java.io.tmpdir}, {@code user.dir}) cannot be
 * scoped at all. Reads of overlaid keys from such ambiguous threads are logged once per key.
 */
public final class ScopedProperties extends Properties {
    /** Keys the JVM-wide rendezvous uses: always the base, whoever writes them. */
    private static final String SHARED_PREFIX="mmlf.injvm.";
    private static final Object ABSENT=new Object();

    /** One started runtime's view: its threads and the values it sees instead of the host's. */
    public static final class Scope {
        final String id; final ThreadGroup group; final List<ClassLoader> loaders=new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<String,Object> overlay=new ConcurrentHashMap<>();
        Scope(String id,ThreadGroup group) { this.id=id; this.group=group; }
        public String id() { return id; }
        public ThreadGroup group() { return group; }
        /** A class loader of this runtime; threads whose context loader descends from it belong here. */
        public void addLoader(ClassLoader loader) { if(loader!=null) loaders.add(loader); }
        /** Sets the runtime's own value, or (null) hides the host's value from it. */
        public void set(String key,String value) { overlay.put(key,value==null?ABSENT:value); }
        boolean owns(Thread t) {
            for(ThreadGroup g=t.getThreadGroup();g!=null;g=g.getParent()) if(g==group) return true;
            ClassLoader context=t.getContextClassLoader();
            for(int depth=0;context!=null && depth<16;context=context.getParent(),depth++) for(ClassLoader l:loaders) if(l==context) return true;
            return false;
        }
    }

    private final List<Scope> scopes=new CopyOnWriteArrayList<>();
    private final Set<String> ambiguousReported=ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> REPORTING=new ThreadLocal<>();

    private ScopedProperties(Properties base) {
        // putAll copies every entry, including non-String values such as the in-JVM registry.
        synchronized(base) { super.putAll(base); }
    }

    /** The JVM's scoped properties, installing them over the current ones the first time. */
    public static synchronized ScopedProperties install() {
        Properties current=System.getProperties();
        if(current instanceof ScopedProperties scoped) return scoped;
        // Another runtime's copy of this class may have installed its own; its scopes stay its own.
        if(current.getClass().getName().equals(ScopedProperties.class.getName())) throw new IllegalStateException("scoped properties installed by another class loader");
        ScopedProperties scoped=new ScopedProperties(current);
        System.setProperties(scoped);
        MmlfLog.info("launcher","scoped_properties_installed",Map.of("entries",scoped.size()));
        return scoped;
    }

    public Scope open(String id,ThreadGroup group) { Scope s=new Scope(id,group); scopes.add(s); return s; }
    public void close(Scope scope) { scopes.remove(scope); }
    public List<Scope> scopes() { return List.copyOf(scopes); }

    private Scope scope() {
        if(scopes.isEmpty()) return null;
        Thread t=Thread.currentThread();
        for(Scope s:scopes) if(s.owns(t)) return s;
        return null;
    }
    private static boolean shared(Object key) { return !(key instanceof String k) || k.startsWith(SHARED_PREFIX); }

    @Override public String getProperty(String key) {
        Scope s=scope();
        if(s!=null && !shared(key)) {
            Object v=s.overlay.get(key);
            if(v!=null) return v==ABSENT?null:(String) v;
        } else if(s==null && !scopes.isEmpty()) ambiguous(key);
        return super.getProperty(key);
    }
    @Override public String getProperty(String key,String fallback) { String v=getProperty(key); return v==null?fallback:v; }
    @Override public Object get(Object key) {
        Scope s=scope();
        if(s!=null && !shared(key)) {
            Object v=s.overlay.get(key);
            if(v!=null) return v==ABSENT?null:v;
        }
        return super.get(key);
    }
    @Override public boolean containsKey(Object key) { return get(key)!=null; }
    @Override public synchronized Object put(Object key,Object value) {
        Scope s=scope();
        if(s!=null && !shared(key)) { Object old=s.overlay.put((String) key,value); return old==null?super.get(key):old==ABSENT?null:old; }
        return super.put(key,value);
    }
    @Override public synchronized Object remove(Object key) {
        Scope s=scope();
        if(s!=null && !shared(key)) { Object old=s.overlay.put((String) key,ABSENT); return old==null?super.get(key):old==ABSENT?null:old; }
        return super.remove(key);
    }

    /** A runtime's whole view (log4j and others enumerate all properties): the base with its overlay applied. */
    private Map<Object,Object> view(Scope s) {
        Map<Object,Object> view=new LinkedHashMap<>();
        for(var e:super.entrySet()) view.put(e.getKey(),e.getValue());
        s.overlay.forEach((k,v)->{ if(v==ABSENT) view.remove(k); else view.put(k,v); });
        return view;
    }
    @Override public Set<Map.Entry<Object,Object>> entrySet() { Scope s=scope(); return s==null?super.entrySet():Collections.unmodifiableSet(view(s).entrySet()); }
    @Override public Set<Object> keySet() { Scope s=scope(); return s==null?super.keySet():Collections.unmodifiableSet(view(s).keySet()); }
    @Override public Collection<Object> values() { Scope s=scope(); return s==null?super.values():Collections.unmodifiableCollection(view(s).values()); }
    @Override public Enumeration<Object> keys() { Scope s=scope(); return s==null?super.keys():Collections.enumeration(view(s).keySet()); }
    @Override public Enumeration<?> propertyNames() { return keys(); }
    @Override public Set<String> stringPropertyNames() {
        Scope s=scope();
        if(s==null) return super.stringPropertyNames();
        Set<String> names=new TreeSet<>();
        view(s).forEach((k,v)->{ if(k instanceof String key && v instanceof String) names.add(key); });
        return Collections.unmodifiableSet(names);
    }
    @Override public int size() { Scope s=scope(); return s==null?super.size():view(s).size(); }
    @Override public synchronized boolean equals(Object o) { return this==o; }
    @Override public synchronized int hashCode() { return System.identityHashCode(this); }

    /**
     * A thread outside every runtime whose context loader is the JVM's own (the common pool, JDK service threads) reads
     * a key some runtime overlays: it gets the host's value, which may not be what its caller meant. Logged once per key.
     */
    private void ambiguous(String key) {
        if(REPORTING.get()!=null) return;
        boolean overlaid=false;
        for(Scope s:scopes) if(s.overlay.containsKey(key)) { overlaid=true; break; }
        if(!overlaid) return;
        ClassLoader context=Thread.currentThread().getContextClassLoader();
        if(context!=null && context!=ClassLoader.getSystemClassLoader() && context!=ClassLoader.getPlatformClassLoader()) return;
        if(!ambiguousReported.add(key)) return;
        REPORTING.set(Boolean.TRUE);
        try { MmlfLog.warn("launcher","injvm_property_foreign_read",Map.of("key",key,"thread",Thread.currentThread().getName())); }
        finally { REPORTING.remove(); }
    }
}
