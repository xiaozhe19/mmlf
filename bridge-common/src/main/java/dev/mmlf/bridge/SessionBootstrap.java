package dev.mmlf.bridge;

import dev.mmlf.launcher.SessionHost;
import java.nio.file.*;
import java.util.*;

/**
 * Lets whichever client the player starts become the primary runtime: with no session configured, the client hosts
 * the Coordinator itself and starts every other known runtime in the background. Development builds only, because
 * the other runtimes are started through the project's Gradle run tasks (-Dmmlf.root names the project).
 */
final class SessionBootstrap {
    private static SessionHost host;
    private SessionBootstrap() {}

    /** Returns the session file to join, or null when this client should stay a plain, unbridged game. */
    static Path hostIfRequested(String runtime) {
        String root=System.getProperty("mmlf.root");
        if(root==null || root.isBlank() || "false".equalsIgnoreCase(System.getProperty("mmlf.autohost","true"))) return null;
        if(!SessionHost.RUN_TASKS.containsKey(runtime)) { System.err.println("MMLF: runtime "+runtime+" cannot host a session"); return null; }
        try {
            Path project=Path.of(root).toAbsolutePath().normalize();
            host=SessionHost.create(project,runtime,"true".equals(System.getProperty("mmlf.selfTest",System.getenv("MMLF_SELF_TEST"))));
            Runtime.getRuntime().addShutdownHook(new Thread(host::close,"mmlf-host-cleanup"));
            List<String> others=new ArrayList<>(new TreeSet<>(SessionHost.RUN_TASKS.keySet())); others.remove(runtime);
            host.launch(project,others);
            System.out.println("MMLF: hosting session as primary "+runtime+"; started "+others+" in "+host.directory());
            return host.sessionFile();
        } catch(Exception e) {
            System.err.println("MMLF: could not host a session: "+e);
            if(host!=null) host.close();
            host=null; return null;
        }
    }
    static boolean hosting() { return host!=null; }
    /** A release jar outside the development project: the session manager it carries starts the session instead. */
    static boolean release() {
        String root=System.getProperty("mmlf.root");
        return (root==null || root.isBlank()) && ReleaseSession.bundled();
    }
}
