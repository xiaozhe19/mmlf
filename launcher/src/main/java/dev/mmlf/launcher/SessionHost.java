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
        // Real-world sessions have no scene: the primary plays its own saves and the background follows them.
        if(realWorld()!=null) return create(root.resolve("runtime"),null,primary,selfTest);
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

    /**
     * Development real-world mode ({@code MMLF_WORLD}): null without it; otherwise {@code "menu"} (the player picks or
     * creates a save on the title screen) or the name of a save the primary opens, creating a normal survival world the
     * first time.
     */
    public static String realWorld() {
        String value=System.getenv("MMLF_WORLD");
        if(value==null || value.isBlank()) return null;
        value=value.trim();
        if(value.contains("/") || value.contains("\\") || value.contains("..")) throw new IllegalArgumentException("MMLF_WORLD must be a save name: "+value);
        return value;
    }
    /**
     * Game directory of a development runtime. Demo sessions give each runtime a fresh one in the session; real-world
     * sessions keep a background's directory across sessions, since its save holds the blocks only it owns. It depends
     * on the primary, because a save is only followed from the primary that created it.
     */
    public static Path gameDirectory(Path root,SessionHost session,String runtime) {
        if(Files.isRegularFile(session.directory().resolve("scenario.json")) || realWorld()==null) return session.directory().resolve(runtime);
        return root.resolve("runtime").resolve("real").resolve(session.session().primary()+"-primary").resolve(runtime);
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
     * Stages only archives for this loader. A file is managed only while its contents match the last staged copy.
     */
    public static void stageMods(Path mods,ModClassifier.Loader loader,Path destination) throws Exception {
        Files.createDirectories(mods);
        Files.createDirectories(destination);
        Path record=destination.resolve(STAGED);
        Map<String,String> managed=new LinkedHashMap<>();
        if(Files.isRegularFile(record)) for(String line:Files.readAllLines(record)) {
            String[] entry=line.split("\t",2);
            if(entry.length==2 && entry[0].matches("[0-9a-f]{64}") && entry[1].matches("[^/\\\\]+(?i:\\.jar)")) {
                Path target=destination.resolve(entry[1]);
                if(Files.isRegularFile(target,java.nio.file.LinkOption.NOFOLLOW_LINKS) && digest(target).equals(entry[0]))
                    managed.put(entry[1],entry[0]);
            } else if(entry.length==1 && line.matches("[^/\\\\\t]+(?i:\\.jar)")) {
                // A record from before contents were recorded (names only): those files were staged, and are taken
                // over as they are now, so the earlier mod set still goes away instead of doubling a mod.
                Path target=destination.resolve(line);
                if(Files.isRegularFile(target,java.nio.file.LinkOption.NOFOLLOW_LINKS)) managed.put(line,digest(target));
            }
        }
        Set<String> wanted=new HashSet<>();
        try(var paths=Files.list(mods)) {
            for(Path file:paths.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                var result=ModClassifier.classify(file);
                if(result.loader()!=loader) {
                    if(result.loader()!=ModClassifier.Loader.FABRIC && result.loader()!=ModClassifier.Loader.NEOFORGE)
                        System.err.println("MMLF: skipped "+file.getFileName()+": "+result.loader()+" ("+result.detail()+")");
                    continue;
                }
                String name=file.getFileName().toString();
                Path target=destination.resolve(name);
                wanted.add(name);
                boolean owned=managed.containsKey(name);
                if(!owned && Files.exists(target,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    System.err.println("MMLF: kept existing mod "+target+"; staging skipped the same name");
                    continue;
                }
                String hash=digest(file);
                if(hash.equals(managed.get(name))) continue;
                Path temporary=Files.createTempFile(destination,".mmlf-mod-",".tmp");
                try {
                    Files.copy(file,temporary,StandardCopyOption.REPLACE_EXISTING);
                    hash=digest(temporary);
                    if(owned) {
                        if(!Files.isRegularFile(target,java.nio.file.LinkOption.NOFOLLOW_LINKS) || !managed.get(name).equals(digest(target))) {
                            managed.remove(name);
                            continue;
                        }
                        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    } else Files.move(temporary,target);
                    managed.put(name,hash);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                System.out.println("MMLF: staged "+name+" to "+loader+"; loader checks dependencies at startup");
            }
            for(var it=managed.keySet().iterator();it.hasNext();) {
                String name=it.next();
                if(wanted.contains(name)) continue;
                Path target=destination.resolve(name);
                if(Files.isRegularFile(target,java.nio.file.LinkOption.NOFOLLOW_LINKS) && managed.get(name).equals(digest(target)))
                    Files.delete(target);
                it.remove();
            }
        } finally {
            Path temporary=Files.createTempFile(destination,".mmlf-manifest-",".tmp");
            try {
                Files.write(temporary,managed.entrySet().stream().map(e->e.getValue()+"\t"+e.getKey()).toList());
                Files.move(temporary,record,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String digest(Path file) throws Exception {
        var sha=java.security.MessageDigest.getInstance("SHA-256");
        try(var input=new java.security.DigestInputStream(Files.newInputStream(file),sha)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha.digest());
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
