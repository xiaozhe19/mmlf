package dev.mmlf.bridge.transport;

import dev.mmlf.bridge.platform.InJvmLwjgl;

import dev.mmlf.launcher.ReleaseSupport;
import dev.mmlf.launcher.SessionHost;
import dev.mmlf.launcher.runtime.*;
import java.nio.file.*;
import java.util.*;

/**
 * Lets whichever client the player starts become the primary runtime: with no session configured, the client hosts
 * the session itself and starts every other known runtime inside this JVM through their loaders' native launches.
 * Development builds only (-Dmmlf.root names the project); release builds start their session in {@link ReleaseSession}.
 */
public final class SessionBootstrap {
    private static RuntimeManager manager;
    private SessionBootstrap() {}

    /** Returns the session file to join, or null when this client should stay a plain, unbridged game. */
    public static Path hostIfRequested(String runtime) {
        String root=System.getProperty("mmlf.root");
        if(root==null || root.isBlank() || "false".equalsIgnoreCase(System.getProperty("mmlf.autohost","true"))) return null;
        if(!DevRunSpecs.RUN_TASKS.containsKey(runtime)) { System.err.println("MMLF: runtime "+runtime+" cannot host a session"); return null; }
        try {
            Path project=Path.of(root).toAbsolutePath().normalize();
            SessionHost host=SessionHost.create(project,runtime,"true".equals(System.getProperty("mmlf.selfTest",System.getenv("MMLF_SELF_TEST"))));
            adopt(new RuntimeManager(host,new InJvmBackend(host)));
            List<String> others=new ArrayList<>(new TreeSet<>(DevRunSpecs.RUN_TASKS.keySet())); others.remove(runtime);
            long started=System.currentTimeMillis();
            for(String other:others) ReleaseSupport.reportExit(manager,other,SessionHost.gameDirectory(project,host,other),started);
            if(solo()) System.out.println("MMLF: solo baseline (MMLF_SOLO); no other runtime started");
            else for(String other:others) manager.start(DevRunSpecs.inJvm(host,project,other));
            System.out.println("MMLF: hosting session as primary "+runtime+"; started "+(solo()?List.of():others)+" in "+host.directory());
            testDeadline(manager);
            return host.sessionFile();
        } catch(Exception e) {
            System.err.println("MMLF: could not host a session: "+e);
            e.printStackTrace();
            if(manager!=null) manager.close();
            manager=null; return null;
        }
    }
    /**
     * Unattended development runs ({@code MMLF_TEST_SECONDS=n}): the primary closes itself the normal way after n seconds,
     * or as soon as a runtime it started has failed, so a test run never leaves a game behind.
     */
    private static void testDeadline(RuntimeManager manager) {
        String value=System.getenv("MMLF_TEST_SECONDS");
        if(value==null || value.isBlank()) return;
        long seconds=Long.parseLong(value.trim());
        Runnable close=()->{ var mc=net.minecraft.client.Minecraft.getInstance(); if(mc!=null) mc.execute(mc::stop); };
        manager.onExit((handle,exit)->{ if(exit.failed()) { System.out.println("MMLF test: "+handle.id()+" failed, closing"); close.run(); } });
        Thread timer=new Thread(()->{
            try { Thread.sleep(seconds*1000); } catch(InterruptedException e) { return; }
            System.out.println("MMLF test: "+seconds+" s elapsed, closing"); close.run();
        },"mmlf-test-deadline");
        timer.setDaemon(true); timer.start();
    }
    static boolean hosting() { return manager!=null; }
    /**
     * Development baseline ({@code MMLF_SOLO=true}): this client hosts its session and opens the demo world alone, with
     * no other runtime; the same world, mods and measurements as a two-runtime run, for comparison.
     */
    static boolean solo() { return "true".equalsIgnoreCase(System.getenv("MMLF_SOLO")); }
    /**
     * This client now hosts {@code hosted}'s session (development or release): its runtimes stop before this game's
     * window closes ({@link #stopRuntimes}), or at the latest when the JVM ends.
     */
    static synchronized void adopt(RuntimeManager hosted) {
        if(manager!=null) throw new IllegalStateException("this client already hosts a session");
        manager=hosted;
        Runtime.getRuntime().addShutdownHook(new Thread(hosted::close,"mmlf-host-cleanup"));
        // A runtime that ended has released its copy of LWJGL's JVM-wide fallback table slot; make sure it holds ours.
        hosted.onExit((handle,exit)->InJvmLwjgl.verifyHostTable());
    }
    /**
     * Stops the runtimes this client hosts and ends the session; returns once they ended. One that did not end within
     * the wait gets this JVM halted ({@link #haltStalled}) rather than ended around it.
     */
    public static void stopRuntimes() {
        RuntimeManager m=manager;
        if(m==null) return;
        long started=System.nanoTime();
        m.close();
        List<String> alive=m.runtimes().stream().filter(RuntimeHandle::alive).map(RuntimeHandle::id).toList();
        dev.mmlf.common.MmlfLog.info("launcher","hosted_runtimes_stopped",Map.of("elapsedMs",(System.nanoTime()-started)/1_000_000,"alive",alive));
        if(!alive.isEmpty()) haltStalled(alive);
    }
    /**
     * A hosted runtime still running when this game closes: this game has saved its world (it closes after saving), and
     * the session's last save point was saved by both before the session ended. Ending the JVM normally around a game
     * still inside its own GL and native code crashed the process on exit (0xC0000409), which a launcher reports as a
     * crash. So the threads are written down for diagnosis, and the process ends at once with a clean exit code.
     * {@code -Dmmlf.haltStalled=false} keeps the normal exit.
     */
    private static void haltStalled(List<String> alive) {
        Path dump=dumpThreads("stop-stall");
        dev.mmlf.common.MmlfLog.warn("launcher","halt_with_stalled_runtimes",Map.of("alive",alive,"threads",String.valueOf(dump)));
        if("false".equalsIgnoreCase(System.getProperty("mmlf.haltStalled","true"))) return;
        System.out.flush(); System.err.flush();
        Runtime.getRuntime().halt(0);
    }
    /** Every thread of this JVM (all runtimes), with full stacks and the locks held and awaited, into the session's directory. */
    static Path dumpThreads(String name) {
        try {
            var session=SessionLink.session();
            Path dir=session!=null ? session.root() : Path.of(System.getProperty("java.io.tmpdir"));
            Path file=dir.resolve(name+"-"+System.currentTimeMillis()+".txt");
            var text=new StringBuilder();
            for(var info:java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true,true)) {
                text.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
                if(info.getLockName()!=null) text.append(" on ").append(info.getLockName());
                if(info.getLockOwnerName()!=null) text.append(" owned by \"").append(info.getLockOwnerName()).append('"');
                text.append('\n');
                var locked=info.getLockedMonitors();
                StackTraceElement[] stack=info.getStackTrace();
                for(int i=0;i<stack.length;i++) {
                    text.append("    at ").append(stack[i]).append('\n');
                    for(var monitor:locked) if(monitor.getLockedStackDepth()==i) text.append("    - locked ").append(monitor).append('\n');
                }
                for(var sync:info.getLockedSynchronizers()) text.append("    - holds ").append(sync).append('\n');
                text.append('\n');
            }
            Files.writeString(file,text);
            return file;
        } catch(Exception|LinkageError e) {
            dev.mmlf.common.MmlfLog.warn("launcher","thread_dump_failed",Map.of("exception",e.toString()));
            return null;
        }
    }
    /** A release jar outside the development project: {@link ReleaseSession} starts the session instead. */
    public static boolean release() {
        String root=System.getProperty("mmlf.root");
        return (root==null || root.isBlank()) && ReleaseSession.bundled();
    }
}
