package dev.mmlf.launcher.runtime;

import dev.mmlf.common.InJvm;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.launcher.SessionHost;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Runs a runtime inside this JVM through its loader's own launch, in a class loader of its own: nothing of the host
 * runtime is visible to it (its parent is the platform loader), so it defines its own Minecraft, Mixin environment,
 * registries and mod classes. The two runtimes meet only through {@link InJvm}: the session's Coordinator port and a
 * String-only context telling the started runtime who it is.
 *
 * <p>System properties a loader reads (the class path property, its log configuration, LWJGL's native directory) are
 * the started runtime's own: {@link ScopedProperties} shows them to its threads only, and hides the host loader's own
 * keys from it. ({@code -Dmmlf.injvm.properties=window} falls back to setting them JVM-wide until its bridge is up.)
 * A loader whose launch needs boot-layer modules (NeoForge) gets them in a {@link FarModuleLayer}.
 */
public final class InJvmBackend implements RuntimeBackend {
    /** Registry keys the bridge of a started runtime writes or reads, by loader. */
    public static String initializedKey(String loader) { return "initialized:"+loader; }
    public static String stopKey(String loader) { return "stop:"+loader; }
    public static String exitKey(String loader) { return "exit:"+loader; }
    /** The phase the runtime's bridge reached, a {@code PHASE_*} String. */
    public static String phaseKey(String loader) { return "phase:"+loader; }
    /**
     * A {@link Runnable} the runtime's bridge leaves for its own clean-up (link, shared files, threads): run once by the
     * backend when the runtime's main thread has ended, since the runtime's shutdown hooks only run when the JVM ends.
     */
    public static String cleanupKey(String loader) { return "cleanup:"+loader; }
    public static final String PHASE_LOADER="loader", PHASE_BRIDGE="bridge", PHASE_GAME="game", PHASE_WORLD="world", PHASE_STOPPING="stopping";
    private static String guardKey(String loader) { return "running:"+loader; }
    /** How long the start-up properties stay set when the runtime's bridge never reports. */
    private static final long PROPERTY_WINDOW_MS=Long.getLong("mmlf.injvm.propertyWindowMs",180_000);
    /** Longest a phase may last before the runtime counts as stalled; 0: no limit (a runtime in its world). */
    static long phaseTimeoutMs(String phase) {
        return switch(phase) {
            case PHASE_LOADER -> Long.getLong("mmlf.injvm.loaderTimeoutMs",180_000);
            case PHASE_BRIDGE -> Long.getLong("mmlf.injvm.gameTimeoutMs",300_000);
            case PHASE_GAME -> Long.getLong("mmlf.injvm.worldTimeoutMs",600_000);
            case PHASE_STOPPING -> Long.getLong("mmlf.injvm.stopTimeoutMs",60_000);
            default -> 0;
        };
    }
    private static final Object PROPERTY_LOCK=new Object();
    /**
     * Prefixes of properties a loader or its libraries set for themselves while the host started. The started runtime
     * must not inherit the host's (NeoForge's log4j context selector names a ModLauncher class, for one), so while it
     * starts these are unset unless its own launch sets them.
     */
    private static final List<String> HOST_ONLY=List.of("log4j","fml.","fabric.","mixin.","legacyClassPath","ignoreList","mergeModules","securejarhandler.","bsl.","neoforge.","forge.","net.minecraftforge.","java.util.logging.manager");

    private final SessionHost session;
    public InJvmBackend(SessionHost session) { this.session=Objects.requireNonNull(session); }
    public String name() { return "injvm"; }

    public RuntimeHandle start(RuntimeSpec spec) throws Exception {
        if(spec.java()==null) throw new IllegalArgumentException("the in-JVM backend runs Java launches only");
        if(!session.session().inJvm()) throw new IllegalArgumentException("in-JVM runtimes need the session's in-JVM endpoint");
        String loader=spec.loader();
        if(InJvm.context(loader)!=null || ownLoader().equals(loader)) throw new IllegalStateException("this JVM already runs a "+loader+" runtime");
        var registry=InJvm.registry();
        if(registry.putIfAbsent(guardKey(loader),spec.id())!=null) throw new IllegalStateException("a "+loader+" runtime is already starting in this JVM");
        try {
            return launch(spec);
        } catch(Exception|Error e) {
            registry.remove(guardKey(loader)); registry.remove(InJvm.contextKey(loader));
            throw e;
        }
    }
    /** The loader of the runtime hosting this backend: one runtime per loader per JVM. */
    private static String ownLoader() {
        ClassLoader own=InJvmBackend.class.getClassLoader();
        for(String[] probe:new String[][]{{"fabric","net.fabricmc.loader.api.FabricLoader"},{"neoforge","net.neoforged.fml.loading.FMLLoader"}})
            try { Class.forName(probe[1],false,own); return probe[0]; } catch(ClassNotFoundException|LinkageError ignored) { }
        return "";
    }

    private RuntimeHandle launch(RuntimeSpec spec) throws Exception {
        JavaLaunch java=spec.java();
        String loader=spec.loader();
        var registry=InJvm.registry();
        Map<String,String> context=new ConcurrentHashMap<>();
        context.put("runtime",spec.id()); context.put("loader",loader);
        context.put("session",session.sessionFile().toString()); context.put("primary","false");
        context.put("gameDirectory",spec.gameDirectory().toString());
        registry.put(InJvm.contextKey(loader),context);
        registry.remove(initializedKey(loader)); registry.remove(stopKey(loader)); registry.remove(exitKey(loader)); registry.remove(cleanupKey(loader));
        registry.put(phaseKey(loader),PHASE_LOADER);

        ClassLoader classes; ModuleLayer layer=null; URLClassLoader urls=null;
        Map<String,String> props=new LinkedHashMap<>(java.properties());
        if(java.modulePath().isEmpty()) {
            URL[] cp=new URL[java.classpath().size()];
            for(int i=0;i<cp.length;i++) cp[i]=java.classpath().get(i).toUri().toURL();
            classes=urls=new TrappingLoader("mmlf-"+spec.id(),cp,JdkOnlyLoader.get());
            // Knot reads its class path from the property its JVM would have been started with.
            props.put("java.class.path",join(java.classpath()));
        } else {
            layer=FarModuleLayer.create("mmlf-"+spec.id()+"-boot",java.modulePath());
            List<String> skipped=JvmAccess.applyModuleOptions(java.jvmOptions(),layer);
            List<String> filesystems=JvmAccess.installFileSystems(layer);
            // Its own check looks for itself in the boot layer, where a JVM started without it cannot have it.
            props.putIfAbsent("securejarhandler.throwOnMissingModule","false");
            MmlfLog.info("launcher","injvm_module_layer",Map.of("runtimeId",spec.id(),"modules",layer.modules().size(),"fileSystems",filesystems,"skippedOptions",skipped));
            classes=layer.findLoader(layer.modules().iterator().next().getName());
            // Its dev launch finds the game jar on the class path; the JVM's is the host's, with the host's game in it.
            props.put("java.class.path",join(legacyClassPath(java)));
        }
        Class<?> main=Class.forName(java.mainClass(),false,classes);
        Method entry=main.getMethod("main",String[].class);
        Handle handle=new Handle(spec,urls,layer);
        writeProperties(session.directory().resolve(spec.id()+"-host-properties.txt"));
        for(String key:System.getProperties().stringPropertyNames())
            if(!props.containsKey(key) && HOST_ONLY.stream().anyMatch(key::startsWith)) props.put(key,null);
        Map<String,String> saved;
        Thread thread;
        Runnable body=()->handle.run(entry,java.args());
        if(scopedProperties()) {
            // The runtime's values live in its own overlay, seen by its threads only: the host's never change.
            ScopedProperties scoped=ScopedProperties.install();
            ThreadGroup group=new ThreadGroup("mmlf-"+spec.id());
            ScopedProperties.Scope scope=scoped.open(spec.id(),group);
            scope.addLoader(classes);
            props.forEach(scope::set);
            handle.scope=scope; handle.scoped=scoped;
            saved=Map.of();
            thread=new Thread(group,body,"mmlf-"+spec.id()+"-main");
            MmlfLog.info("launcher","injvm_properties_scoped",Map.of("runtimeId",spec.id(),"keys",props.size(),"hidden",props.values().stream().filter(Objects::isNull).count()));
        } else {
            saved=applyProperties(props);
            thread=new Thread(body,"mmlf-"+spec.id()+"-main");
        }
        thread.setContextClassLoader(classes);
        thread.setDaemon(false);
        handle.thread=thread;
        watch(handle,saved);
        MmlfLog.info("launcher","injvm_start",Map.of("runtimeId",spec.id(),"loader",loader,"mainClass",java.mainClass(),"classpath",java.classpath().size(),"modulePath",java.modulePath().size()));
        thread.start();
        return handle;
    }

    /**
     * Follows the runtime's phases on a daemon thread until it ends: puts the host's properties back once the runtime's
     * bridge is up (its loader and LWJGL have read theirs by then), and reports a phase that lasts too long. A stalled
     * runtime is asked to stop; a thread stuck before its bridge exists cannot be stopped from here.
     */
    private static void watch(Handle handle,Map<String,String> saved) {
        String loader=handle.spec.loader();
        var registry=InJvm.registry();
        Thread watcher=new Thread(()->{
            boolean restored=false; String last=null; long since=System.nanoTime(), started=since;
            while(!handle.exit.isDone()) {
                long now=System.nanoTime();
                String phase=String.valueOf(registry.getOrDefault(phaseKey(loader),PHASE_LOADER));
                if(!phase.equals(last)) {
                    MmlfLog.info("launcher","injvm_phase",Map.of("runtimeId",handle.spec.id(),"phase",phase,"previous",String.valueOf(last),"previousMs",(now-since)/1_000_000));
                    last=phase; since=now; handle.phase(phase);
                }
                boolean up=!phase.equals(PHASE_LOADER) || registry.get(initializedKey(loader))!=null;
                if(!restored && (up || now-started>PROPERTY_WINDOW_MS*1_000_000)) { restore(handle,saved,up); restored=true; }
                long limit=phaseTimeoutMs(phase);
                if(limit>0 && now-since>limit*1_000_000 && !handle.stalled.isDone()) handle.stall(phase,(now-since)/1_000_000);
                try { Thread.sleep(100); } catch(InterruptedException e) { break; }
            }
            if(!restored) restore(handle,saved,false);
        },"mmlf-"+handle.spec.id()+"-watch");
        watcher.setDaemon(true);
        watcher.start();
    }
    private static void restore(Handle handle,Map<String,String> saved,boolean initialized) {
        restoreProperties(saved);
        MmlfLog.info("launcher","injvm_properties_restored",Map.of("runtimeId",handle.spec.id(),"initialized",initialized,"keys",saved.keySet()));
    }

    /**
     * {@code -Dmmlf.injvm.properties}: {@code scoped} (default) gives the runtime its own view of the system properties
     * ({@link ScopedProperties}); {@code window} is the earlier behaviour, setting them JVM-wide while it starts.
     */
    static boolean scopedProperties() { return !"window".equalsIgnoreCase(System.getProperty("mmlf.injvm.properties",System.getenv().getOrDefault("MMLF_INJVM_PROPERTIES","scoped"))); }

    /** Sets the started runtime's JVM-wide properties; returns the previous values (null: unset) to restore. */
    private static Map<String,String> applyProperties(Map<String,String> props) {
        synchronized(PROPERTY_LOCK) {
            Map<String,String> saved=new LinkedHashMap<>();
            for(var e:props.entrySet()) {
                String old=System.getProperty(e.getKey());
                if(Objects.equals(old,e.getValue())) continue;
                saved.put(e.getKey(),old);
                if(e.getValue()==null) System.clearProperty(e.getKey()); else System.setProperty(e.getKey(),e.getValue());
            }
            return saved;
        }
    }
    /** Puts back values the host had; values only the started runtime uses stay, nothing else in this JVM reads them. */
    private static void restoreProperties(Map<String,String> saved) {
        synchronized(PROPERTY_LOCK) { saved.forEach((k,v)->{ if(v!=null) System.setProperty(k,v); }); }
    }
    private static void writeProperties(Path file) {
        try {
            List<String> lines=new ArrayList<>();
            for(String key:new TreeSet<>(System.getProperties().stringPropertyNames())) lines.add(key+"="+System.getProperty(key));
            java.nio.file.Files.write(file,lines,java.nio.charset.StandardCharsets.UTF_8);
        } catch(java.io.IOException ignored) { }
    }
    /** The class path a module-path launch hands its bootstrap: its own class path, else the file it names. */
    private static List<Path> legacyClassPath(JavaLaunch launch) throws java.io.IOException {
        if(!launch.classpath().isEmpty()) return launch.classpath();
        String file=launch.property("legacyClassPath.file");
        List<Path> out=new ArrayList<>();
        if(file!=null) {
            for(String line:java.nio.file.Files.readAllLines(Path.of(file))) if(!line.isBlank()) out.add(Path.of(line.trim()));
        } else if(launch.property("legacyClassPath")!=null) {
            for(String part:launch.property("legacyClassPath").split(File.pathSeparator)) out.add(Path.of(part));
        }
        return out;
    }
    /**
     * Class path root of a class-path launch (Fabric's Knot). Its loader's own classes call {@code System.exit} on
     * their error paths, before any Mixin of ours exists; those calls go to {@link InJvmExitTrap} instead, defined in
     * this loader from its JDK-only class file. Nothing else is changed.
     */
    static final class TrappingLoader extends URLClassLoader {
        static { registerAsParallelCapable(); }
        static final List<String> TRAPPED=List.of("net/fabricmc/loader/");
        private static final String TRAP=InJvmExitTrap.class.getName();
        TrappingLoader(String name,URL[] urls,ClassLoader parent) { super(name,urls,parent); }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal=name.replace('.','/');
            boolean trap=name.equals(TRAP);
            if(!trap && TRAPPED.stream().noneMatch(internal::startsWith)) return super.findClass(name);
            try {
                byte[] bytes;
                java.security.CodeSource source=null;
                if(trap) {
                    try(var in=InJvmExitTrap.class.getResourceAsStream("/"+internal+".class")) {
                        if(in==null) throw new ClassNotFoundException(name);
                        bytes=in.readAllBytes();
                    }
                } else {
                    URL url=findResource(internal+".class");
                    if(url==null) throw new ClassNotFoundException(name);
                    try(var in=url.openStream()) { bytes=in.readAllBytes(); }
                    bytes=ClassPatch.redirectStatic(bytes,"java/lang/System","exit",TRAP.replace('.','/'));
                    source=codeSource(url,internal);
                }
                definePackageFor(name,source);
                return defineClass(name,bytes,0,bytes.length,source==null?null:new java.security.ProtectionDomain(source,null,this,null));
            } catch(java.io.IOException e) { throw new ClassNotFoundException(name,e); }
        }
        /** The jar (or directory) a class came from, as the loader's own lookups expect to find it. */
        private static java.security.CodeSource codeSource(URL url,String internal) throws java.io.IOException {
            String text=url.toString();
            URL root=java.net.URI.create(text.startsWith("jar:")?text.substring(4,text.indexOf("!/")):text.substring(0,text.length()-(internal+".class").length())).toURL();
            return new java.security.CodeSource(root,(java.security.CodeSigner[]) null);
        }
        private void definePackageFor(String name,java.security.CodeSource source) {
            int dot=name.lastIndexOf('.');
            if(dot<0) return;
            String pkg=name.substring(0,dot);
            if(getDefinedPackage(pkg)==null) try { definePackage(pkg,null,null,null,null,null,null,source==null?null:source.getLocation()); } catch(IllegalArgumentException ignored) { }
        }
    }

    private static String join(List<Path> paths) { StringJoiner j=new StringJoiner(File.pathSeparator); paths.forEach(p->j.add(p.toString())); return j.toString(); }

    private static final class Handle implements RuntimeHandle {
        final RuntimeSpec spec; final URLClassLoader urls; final ModuleLayer layer;
        final CompletableFuture<Exit> exit=new CompletableFuture<>();
        final CompletableFuture<String> stalled=new CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
        volatile State state=State.STARTING;
        volatile Thread thread;
        volatile ScopedProperties.Scope scope; volatile ScopedProperties scoped;
        Handle(RuntimeSpec spec,URLClassLoader urls,ModuleLayer layer) { this.spec=spec; this.urls=urls; this.layer=layer; }
        /** Follows the bridge's phase; a runtime that ended keeps its final state. */
        void phase(String phase) {
            if(finished.get()) return;
            state=switch(phase) {
                case PHASE_BRIDGE -> State.LOADER_INIT;
                case PHASE_GAME -> State.GAME_INIT;
                case PHASE_WORLD -> State.IN_WORLD;
                case PHASE_STOPPING -> State.STOPPING;
                default -> State.STARTING;
            };
        }
        void stall(String phase,long millis) {
            String reason="phase "+phase+" did not end within "+millis/1000+" s";
            MmlfLog.error("launcher","injvm_phase_timeout",Map.of("runtimeId",spec.id(),"phase",phase,"elapsedMs",millis));
            try { java.nio.file.Files.writeString(spec.log(),"MMLF: "+reason+System.lineSeparator(),java.nio.charset.StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND); }
            catch(java.io.IOException ignored) { }
            if(!phase.equals(PHASE_STOPPING)) InJvm.registry().put(stopKey(spec.loader()),Boolean.TRUE);
            stalled.complete(reason);
        }
        public CompletableFuture<String> stalled() { return stalled; }

        void run(Method entry,List<String> args) {
            Exit result;
            try {
                entry.invoke(null,(Object) args.toArray(String[]::new));
                result=exitFromBridge(0,null);
            } catch(InvocationTargetException e) {
                Throwable cause=e.getCause()==null?e:e.getCause();
                Object code=InJvm.registry().get(exitKey(spec.loader()));
                // An exception escaping the game's main: the game did not get to end itself (its exit code would be recorded).
                result=new Exit(code instanceof Number n?n.intValue():-1,rootCause(cause));
                MmlfLog.error("launcher","injvm_runtime_failed",Map.of("runtimeId",spec.id(),"exception",cause.toString(),"rootCause",rootCause(cause)));
                report(cause);
            } catch(Throwable t) {
                result=new Exit(-1,t.toString()); report(t);
            }
            finish(result);
        }
        /** The whole chain, to the runtime's log in the session: its own logging may be what failed. */
        private void report(Throwable t) {
            java.io.StringWriter text=new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(text));
            try { java.nio.file.Files.writeString(spec.log(),text.toString(),java.nio.charset.StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND); }
            catch(java.io.IOException ignored) { }
            System.err.println("MMLF in-JVM runtime "+spec.id()+" failed:"); System.err.print(text);
        }
        private static String rootCause(Throwable t) { Throwable c=t; while(c.getCause()!=null && c.getCause()!=c) c=c.getCause(); return c==t?t.toString():t+" <- "+c; }
        private Exit exitFromBridge(int fallback,String failure) {
            Object code=InJvm.registry().get(exitKey(spec.loader()));
            int value=code instanceof Number n?n.intValue():fallback;
            return new Exit(value,failure);
        }
        /** Runs once, on the runtime's main thread after the game's main returned or threw. */
        private void finish(Exit result) {
            if(!finished.compareAndSet(false,true)) return;
            var registry=InJvm.registry();
            // The runtime's own clean-up first: its link must not keep reconnecting or receiving for a game that ended.
            if(registry.remove(cleanupKey(spec.loader())) instanceof Runnable cleanup) {
                try { cleanup.run(); }
                catch(RuntimeException|LinkageError e) { MmlfLog.warn("launcher","injvm_cleanup_failed",Map.of("runtimeId",spec.id(),"exception",e.toString())); }
            }
            state=result.failed()?State.FAILED:State.EXITED;
            registry.remove(phaseKey(spec.loader()));
            registry.remove(InJvm.contextKey(spec.loader()));
            registry.remove(guardKey(spec.loader()));
            registry.remove(initializedKey(spec.loader()));
            registry.remove(stopKey(spec.loader()));
            if(layer!=null) FarModuleLayer.release(layer);
            // Threads it left behind then read the host's values; nothing of it should still be running by now.
            if(scope!=null) scoped.close(scope);
            // Threads the runtime started may still run; the loader stays reachable through them until they end.
            if(urls!=null) try { urls.close(); } catch(java.io.IOException ignored) { }
            exit.complete(result);
        }

        public String id() { return spec.id(); }
        public String backend() { return "injvm"; }
        public State state() { return state; }
        public CompletableFuture<Exit> exit() { return exit; }
        /** Asks the runtime's bridge to stop its game; a game thread that does not end in time cannot be forced. */
        public void stop(Duration timeout) {
            InJvm.registry().put(stopKey(spec.loader()),Boolean.TRUE);
            try { exit.get(Math.max(1,timeout.toMillis()),TimeUnit.MILLISECONDS); }
            catch(TimeoutException e) {
                MmlfLog.warn("launcher","injvm_stop_timeout",Map.of("runtimeId",spec.id(),"timeoutMs",timeout.toMillis()));
                Thread t=thread; if(t!=null) t.interrupt();
            }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            catch(ExecutionException ignored) { }
        }
    }
}
