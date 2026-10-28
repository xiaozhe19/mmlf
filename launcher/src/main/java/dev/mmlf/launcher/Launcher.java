package dev.mmlf.launcher;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Developer launcher: owns precisely the processes it starts. Any runtime may be primary (--primary=<id>). */
public final class Launcher {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getProperty("mmlf.root", ".")).toAbsolutePath().normalize();
        Path wrapper=root.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"gradlew.bat":"gradlew");
        if(!Files.isRegularFile(wrapper)) throw new IllegalStateException("Launch from project root or set -Dmmlf.root");
        String primary=Arrays.stream(args).filter(a->a.startsWith("--primary=")).map(a->a.substring(10)).findFirst().orElse("fabric");
        SessionHost host=SessionHost.create(root,primary,Arrays.asList(args).contains("--self-test"));
        Runtime.getRuntime().addShutdownHook(new Thread(host::close,"mmlf-cleanup"));
        List<String> runtimes=new ArrayList<>(new TreeSet<>(SessionHost.RUN_TASKS.keySet()));
        Process runs=host.launch(root,runtimes);
        var coordinator=host.coordinator();
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
        if(!runs.isAlive()) {dev.mmlf.common.MmlfLog.info("launcher","process_exit",Map.of("pid",runs.pid(),"exitCode",runs.exitValue()));Files.writeString(host.directory().resolve("exit-status.json"),dev.mmlf.common.Wire.JSON.toJson(Map.of("time",java.time.Instant.now().toString(),"exitCode",runs.exitValue(),"pid",runs.pid())));}
        // Coordinator server threads are non-daemon; returning from main would leave the JVM, and with it the background runtimes, alive.
        dev.mmlf.common.MmlfLog.info("launcher","session_cleanup",Map.of("clientsAlive",runs.isAlive()));
        System.exit(0);
    }
}
