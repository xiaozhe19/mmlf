package dev.mmlf.launcher;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Session;
import dev.mmlf.coordinator.Coordinator;
import dev.mmlf.coordinator.CoordinatorCore;
import dev.mmlf.coordinator.InJvmPort;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * One fusion session: its directory, token and Coordinator, and the staging of each runtime's mods. Runtimes are
 * started by a {@link dev.mmlf.launcher.runtime.RuntimeManager}. The Coordinator listens where its runtimes are: in
 * this JVM ({@link Endpoint#IN_JVM}) or on a loopback WebSocket for runtimes in other processes.
 */
public final class SessionHost implements AutoCloseable {
    public static final Map<String,ModClassifier.Loader> LOADERS=Map.of("fabric",ModClassifier.Loader.FABRIC,"neoforge",ModClassifier.Loader.NEOFORGE);
    public enum Endpoint { IN_JVM, WEBSOCKET }
    private final CoordinatorCore core;
    private final Coordinator socket;
    private final InJvmPort port;
    private final Path directory, sessionFile;
    private final Session session;
    private boolean closed;

    private SessionHost(CoordinatorCore core,Coordinator socket,InJvmPort port,Path directory,Session session) throws Exception {
        this.core=core; this.socket=socket; this.port=port; this.directory=directory; this.session=session;
        sessionFile=directory.resolve("session.json"); session.write(sessionFile);
    }
    public CoordinatorCore core() { return core; }
    public Path directory() { return directory; }
    public Path sessionFile() { return sessionFile; }
    public Session session() { return session; }

    /** Development session under the project's {@code runtime} directory, with the project's demo scene. */
    public static SessionHost create(Path root,String primary,boolean selfTest,Endpoint endpoint) throws Exception {
        Path sceneConfig=root.resolve("config/demo-scene.json");
        return create(root.resolve("runtime"),Files.isRegularFile(sceneConfig)?Files.readString(sceneConfig):DEFAULT_SCENE,primary,selfTest,endpoint);
    }
    /** Development scene used when the project has no config/demo-scene.json. */
    private static final String DEFAULT_SCENE="""
        {"placements":[{"runtime":"fabric","block":"mmlf_fabric_test:demo_block","x":-1,"y":100,"z":0},{"runtime":"neoforge","block":"mmlf_neoforge_test:demo_block","x":1,"y":100,"z":0}]}
        """;

    /** Creates a session directory under {@code runtime}, which also receives latest-session.txt; {@code scene} may be null. */
    public static SessionHost create(Path runtime,String scene,String primary,boolean selfTest,Endpoint endpoint) throws Exception {
        if(!LOADERS.containsKey(primary)) throw new IllegalArgumentException("unknown primary runtime "+primary);
        Files.createDirectories(runtime);
        Path dir=Files.createTempDirectory(runtime,"session-");
        MmlfLog.configure("manager",dir,MmlfLog.configuredLevel());
        MmlfLog.info("launcher","session_created",Map.of("directory",dir.toString(),"primary",primary));
        Files.writeString(runtime.resolve("latest-session.txt"),dir.toString());
        byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
        String token=HexFormat.of().formatHex(secret);
        CoordinatorCore core=new CoordinatorCore(token);
        Coordinator socket=null; InJvmPort port=null; String uri;
        if(endpoint==Endpoint.IN_JVM) {
            Session probe=new Session(Session.IN_JVM,token,dir.toString(),primary);
            port=new InJvmPort(core,probe.identity());
            uri=Session.IN_JVM;
        } else {
            socket=new Coordinator(0,core);
            socket.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(socket.getPort()==0 && System.nanoTime()<deadline) Thread.sleep(20);
            if(socket.getPort()==0) { try { socket.stop(); } catch(Exception ignored) { } throw new IllegalStateException("Coordinator did not start"); }
            uri="ws://127.0.0.1:"+socket.getPort();
        }
        // Without a scenario the clients play their own menus and worlds; with one they open the demo world.
        if(scene!=null) Files.writeString(dir.resolve("scenario.json"),scene);
        if(selfTest) Files.createFile(dir.resolve("self-test"));
        MmlfLog.info("launcher","coordinator_started",Map.of("endpoint",endpoint.name()));
        return new SessionHost(core,socket,port,dir,new Session(uri,token,dir.toString(),primary));
    }

    /** NeoForge's early loading window opens before any mod runs; a background one must not show it. */
    public void suppressEarlyWindow(String runtime,Path gameDirectory) throws Exception {
        if(!runtime.equals("neoforge") || runtime.equals(session.primary())) return;
        Path config=gameDirectory.resolve("config"); Files.createDirectories(config);
        Files.writeString(config.resolve("fml.toml"),"earlyWindowControl = false"+System.lineSeparator());
    }

    /** Gradle hook for a client that hosts its own session: args are the mod source, loader name and destination. */
    public static void main(String[] args) throws Exception {
        stageMods(Path.of(args[0]),ModClassifier.Loader.valueOf(args[1].toUpperCase(Locale.ROOT)),Path.of(args[2]));
    }

    /** Development third-party mods: {@code MMLF_MODS_DIR} (a compatibility profile), else the project's mmlf/mods. */
    public static Path modsSource(Path root) {
        String dir=System.getenv("MMLF_MODS_DIR");
        return dir!=null && !dir.isBlank()?Path.of(dir):root.resolve("mmlf/mods");
    }
    /** Names of the archives the previous staging copied into a destination; they go before staging again. */
    static final String STAGED=".mmlf-staged";

    /**
     * Copies only archives whose metadata names this loader; ambiguous archives are skipped and reported. Archives an
     * earlier staging copied there are removed first, so a mod set never leaks into the next run; others stay.
     */
    public static void stageMods(Path mods,ModClassifier.Loader loader,Path destination) throws Exception {
        Files.createDirectories(mods); Files.createDirectories(destination);
        Path record=destination.resolve(STAGED);
        if(Files.isRegularFile(record)) for(String name:Files.readAllLines(record)) {
            if(name.isBlank() || name.contains("/") || name.contains("\\")) continue;
            Files.deleteIfExists(destination.resolve(name));
        }
        List<String> staged=new ArrayList<>();
        try(var paths=Files.list(mods)) {
            for(Path file:paths.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                var result=ModClassifier.classify(file);
                if(result.loader()!=loader) {
                    if(result.loader()!=ModClassifier.Loader.FABRIC && result.loader()!=ModClassifier.Loader.NEOFORGE)
                        System.err.println("MMLF: skipped "+file.getFileName()+": "+result.loader()+" ("+result.detail()+")");
                    continue;
                }
                Files.copy(file,destination.resolve(file.getFileName()),StandardCopyOption.REPLACE_EXISTING);
                staged.add(file.getFileName().toString());
                System.out.println("MMLF: staged "+file.getFileName()+" to "+loader+"; loader checks dependencies at startup");
            }
        }
        Files.write(record,staged);
    }

    /** Stops the Coordinator; the runtimes belong to the {@link dev.mmlf.launcher.runtime.RuntimeManager}. */
    @Override public synchronized void close() {
        if(closed) return; closed=true;
        if(port!=null) port.close();
        if(socket!=null) try { socket.stop(); } catch(Exception ignored) { }
        // In-JVM frame channels live as long as the session that names them.
        if(session.inJvm()) dev.mmlf.common.FrameFile.releaseMemory(session.identity());
        MmlfLog.info("launcher","shutdown",Map.of());
    }
}
