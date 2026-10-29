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
 * Release builds: the client a launcher started becomes the primary. Its mod jar carries the session manager, which is
 * started beside the game and starts the other runtime from the same launcher's installed versions.
 */
final class ReleaseSession {
    static final String MANAGER="/META-INF/mmlf/mmlf-manager.jar";
    /** {@code minecraft.launcher.brand} values of the launchers whose installed versions the manager can start. */
    private static final List<String> LAUNCHERS=List.of("minecraft-launcher","hmcl","pcl");
    private ReleaseSession() {}

    /** Only release jars carry the manager; a development client without a session stays a plain game. */
    static boolean bundled() { return ReleaseSession.class.getResource(MANAGER)!=null; }

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
        var request=new LinkedHashMap<String,Object>();
        request.put("primary",runtime);
        request.put("minecraftVersion",net.minecraft.SharedConstants.getCurrentVersion().getName());
        request.put("minecraftRoot",root.toString());
        request.put("gameDirectory",mc.gameDirectory.toPath().toAbsolutePath().normalize().toString());
        request.put("launcherName",brand);
        request.put("launcherVersion",System.getProperty("minecraft.launcher.version","unknown"));
        request.put("account",Map.of("name",user.getName(),"uuid",user.getProfileId().toString().replace("-",""),"accessToken",user.getAccessToken(),
            "type",user.getType().getName(),"xuid",user.getXuid().orElse(""),"clientId",user.getClientId().orElse("")));
        request.put("hostPid",ProcessHandle.current().pid());
        request.put("maxMemoryMb",Runtime.getRuntime().maxMemory()/(1024*1024));
        return CompletableFuture.supplyAsync(()->{
            try { return run(Path.of(request.get("gameDirectory").toString()),Wire.JSON.toJson(request)); }
            catch(IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private static Path run(Path gameDirectory,String request) throws IOException {
        Path directory=gameDirectory.resolve("mmlf");
        Files.createDirectories(directory);
        Path jar=directory.resolve("mmlf-manager.jar");
        try(InputStream in=ReleaseSession.class.getResourceAsStream(MANAGER)) { Files.copy(in,jar,StandardCopyOption.REPLACE_EXISTING); }
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        // javaw: a console java started from the windowless game would open a console window.
        Path java=Path.of(System.getProperty("java.home"),"bin",windows?"javaw.exe":"java");
        Process manager=new ProcessBuilder(java.toString(),"-Dmmlf.logLevel="+MmlfLog.configuredLevel(),"-cp",jar.toString(),"dev.mmlf.launcher.Manager")
            .directory(directory.toFile()).redirectError(directory.resolve("manager-error.log").toFile()).start();
        try(Writer in=new OutputStreamWriter(manager.getOutputStream(),StandardCharsets.UTF_8)) { in.write(request); in.write('\n'); }
        BufferedReader out=new BufferedReader(new InputStreamReader(manager.getInputStream(),StandardCharsets.UTF_8));
        String reply;
        while((reply=out.readLine())!=null) {
            if(reply.startsWith(PROGRESS)) { progress(reply.substring(PROGRESS.length())); continue; }
            if(reply.startsWith("MMLF-")) break;
        }
        if(reply!=null && reply.startsWith("MMLF-SESSION ")) {
            MmlfLog.info("launcher","manager_started",Map.of("pid",manager.pid()));
            return Path.of(reply.substring("MMLF-SESSION ".length()));
        }
        if(reply!=null && reply.startsWith("MMLF-ERROR ")) throw new IllegalStateException(reply.substring("MMLF-ERROR ".length()));
        try { manager.waitFor(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        throw new IllegalStateException("MMLF 会话管理器没有正常启动，详见 "+directory.resolve("manager-error.log"));
    }

    private static final String PROGRESS="MMLF-PROGRESS ";
    /** {@code <fraction> <text>} from the manager, shown while it prepares the remote. */
    private static void progress(String line) {
        int space=line.indexOf(' ');
        try { SessionStatus.managerStep(Float.parseFloat(space<0?line:line.substring(0,space)),space<0?"":line.substring(space+1)); }
        catch(NumberFormatException ignored) { }
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
