package dev.mmlf.bridge;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Wire;
import net.minecraft.client.Minecraft;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Release builds: the client a launcher started becomes the primary and starts the other runtime from the same
 * launcher's installed versions, inside its own JVM ({@link #hostInJvm}).
 */
final class ReleaseSession {
    /** {@code minecraft.launcher.brand} values of the launchers whose installed versions the manager can start. */
    private static final List<String> LAUNCHERS=List.of("minecraft-launcher","hmcl","pcl");
    private ReleaseSession() {}

    /** Release jars carry the session launcher; a development client without a session stays a plain game. */
    static boolean bundled() { return ReleaseSession.class.getResource("/dev/mmlf/launcher/ReleaseRemote.class")!=null; }

    /** Started once the game is up, because the account the background plays with is only known then. */
    static CompletableFuture<Path> start(Minecraft mc,String runtime) {
        String brand=System.getProperty("minecraft.launcher.brand","");
        String normalized=brand.toLowerCase(Locale.ROOT);
        if(LAUNCHERS.stream().noneMatch(normalized::startsWith))
            return CompletableFuture.failedFuture(new IllegalStateException("MMLF 只支持官方启动器、HMCL 和 PCL，当前启动器为「"+(brand.isEmpty()?"未知":brand)+"」"));
        Path root;
        try { root=minecraftRoot(mc.gameDirectory.toPath()); }
        catch(IllegalStateException e) { return CompletableFuture.failedFuture(e); }
        var user=mc.getUser();
        Path gameDirectory=mc.gameDirectory.toPath().toAbsolutePath().normalize();
        var account=new dev.mmlf.launcher.VersionProfile.Account(user.getName(),user.getProfileId().toString().replace("-",""),user.getAccessToken(),
            user.getType().getName(),user.getXuid().orElse(""),user.getClientId().orElse(""));
        String version=net.minecraft.SharedConstants.getCurrentVersion().getName();
        Map<String,String> launcher=Map.of("name",brand,"version",System.getProperty("minecraft.launcher.version","unknown"));
        return CompletableFuture.supplyAsync(()->{
            try { return hostInJvm(runtime,version,root,gameDirectory,account,launcher); }
            catch(Exception e) { throw new java.util.concurrent.CompletionException(e); }
        });
    }

    /**
     * In-JVM release session: this game hosts the session itself and starts the other loader's installed version
     * inside its own JVM, as development runs do ({@link dev.mmlf.launcher.ReleaseRemote} stages it).
     */
    private static Path hostInJvm(String primary,String minecraftVersion,Path root,Path gameDirectory,dev.mmlf.launcher.VersionProfile.Account account,Map<String,String> launcher) throws Exception {
        long started=System.currentTimeMillis();
        var prepared=dev.mmlf.launcher.ReleaseRemote.prepare(root,primary,minecraftVersion,gameDirectory,(f,t)->SessionStatus.managerStep(f.floatValue(),t));
        Path sessions=gameDirectory.resolve("mmlf").resolve("sessions");
        dev.mmlf.launcher.ReleaseSupport.removeOldSessions(sessions);
        var host=dev.mmlf.launcher.SessionHost.create(sessions,null,primary,false);
        var manager=new dev.mmlf.launcher.runtime.RuntimeManager(host,new dev.mmlf.launcher.runtime.InJvmBackend(host));
        try {
            SessionBootstrap.adopt(manager);
            host.suppressEarlyWindow(prepared.remote(),prepared.game());
            dev.mmlf.launcher.ReleaseSupport.reportExit(manager,prepared.remote(),prepared.game(),started);
            SessionStatus.managerStep(0.9f,"启动 "+prepared.profile().id());
            MmlfLog.info("launcher","remote_version",Map.of("runtime",prepared.remote(),"version",prepared.profile().id(),"loaderVersion",prepared.profile().loaderVersion(),
                "mods",prepared.mods().size(),"instance",prepared.instance().toString(),"backend","injvm"));
            manager.start(dev.mmlf.launcher.ReleaseRemote.inJvm(prepared,host.directory(),account,launcher));
        } catch(Exception e) { manager.close(); throw e; }
        return host.sessionFile();
    }

    /**
     * The launcher's {@code .minecraft}: the folder above the libraries on this game's classpath, else the nearest
     * folder above the game directory holding both versions and libraries (a version-isolated game sits inside it).
     */
    static Path minecraftRoot(Path gameDirectory) {
        String classpath=System.getProperty("java.class.path","")+File.pathSeparator+System.getProperty("legacyClassPath","");
        for(String entry:classpath.split(File.pathSeparator)) {
            Path file;
            try { file=Path.of(entry).toAbsolutePath(); } catch(InvalidPathException e) { continue; }
            if(entry.isBlank()) continue;
            for(Path p=file.getParent(); p!=null; p=p.getParent())
                if(p.getFileName()!=null && p.getFileName().toString().equals("libraries") && p.getParent()!=null && Files.isDirectory(p.getParent().resolve("versions")))
                    return p.getParent().normalize();
        }
        for(Path p=gameDirectory.toAbsolutePath().normalize(); p!=null; p=p.getParent())
            if(Files.isDirectory(p.resolve("versions")) && Files.isDirectory(p.resolve("libraries"))) return p;
        throw new IllegalStateException("找不到启动器的 .minecraft 目录（需要包含 versions 和 libraries）");
    }
}
