package dev.mmlf.launcher;

import dev.mmlf.common.Session;
import dev.mmlf.coordinator.Coordinator;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Developer launcher: owns precisely the processes it starts. */
public final class Launcher {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getProperty("mmlf.root", ".")).toAbsolutePath().normalize();
        Path wrapper=root.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"gradlew.bat":"gradlew");
        if(!Files.isRegularFile(wrapper)) throw new IllegalStateException("Launch from project root or set -Dmmlf.root");
        Files.createDirectories(root.resolve("runtime"));
        Path dir=Files.createTempDirectory(root.resolve("runtime"),"session-");
        dev.mmlf.common.MmlfLog.configure("manager",dir,dev.mmlf.common.MmlfLog.configuredLevel());
        dev.mmlf.common.MmlfLog.info("launcher","session_created",Map.of("directory",dir.toString()));
        Runtime.getRuntime().addShutdownHook(new Thread(()->dev.mmlf.common.MmlfLog.info("launcher","shutdown",Map.of()),"mmlf-log-shutdown"));
        Files.writeString(root.resolve("runtime/latest-session.txt"),dir.toString());
        byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
        String token=HexFormat.of().formatHex(secret);
        Coordinator coordinator=new Coordinator(0,token);
        List<Process> children=new ArrayList<>();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            for (Process process : children) destroyTree(process);
            try { coordinator.stop(); } catch(Exception ignored) { }
        },"mmlf-cleanup"));
        coordinator.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(coordinator.getPort()==0 && System.nanoTime()<deadline) Thread.sleep(20);
        if(coordinator.getPort()==0) throw new IllegalStateException("Coordinator did not start");
        Files.writeString(dir.resolve("scenario.json"),"""
            {"placements":[{"runtime":"fabric","block":"mmlf_fabric_test:demo_block","x":-1,"y":100,"z":0},{"runtime":"neoforge","block":"mmlf_neoforge_test:demo_block","x":1,"y":100,"z":0}]}
            """);
        Path sceneConfig=root.resolve("config/demo-scene.json");
        if(Files.isRegularFile(sceneConfig)) Files.copy(sceneConfig,dir.resolve("scenario.json"),StandardCopyOption.REPLACE_EXISTING);
        if(Arrays.asList(args).contains("--self-test")) Files.createFile(dir.resolve("self-test"));
        Path sessionFile=dir.resolve("session.json");
        new Session("ws://127.0.0.1:"+coordinator.getPort(),token,dir.toString()).write(sessionFile);
        Path fabric=dir.resolve("fabric"), neo=dir.resolve("neoforge");
        Files.createDirectories(fabric.resolve("mods")); Files.createDirectories(neo.resolve("mods"));
        prepareMods(root.resolve("mmlf/mods"),fabric.resolve("mods"),neo.resolve("mods"));
        Process runs=launch(root,wrapper,sessionFile,dir);
        children.add(runs);
        long startupDeadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
        while(!runs.waitFor(1,TimeUnit.SECONDS)) {
            if(!coordinator.hadPrimary() && System.nanoTime()>startupDeadline) {
                System.err.println("MMLF primary did not register in ten minutes; stopping session");
                break;
            }
            if(coordinator.hadPrimary() && !coordinator.hasPrimary()) {
                System.out.println("MMLF primary disconnected; stopping session");
                break;
            }
        }
        if(!runs.isAlive()) {dev.mmlf.common.MmlfLog.info("launcher","process_exit",Map.of("pid",runs.pid(),"exitCode",runs.exitValue()));Files.writeString(dir.resolve("exit-status.json"),dev.mmlf.common.Wire.JSON.toJson(Map.of("time",java.time.Instant.now().toString(),"exitCode",runs.exitValue(),"pid",runs.pid())));}
        // Coordinator server threads are non-daemon; returning from main would leave the JVM, and with it the background runtimes, alive.
        dev.mmlf.common.MmlfLog.info("launcher","session_cleanup",Map.of("clientsAlive",runs.isAlive()));
        System.exit(0);
    }
    private static void destroyTree(Process process) {
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
    private static void prepareMods(Path mods,Path fabric,Path neo) throws Exception {
        Files.createDirectories(mods);
        try(var paths=Files.list(mods)) {
            for(Path file:paths.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                var result=ModClassifier.classify(file);
                Path destination=switch(result.loader()) { case FABRIC -> fabric; case NEOFORGE -> neo; default -> null; };
                if(destination==null) {
                    System.err.println("MMLF: skipped "+file.getFileName()+": "+result.loader()+" ("+result.detail()+")");
                    continue;
                }
                Files.copy(file,destination.resolve(file.getFileName()));
                System.out.println("MMLF: staged "+file.getFileName()+" to "+result.loader()+"; loader checks dependencies at startup");
            }
        }
    }
    private static Process launch(Path root,Path wrapper,Path session,Path gameRoot) throws Exception {
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> command=windows?List.of("cmd.exe","/d","/c",wrapper.toString(),"--parallel",":fabric-bridge:runClient",":neoforge-bridge:runClient"):
            List.of(wrapper.toString(),"--parallel",":fabric-bridge:runClient",":neoforge-bridge:runClient");
        ProcessBuilder pb=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(gameRoot.resolve("clients.log").toFile());
        pb.environment().put("MMLF_SESSION",session.toString());
        pb.environment().put("MMLF_GAME_ROOT",gameRoot.toString());
        pb.environment().put("MMLF_HIDE_SECONDARY","true");
        Process child=pb.start();
        dev.mmlf.common.MmlfLog.info("launcher","process_start",Map.of("pid",child.pid(),"log",gameRoot.resolve("clients.log").toString()));
        return child;
    }
}

