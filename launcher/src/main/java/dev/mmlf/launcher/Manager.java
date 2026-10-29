package dev.mmlf.launcher;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Wire;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/**
 * Release session manager, started by the MMLF mod of the client a launcher started. It hosts the Coordinator and
 * starts the other runtime from the version the player installed with the same launcher, then lives until the primary
 * is gone. One request line on stdin; one reply line on stdout ({@code MMLF-SESSION <file>} or {@code MMLF-ERROR <text>}).
 */
public final class Manager {
    public record Request(String primary,String minecraftVersion,String minecraftRoot,String gameDirectory,String launcherName,String launcherVersion,
                          VersionProfile.Account account,long hostPid,long maxMemoryMb) {}
    private static final Map<String,VersionProfile.Loader> PROFILE_LOADERS=Map.of("fabric",VersionProfile.Loader.FABRIC,"neoforge",VersionProfile.Loader.NEOFORGE);
    private static final Map<String,String> NAMES=Map.of("fabric","Fabric","neoforge","NeoForge");
    /** Session directories kept besides the new one, for looking at a recent session's logs. */
    private static final int KEPT_SESSIONS=2;
    private Manager() {}

    public static void main(String[] args) {
        // stdout carries only the reply, in UTF-8 whatever the console's code page; log lines go to stderr until then.
        PrintStream reply=new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8);
        System.setOut(System.err);
        SessionHost host=null;
        try {
            Request request=Wire.JSON.fromJson(new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine(),Request.class);
            if(request==null || !PROFILE_LOADERS.containsKey(request.primary())) throw new IllegalStateException("无效的启动请求");
            String remote=request.primary().equals("fabric")?"neoforge":"fabric";
            Path root=Path.of(request.minecraftRoot());
            progress(reply,0.1,"查找已安装的 "+NAMES.get(remote)+" 版本");
            VersionProfile profile=remoteVersion(root,remote,request.minecraftVersion());
            Path instance=Files.isDirectory(root.resolve("versions").resolve(profile.id()).resolve("mods"))?root.resolve("versions").resolve(profile.id()):root;
            progress(reply,0.3,"检查 "+profile.id()+" 的模组");
            List<Path> mods=remoteMods(instance.resolve("mods"),remote);

            Path runtime=Path.of(request.gameDirectory()).resolve("mmlf").resolve("sessions");
            removeOldSessions(runtime);
            host=SessionHost.create(runtime,null,request.primary(),false);
            Runtime.getRuntime().addShutdownHook(new Thread(host::close,"mmlf-cleanup"));
            // Kept across sessions: the background's worlds for the player's saves live here.
            Path game=Path.of(request.gameDirectory()).resolve("mmlf").resolve(remote);
            long started=System.currentTimeMillis();
            progress(reply,0.5,"准备远端运行目录（"+mods.size()+" 个模组）");
            stage(mods,instance,game);
            host.suppressEarlyWindow(remote,game);
            boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            Path java=Path.of(System.getProperty("java.home"),"bin",windows?"javaw.exe":"java");
            List<String> extra=new ArrayList<>();
            if(request.maxMemoryMb()>0) extra.add("-Xmx"+request.maxMemoryMb()+"m");
            extra.add("-Dmmlf.session="+host.sessionFile());
            extra.add("-Dmmlf.runtime="+remote);
            extra.add("-Dmmlf.logLevel="+MmlfLog.configuredLevel());
            List<String> command=profile.command(java,game,game.resolve("natives"),request.account(),
                Map.of("name",request.launcherName(),"version",request.launcherVersion()),extra);
            MmlfLog.info("launcher","remote_version",Map.of("runtime",remote,"version",profile.id(),"loaderVersion",profile.loaderVersion(),"mods",mods.size(),"instance",instance.toString()));
            progress(reply,0.9,"启动 "+profile.id());
            Process child=host.launchInstalled(remote,command,game);

            reply.println("MMLF-SESSION "+host.sessionFile());
            reply.flush();
            // Nobody reads stdout after the reply; a full pipe would block every log line.
            PrintStream log=new PrintStream(new FileOutputStream(host.directory().resolve("manager.log").toFile(),true),true,StandardCharsets.UTF_8);
            System.setOut(log); System.setErr(log);
            supervise(host,request.hostPid(),child,remote,game,started);
        } catch(Throwable e) {
            String message=e.getMessage()==null?e.toString():e.getMessage();
            reply.println("MMLF-ERROR "+message.replace('\r',' ').replace('\n',' '));
            reply.flush();
            if(host!=null) host.close();
            System.exit(1);
        }
        MmlfLog.info("launcher","session_cleanup",Map.of());
        host.close();
        // Coordinator server threads are non-daemon; the JVM would otherwise outlive the session.
        System.exit(0);
    }

    /** A step before the reply, for the primary's progress panel; the reply line still ends the exchange. */
    private static void progress(PrintStream reply,double fraction,String text) {
        reply.println("MMLF-PROGRESS "+fraction+" "+text.replace('\r',' ').replace('\n',' '));
        reply.flush();
    }

    /** The newest installed version of the other loader on the same Minecraft version, complete on disk. */
    static VersionProfile remoteVersion(Path root,String remote,String minecraftVersion) throws IOException {
        List<VersionProfile> found=VersionProfile.find(root,PROFILE_LOADERS.get(remote),minecraftVersion);
        if(found.isEmpty()) throw new IllegalStateException("在 "+root.resolve("versions")+" 中没有找到 Minecraft "+minecraftVersion+" 的 "+NAMES.get(remote)
            +" 版本。请先用同一个启动器安装它，并在其中放入 MMLF "+NAMES.get(remote)+" 模组。");
        VersionProfile profile=found.getFirst();
        List<Path> missing=profile.missingFiles();
        if(!missing.isEmpty()) throw new IllegalStateException("版本 "+profile.id()+" 缺少 "+missing.size()+" 个文件（如 "+missing.getFirst()
            +"）。请在启动器里单独启动一次该版本以补全文件。");
        return profile;
    }

    /** The other loader's mods from the player's instance; the MMLF mod for that loader must be among them. */
    static List<Path> remoteMods(Path directory,String remote) throws IOException {
        ModClassifier.Loader loader=SessionHost.LOADERS.get(remote);
        List<Path> mods=new ArrayList<>();
        boolean bridge=false;
        if(Files.isDirectory(directory)) try(var files=Files.list(directory)) {
            for(Path file:files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList()) {
                if(ModClassifier.classify(file).loader()!=loader) continue;
                mods.add(file);
                try(ZipFile zip=new ZipFile(file.toFile())) { bridge|=zip.getEntry("dev/mmlf/bridge/BridgeCore.class")!=null; }
            }
        }
        if(!bridge) throw new IllegalStateException(directory+" 中没有 MMLF "+NAMES.get(remote)+" 模组。请把它放进远端版本的 mods 文件夹。");
        return mods;
    }

    /** The background plays in a directory of its own, so the player's own instance keeps its options and saves. */
    static void stage(List<Path> mods,Path instance,Path game) throws IOException {
        Path config=instance.resolve("config");
        Path target=game.resolve("mods");
        Files.createDirectories(target);
        // The instance's mods may have changed since the last session; links and copies are rebuilt every time.
        try(var old=Files.list(target)) { for(Path file:old.filter(Files::isRegularFile).toList()) Files.delete(file); }
        for(Path mod:mods) {
            Path destination=target.resolve(mod.getFileName());
            try { Files.createLink(destination,mod); }
            catch(IOException | UnsupportedOperationException e) { Files.copy(mod,destination,StandardCopyOption.REPLACE_EXISTING); }
        }
        options(instance.resolve("options.txt"),game.resolve("options.txt"));
        if(!Files.isDirectory(config)) return;
        try(var files=Files.walk(config)) {
            for(Path file:files.toList()) {
                Path destination=game.resolve("config").resolve(config.relativize(file).toString());
                if(Files.isDirectory(file)) Files.createDirectories(destination);
                else Files.copy(file,destination,StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** The player's options for that instance, without the first-run accessibility screen a hidden window could never leave. */
    static void options(Path source,Path destination) throws IOException {
        List<String> lines=new ArrayList<>();
        if(Files.isRegularFile(source)) for(String line:Files.readAllLines(source,StandardCharsets.UTF_8)) if(!line.startsWith("onboardAccessibility:")) lines.add(line);
        lines.add("onboardAccessibility:false");
        Files.write(destination,lines,StandardCharsets.UTF_8);
    }

    /** Keeps the newest few sessions; a directory still in use by a running game simply fails to delete. */
    static void removeOldSessions(Path runtime) {
        if(!Files.isDirectory(runtime)) return;
        try(var directories=Files.list(runtime)) {
            List<Path> sessions=directories.filter(Files::isDirectory).filter(p->p.getFileName().toString().startsWith("session-"))
                .sorted(Comparator.comparing((Path p)->p.toFile().lastModified()).reversed()).toList();
            for(Path old:sessions.subList(Math.min(KEPT_SESSIONS,sessions.size()),sessions.size())) {
                try(var files=Files.walk(old)) {
                    for(Path file:files.sorted(Comparator.reverseOrder()).toList()) try { Files.delete(file); } catch(IOException ignored) { }
                }
            }
        } catch(IOException ignored) { }
    }

    /** Written to the session directory when the background runtime exits; the primary shows it as its error. */
    public static final String REMOTE_ERROR="remote-error.txt";
    /** The newest crash report's description and root cause, else the exit code; always names where to look. */
    static String remoteFailure(String name,int exitCode,Path game,Path log,long since) {
        String reason="进程退出，退出码 "+exitCode;
        Path where=log;
        Path reports=game.resolve("crash-reports");
        if(Files.isDirectory(reports)) try(var files=Files.list(reports)) {
            Path report=files.filter(p->p.getFileName().toString().endsWith(".txt") && p.toFile().lastModified()>=since).max(Comparator.comparing((Path p)->p.toFile().lastModified())).orElse(null);
            if(report!=null) {
                where=report;
                String description=null, cause=null;
                for(String line:Files.readAllLines(report,StandardCharsets.UTF_8)) {
                    if(line.startsWith("Description: ") && description==null) description=line.substring(13).trim();
                    if(line.startsWith("Caused by: ")) cause=line.substring(11).trim();
                    // The stack traces that follow repeat the first one.
                    if(line.startsWith("A detailed walkthrough")) break;
                }
                if(cause==null) for(String line:Files.readAllLines(report,StandardCharsets.UTF_8)) if(line.contains("Exception") || line.contains("Error")) { cause=line.trim(); break; }
                reason=(description==null?"崩溃":description)+(cause==null?"":"："+cause);
            }
        } catch(IOException | RuntimeException ignored) { }
        if(reason.length()>600) reason=reason.substring(0,600)+"…";
        return "远端 "+name+" 启动失败（"+reason+"）。详见 "+where;
    }

    /** Ends the session when the primary's process exits or it leaves the Coordinator. */
    private static void supervise(SessionHost host,long hostPid,Process child,String remote,Path game,long started) throws InterruptedException {
        ProcessHandle primary=ProcessHandle.of(hostPid).orElse(null);
        var coordinator=host.coordinator();
        long startupDeadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
        boolean childReported=false;
        while(true) {
            Thread.sleep(1000);
            if(primary==null || !primary.isAlive()) { MmlfLog.info("launcher","primary_exited",Map.of("pid",hostPid)); return; }
            if(coordinator.hadPrimary() && !coordinator.hasPrimary()) { MmlfLog.info("launcher","primary_disconnected",Map.of()); return; }
            if(!coordinator.hadPrimary() && System.nanoTime()>startupDeadline) { MmlfLog.warn("launcher","primary_registration_timeout",Map.of()); return; }
            if(!childReported && !child.isAlive()) {
                childReported=true;
                Path log=host.directory().resolve(remote+".log");
                MmlfLog.error("launcher","remote_exited",Map.of("pid",child.pid(),"exitCode",child.exitValue(),"log",log.toString()));
                // The primary waits for this runtime's world; it reads the reason instead of waiting forever.
                try { Files.writeString(host.directory().resolve(REMOTE_ERROR),remoteFailure(NAMES.get(remote),child.exitValue(),game,log,started),StandardCharsets.UTF_8); }
                catch(IOException e) { MmlfLog.error("launcher","remote_error_write_failed",Map.of("exception",e.toString())); }
            }
        }
    }
}
