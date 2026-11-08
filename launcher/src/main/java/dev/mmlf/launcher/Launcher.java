package dev.mmlf.launcher;

import dev.mmlf.launcher.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Developer launcher. In-JVM (the default) it starts only the primary's Gradle client, which hosts the session and runs
 * the other runtime inside its own JVM; with {@code -Dmmlf.backend=process} it hosts the session itself and starts every
 * runtime as a separate Gradle client. Any runtime may be primary ({@code --primary=<id>}).
 */
public final class Launcher {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getProperty("mmlf.root", ".")).toAbsolutePath().normalize();
        Path wrapper=root.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"gradlew.bat":"gradlew");
        if(!Files.isRegularFile(wrapper)) throw new IllegalStateException("Launch from project root or set -Dmmlf.root");
        String primary=Arrays.stream(args).filter(a->a.startsWith("--primary=")).map(a->a.substring(10)).findFirst().orElse("fabric");
        boolean selfTest=Arrays.asList(args).contains("--self-test");
        if(RuntimeManager.configuredBackend().equals(RuntimeManager.IN_JVM)) System.exit(runPrimary(root,primary,selfTest));
        SessionHost host=SessionHost.create(root,primary,selfTest,SessionHost.Endpoint.WEBSOCKET);
        RuntimeManager manager=new RuntimeManager(host,new ProcessBackend(host));
        Runtime.getRuntime().addShutdownHook(new Thread(manager::close,"mmlf-cleanup"));
        List<String> runtimes=new ArrayList<>(new TreeSet<>(DevRunSpecs.RUN_TASKS.keySet()));
        RuntimeHandle runs=manager.start(DevRunSpecs.gradle(host,root,runtimes));
        manager.supervise(()->true,Duration.ofMinutes(10),true);
        if(!runs.alive()) {
            var exit=runs.exit().join();
            Files.writeString(host.directory().resolve("exit-status.json"),dev.mmlf.common.Wire.JSON.toJson(Map.of("time",java.time.Instant.now().toString(),"exitCode",exit.code())));
        }
        dev.mmlf.common.MmlfLog.info("launcher","session_cleanup",Map.of("clientsAlive",runs.alive()));
        manager.close();
        // Coordinator server threads are non-daemon; returning from main would leave the JVM, and with it the background runtimes, alive.
        System.exit(0);
    }

    /** One Gradle client that hosts the session in its own JVM; its exit code is the launcher's. */
    private static int runPrimary(Path root,String primary,boolean selfTest) throws Exception {
        String task=DevRunSpecs.RUN_TASKS.get(primary);
        if(task==null) throw new IllegalArgumentException("unknown primary runtime "+primary);
        Path logs=root.resolve("runtime"); Files.createDirectories(logs);
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> command=new ArrayList<>(windows?List.of("cmd.exe","/d","/c",root.resolve("gradlew.bat").toString()):List.of(root.resolve("gradlew").toString()));
        command.add(task);
        ProcessBuilder pb=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(logs.resolve("host-"+primary+"-console.log").toFile());
        pb.environment().remove("MMLF_SESSION");
        pb.environment().put("MMLF_BACKEND",RuntimeManager.IN_JVM);
        if(selfTest) pb.environment().put("MMLF_SELF_TEST","true");
        Process client=pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{ client.descendants().forEach(ProcessHandle::destroy); client.destroy(); },"mmlf-cleanup"));
        int code=client.waitFor();
        Files.writeString(logs.resolve("exit-status.json"),dev.mmlf.common.Wire.JSON.toJson(Map.of("time",java.time.Instant.now().toString(),"exitCode",code,"pid",client.pid())));
        return code;
    }
}
