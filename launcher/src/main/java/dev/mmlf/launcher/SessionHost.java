package dev.mmlf.launcher;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Session;
import dev.mmlf.coordinator.Coordinator;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Creates one fusion session: Coordinator, session directory, staged mods and the runtimes it is asked to start.
 * Used both by the standalone launcher and by a client that starts a session itself, so any runtime may be primary.
 */
public final class SessionHost implements AutoCloseable {
    /** Development run tasks; a runtime not listed here cannot be started by a session host. */
    public static final Map<String,String> RUN_TASKS=Map.of("fabric",":fabric-bridge:runClient","neoforge",":neoforge-bridge:runClient");
    static final Map<String,ModClassifier.Loader> LOADERS=Map.of("fabric",ModClassifier.Loader.FABRIC,"neoforge",ModClassifier.Loader.NEOFORGE);
    private final Coordinator coordinator;
    private final Path directory, sessionFile;
    private final Session session;
    private final List<Process> children=new ArrayList<>();
    private boolean closed;

    private SessionHost(Coordinator coordinator,Path directory,Session session) throws Exception {
        this.coordinator=coordinator; this.directory=directory; this.session=session;
        sessionFile=directory.resolve("session.json"); session.write(sessionFile);
    }
    public Coordinator coordinator() { return coordinator; }
    public Path directory() { return directory; }
    public Path sessionFile() { return sessionFile; }
    public Session session() { return session; }
    public List<Process> children() { return List.copyOf(children); }

    /** Starts the Coordinator and writes the session; runtimes are started separately with {@link #launch}. */
    public static SessionHost create(Path root,String primary,boolean selfTest) throws Exception {
        Path sceneConfig=root.resolve("config/demo-scene.json");
        return create(root.resolve("runtime"),Files.isRegularFile(sceneConfig)?Files.readString(sceneConfig):DEFAULT_SCENE,primary,selfTest);
    }
    /** Development scene used when the project has no config/demo-scene.json. */
    private static final String DEFAULT_SCENE="""
        {"placements":[{"runtime":"fabric","block":"mmlf_fabric_test:demo_block","x":-1,"y":100,"z":0},{"runtime":"neoforge","block":"mmlf_neoforge_test:demo_block","x":1,"y":100,"z":0}]}
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
        Coordinator coordinator=new Coordinator(0,token);
        coordinator.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(coordinator.getPort()==0 && System.nanoTime()<deadline) Thread.sleep(20);
        if(coordinator.getPort()==0) { try { coordinator.stop(); } catch(Exception ignored) { } throw new IllegalStateException("Coordinator did not start"); }
        // Without a scenario the clients play their own menus and worlds; with one they open the demo world.
        if(scene!=null) Files.writeString(dir.resolve("scenario.json"),scene);
        if(selfTest) Files.createFile(dir.resolve("self-test"));
        return new SessionHost(coordinator,dir,new Session("ws://127.0.0.1:"+coordinator.getPort(),token,dir.toString(),primary));
    }

    /** Starts development clients for the given runtimes in one Gradle process; their game directories live in the session. */
    public Process launch(Path root,List<String> runtimes) throws Exception {
        if(runtimes.isEmpty()) throw new IllegalArgumentException("no runtimes to launch");
        List<String> tasks=new ArrayList<>();
        for(String id:runtimes) {
            String task=RUN_TASKS.get(id); if(task==null) throw new IllegalArgumentException("unknown runtime "+id);
            tasks.add(task);
            stageMods(root.resolve("mmlf/mods"),LOADERS.get(id),directory.resolve(id).resolve("mods"));
            suppressEarlyWindow(id,directory.resolve(id));
        }
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path wrapper=root.resolve(windows?"gradlew.bat":"gradlew");
        List<String> command=new ArrayList<>(windows?List.of("cmd.exe","/d","/c",wrapper.toString()):List.of(wrapper.toString()));
        if(tasks.size()>1) command.add("--parallel");
        command.addAll(tasks);
        String log=runtimes.size()==2?"clients.log":String.join("-",runtimes)+".log";
        return start(command,root,log,runtimes);
    }
    /** Starts an installed game for one background runtime; its command already names this session. */
    public Process launchInstalled(String runtime,List<String> command,Path workingDirectory) throws Exception {
        return start(command,workingDirectory,runtime+".log",List.of(runtime));
    }
    private Process start(List<String> command,Path workingDirectory,String log,List<String> runtimes) throws Exception {
        ProcessBuilder pb=new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve(log).toFile());
        pb.environment().put("MMLF_SESSION",sessionFile.toString());
        pb.environment().put("MMLF_GAME_ROOT",directory.toString());
        pb.environment().put("MMLF_HIDE_SECONDARY","true");
        // Background runtimes exit as soon as this process is gone, however it ended.
        pb.environment().put("MMLF_HOST_PID",Long.toString(ProcessHandle.current().pid()));
        Process child=pb.start();
        synchronized(children) { children.add(child); }
        MmlfLog.info("launcher","process_start",Map.of("pid",child.pid(),"runtimes",runtimes,"log",directory.resolve(log).toString()));
        return child;
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

    /** Copies only archives whose metadata names this loader; ambiguous archives are skipped and reported. */
    public static void stageMods(Path mods,ModClassifier.Loader loader,Path destination) throws Exception {
        Files.createDirectories(mods); Files.createDirectories(destination);
        try(var paths=Files.list(mods)) {
            for(Path file:paths.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                var result=ModClassifier.classify(file);
                if(result.loader()!=loader) {
                    if(result.loader()!=ModClassifier.Loader.FABRIC && result.loader()!=ModClassifier.Loader.NEOFORGE)
                        System.err.println("MMLF: skipped "+file.getFileName()+": "+result.loader()+" ("+result.detail()+")");
                    continue;
                }
                Files.copy(file,destination.resolve(file.getFileName()),StandardCopyOption.REPLACE_EXISTING);
                System.out.println("MMLF: staged "+file.getFileName()+" to "+loader+"; loader checks dependencies at startup");
            }
        }
    }

    @Override public synchronized void close() {
        if(closed) return; closed=true;
        List<Process> copy; synchronized(children) { copy=new ArrayList<>(children); }
        for(Process process:copy) destroyTree(process);
        try { coordinator.stop(); } catch(Exception ignored) { }
        MmlfLog.info("launcher","shutdown",Map.of());
    }
    static void destroyTree(Process process) {
        var descendants=process.descendants().toList();
        for(int i=descendants.size()-1;i>=0;i--) descendants.get(i).destroy();
        process.destroy();
        for(ProcessHandle child:descendants) {
            try { child.onExit().get(3,TimeUnit.SECONDS); }
            catch(Exception e) { if(child.isAlive()) child.destroyForcibly(); }
        }
        try { if(!process.waitFor(3,TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
