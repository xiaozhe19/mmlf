package dev.mmlf.launcher.runtime;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.launcher.SessionHost;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Each runtime in its own process: an installed game's command line, or the project's Gradle run tasks during
 * development. The child learns its session from its environment, and stops by itself once this process is gone.
 */
public final class ProcessBackend implements RuntimeBackend {
    private final SessionHost session;
    public ProcessBackend(SessionHost session) {
        this.session=Objects.requireNonNull(session);
        if(session.session().inJvm()) throw new IllegalArgumentException("process runtimes need the session's WebSocket endpoint");
    }
    public String name() { return "process"; }

    public RuntimeHandle start(RuntimeSpec spec) throws Exception {
        List<String> line; Path workingDirectory; List<String> runtimes;
        if(spec.command()!=null) { line=spec.command().line(); workingDirectory=spec.command().workingDirectory(); runtimes=spec.command().runtimes(); }
        else {
            boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            Path java=Path.of(System.getProperty("java.home"),"bin",windows?"javaw.exe":"java");
            line=spec.java().command(java,List.of()); workingDirectory=spec.gameDirectory(); runtimes=List.of(spec.id());
        }
        ProcessBuilder pb=new ProcessBuilder(line).directory(workingDirectory.toFile()).redirectErrorStream(true).redirectOutput(spec.log().toFile());
        pb.environment().put("MMLF_SESSION",session.sessionFile().toString());
        pb.environment().put("MMLF_GAME_ROOT",session.directory().toString());
        pb.environment().put("MMLF_HIDE_SECONDARY","true");
        // Background runtimes exit as soon as this process is gone, however it ended.
        pb.environment().put("MMLF_HOST_PID",Long.toString(ProcessHandle.current().pid()));
        Process child=pb.start();
        MmlfLog.info("launcher","process_start",Map.of("pid",child.pid(),"runtimes",runtimes,"log",spec.log().toString()));
        return new Handle(spec.id(),child);
    }

    private static final class Handle implements RuntimeHandle {
        private final String id; private final Process process;
        private final CompletableFuture<Exit> exit;
        Handle(String id,Process process) {
            this.id=id; this.process=process;
            exit=process.onExit().thenApply(p->{
                int code=p.exitValue();
                MmlfLog.info("launcher","process_exit",Map.of("pid",p.pid(),"exitCode",code));
                return new Exit(code,null);
            });
        }
        public String id() { return id; }
        public String backend() { return "process"; }
        public State state() { return process.isAlive()?State.RUNNING:process.exitValue()==0?State.EXITED:State.FAILED; }
        public CompletableFuture<Exit> exit() { return exit; }
        public long pid() { return process.pid(); }
        public void stop(Duration timeout) { destroyTree(process,timeout); }
    }

    /** Gradle on Windows runs through cmd: killing only the direct child would leave the game itself running. */
    static void destroyTree(Process process,Duration timeout) {
        var descendants=process.descendants().toList();
        for(int i=descendants.size()-1;i>=0;i--) descendants.get(i).destroy();
        process.destroy();
        long millis=Math.max(1,timeout.toMillis());
        for(ProcessHandle child:descendants) {
            try { child.onExit().get(millis,TimeUnit.MILLISECONDS); }
            catch(Exception e) { if(child.isAlive()) child.destroyForcibly(); }
        }
        try { if(!process.waitFor(millis,TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
