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
        applyRemoteOverrides(prepared.mods(),prepared.game());
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
     * With the unified render layer, the remote only submits what it draws; mods that would draw past the generic
     * capture paths get the configuration the bundled {@code remote-overrides.json} names (e.g. Create's Flywheel off),
     * written into the remote's staged game directory after the instance's own config was copied there. The player's
     * instance itself is not touched. The development scripts apply the same file.
     */
    private static void applyRemoteOverrides(List<Path> mods,Path game) {
        if(!Experiments.GEOMETRY_REPLAY) return;
        try(InputStream in=ReleaseSession.class.getResourceAsStream("remote-overrides.json")) {
            if(in==null) return;
            var root=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> ids=new HashSet<>();
            for(Path mod:mods) ids.addAll(modIds(mod));
            for(var element:root.getAsJsonArray("overrides")) {
                var entry=element.getAsJsonObject();
                boolean matches=false;
                for(var id:entry.getAsJsonArray("mods")) matches|=ids.contains(id.getAsString());
                if(!matches) continue;
                for(var file:entry.getAsJsonObject("files").entrySet()) {
                    Path target=game.resolve(file.getKey()).normalize();
                    if(!target.startsWith(game)) continue;
                    Files.createDirectories(target.getParent());
                    Files.writeString(target,file.getValue().getAsString());
                    MmlfLog.info("launcher","remote_override",Map.of("file",file.getKey()));
                }
            }
        } catch(IOException | RuntimeException e) {
            MmlfLog.warn("launcher","remote_override_failed",Map.of("exception",e.toString()));
        }
    }
    private static final java.util.regex.Pattern NEOFORGE_MOD_ID=java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
    /** Mod ids a jar declares, for either loader; empty when it cannot be read. */
    private static Set<String> modIds(Path jar) {
        Set<String> ids=new HashSet<>();
        try(var zip=new java.util.zip.ZipFile(jar.toFile())) {
            var toml=zip.getEntry("META-INF/neoforge.mods.toml");
            if(toml!=null) try(var in=zip.getInputStream(toml)) {
                var m=NEOFORGE_MOD_ID.matcher(new String(in.readAllBytes(),StandardCharsets.UTF_8));
                while(m.find()) ids.add(m.group(1));
            }
            var fabric=zip.getEntry("fabric.mod.json");
            if(fabric!=null) try(var in=zip.getInputStream(fabric)) {
                var json=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                if(json.has("id")) ids.add(json.get("id").getAsString());
            }
        } catch(IOException | RuntimeException e) { /* not a readable mod jar */ }
        return ids;
    }

    /**
     * The launcher's {@code .minecraft}:the folder above the libraries on this game's classpath, else the nearest
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
