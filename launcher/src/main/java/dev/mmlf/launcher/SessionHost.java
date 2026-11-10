package dev.mmlf.launcher;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Session;
import dev.mmlf.coordinator.CoordinatorCore;
import dev.mmlf.coordinator.InJvmPort;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * One fusion session: its directory, token and Coordinator, and the staging of each runtime's mods. Runtimes are
 * started by a {@link dev.mmlf.launcher.runtime.RuntimeManager} inside this JVM; the Coordinator serves them through an
 * {@link InJvmPort}.
 */
public final class SessionHost implements AutoCloseable {
    public static final Map<String,ModClassifier.Loader> LOADERS=Map.of("fabric",ModClassifier.Loader.FABRIC,"neoforge",ModClassifier.Loader.NEOFORGE);
    private final CoordinatorCore core;
    private final InJvmPort port;
    private final Path directory, sessionFile;
    private final Session session;
    private boolean closed;

    private SessionHost(CoordinatorCore core,InJvmPort port,Path directory,Session session) throws Exception {
        this.core=core; this.port=port; this.directory=directory; this.session=session;
        sessionFile=directory.resolve("session.json"); session.write(sessionFile);
    }
    public CoordinatorCore core() { return core; }
    public Path directory() { return directory; }
    public Path sessionFile() { return sessionFile; }
    public Session session() { return session; }

    /** Development session under the project's {@code runtime} directory, with the project's demo scene. */
    public static SessionHost create(Path root,String primary,boolean selfTest) throws Exception {
        // Manual sessions use the visual scene (no redstone fixtures) when present; the self-tests need the full one.
        Path visual=root.resolve("config/visual-scene.json");
        Path sceneConfig=!selfTest && Files.isRegularFile(visual) ? visual : root.resolve("config/demo-scene.json");
        return create(root.resolve("runtime"),Files.isRegularFile(sceneConfig)?Files.readString(sceneConfig):DEFAULT_SCENE,primary,selfTest);
    }
    /**
     * Development scene used when the project has no config/demo-scene.json: a test block of each runtime, and a villager
     * and a fenced-in zombie the background runtime owns, for entity interaction across runtimes.
     */
    private static final String DEFAULT_SCENE="""
        {"placements":[{"runtime":"fabric","block":"mmlf_fabric_test:demo_block","x":-1,"y":100,"z":0},{"runtime":"neoforge","block":"mmlf_neoforge_test:demo_block","x":1,"y":100,"z":0},{"runtime":"background","block":"minecraft:oak_fence","x":2,"y":100,"z":1,"properties":{"east":"true","south":"true"}},{"runtime":"background","block":"minecraft:oak_fence","x":3,"y":100,"z":1,"properties":{"east":"true","west":"true"}},{"runtime":"background","block":"minecraft:oak_fence","x":4,"y":100,"z":1,"properties":{"west":"true","south":"true"}},{"runtime":"background","block":"minecraft:oak_fence","x":2,"y":100,"z":2,"properties":{"north":"true","south":"true"}},{"runtime":"background","block":"minecraft:oak_fence","x":4,"y":100,"z":2,"properties":{"north":"true","south":"true"}},{"runtime":"background","block":"minecraft:oak_fence","x":2,"y":100,"z":3,"properties":{"north":"true","east":"true"}},{"runtime":"background","block":"minecraft:oak_fence","x":3,"y":100,"z":3,"properties":{"east":"true","west":"true"}},{"runtime":"background","block":"minecraft:oak_fence","x":4,"y":100,"z":3,"properties":{"west":"true","north":"true"}},{"runtime":"background","block":"minecraft:composter","x":-3,"y":100,"z":5}],"entities":[{"runtime":"background","type":"minecraft:villager","x":-2.5,"y":100,"z":2.5,"yaw":0,"profession":"minecraft:farmer"},{"runtime":"background","type":"minecraft:zombie","x":3.5,"y":100,"z":2.5,"yaw":0,"equipment":{"head":"minecraft:leather_helmet"}}]}
        """;

    /** Creates a session directory under {@code runtime}, which also receives latest-session.txt; {@code scene} may be null. */
    public static SessionHost create(Path runtime,String scene,String primary,boolean selfTest) throws Exception {
        if(!LOADERS.containsKey(primary)) throw new IllegalArgumentException("unknown primary runtime "+primary);
        Files.createDirectories(runtime);
        Path dir=Files.createTempDirectory(runtime,"session-");
        MmlfLog.configure("manager",dir,MmlfLog.configuredLevel());
        MmlfLog.info("launcher","session_created",Map.of("directory",dir.toString(),"primary",primary));
        Files.writeString(runtime.resolve("latest-session.txt"),dir.toString());
        byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
        String token=HexFormat.of().formatHex(secret);
        CoordinatorCore core=new CoordinatorCore(token);
        Session session=new Session(Session.IN_JVM,token,dir.toString(),primary);
        InJvmPort port=new InJvmPort(core,session.identity());
        // Without a scenario the clients play their own menus and worlds; with one they open the demo world.
        if(scene!=null) Files.writeString(dir.resolve("scenario.json"),scene);
        if(selfTest) Files.createFile(dir.resolve("self-test"));
        MmlfLog.info("launcher","coordinator_started",Map.of("endpoint","IN_JVM"));
        return new SessionHost(core,port,dir,session);
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
        port.close();
        // In-JVM frame channels live as long as the session that names them.
        dev.mmlf.common.FrameFile.releaseMemory(session.identity());
        MmlfLog.info("launcher","shutdown",Map.of());
    }
}
