package dev.mmlf.launcher;

import dev.mmlf.launcher.runtime.*;
import java.nio.file.*;
import java.util.*;

/**
 * Developer launcher: starts only the primary's Gradle client, which hosts the session and runs the other runtime
 * inside its own JVM. Any runtime may be primary ({@code --primary=<id>}).
 */
public final class Launcher {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getProperty("mmlf.root", ".")).toAbsolutePath().normalize();
        Path wrapper=root.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"gradlew.bat":"gradlew");
        if(!Files.isRegularFile(wrapper)) throw new IllegalStateException("Launch from project root or set -Dmmlf.root");
        String primary=Arrays.stream(args).filter(a->a.startsWith("--primary=")).map(a->a.substring(10)).findFirst().orElse("fabric");
        boolean selfTest=Arrays.asList(args).contains("--self-test");
        System.exit(runPrimary(root,primary,selfTest));
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
        if(selfTest) pb.environment().put("MMLF_SELF_TEST","true");
        Process client=pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{ client.descendants().forEach(ProcessHandle::destroy); client.destroy(); },"mmlf-cleanup"));
        int code=client.waitFor();
        Files.writeString(logs.resolve("exit-status.json"),dev.mmlf.common.Wire.JSON.toJson(Map.of("time",java.time.Instant.now().toString(),"exitCode",code,"pid",client.pid())));
        return code;
    }
}
