package dev.mmlf.launcher;

import dev.mmlf.launcher.runtime.JavaLaunch;
import dev.mmlf.launcher.runtime.RuntimeSpec;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * The other runtime of a release session, from what the player installed with their launcher: the newest version of the
 * other loader for the same Minecraft version, its instance's mods (with the MMLF mod among them), staged into a game
 * directory of its own. Shared by both backends: the session manager process starts it as a command, a primary that
 * hosts in its own JVM starts it as a {@link JavaLaunch}. Nothing here downloads or installs anything.
 */
public final class ReleaseRemote {
    private ReleaseRemote() {}

    /**
     * @param remote runtime id and loader ({@code fabric} or {@code neoforge})
     * @param profile the installed version that runs it
     * @param instance the player's instance of that version (its own folder when version-isolated, else the root)
     * @param mods the mods staged for it
     * @param game its game directory, kept across sessions (the background's worlds live there)
     */
    public record Prepared(String remote,VersionProfile profile,Path instance,List<Path> mods,Path game) {}

    public static String remoteOf(String primary) { return primary.equals("fabric")?"neoforge":"fabric"; }

    /** Finds and stages the remote; {@code progress} gets a fraction and a step for the player's progress panel. */
    public static Prepared prepare(Path minecraftRoot,String primary,String minecraftVersion,Path gameDirectory,BiConsumer<Double,String> progress) throws IOException {
        String remote=remoteOf(primary);
        String name=remote.equals("fabric")?"Fabric":"NeoForge";
        progress.accept(0.1,"查找已安装的 "+name+" 版本");
        VersionProfile profile=ReleaseSupport.remoteVersion(minecraftRoot,remote,minecraftVersion);
        Path isolated=minecraftRoot.resolve("versions").resolve(profile.id());
        Path instance=Files.isDirectory(isolated.resolve("mods"))?isolated:minecraftRoot;
        progress.accept(0.3,"检查 "+profile.id()+" 的模组");
        List<Path> mods=ReleaseSupport.remoteMods(instance.resolve("mods"),remote);
        Path game=gameDirectory.resolve("mmlf").resolve(remote);
        progress.accept(0.5,"准备远端运行目录（"+mods.size()+" 个模组）");
        ReleaseSupport.stage(mods,instance,game);
        return new Prepared(remote,profile,instance,mods,game);
    }

    /** Directory for the remote's native libraries; LWJGL extracts its own copy there (one per runtime in a JVM). */
    public static Path natives(Prepared prepared) { return prepared.game().resolve("natives"); }

    /**
     * The remote as a launch inside the primary's JVM: the installed version's own command line, read back into its
     * parts. No {@code -Dmmlf.*} identity (the in-JVM backend's registry tells the runtime who it is), no heap options
     * (the heap is the primary's). LWJGL extracts its natives to the runtime's own directory: two copies of one library
     * file cannot be loaded by two class loaders.
     */
    public static RuntimeSpec inJvm(Prepared prepared,Path sessionDirectory,VersionProfile.Account account,Map<String,String> launcher) {
        Path natives=natives(prepared);
        List<String> line=prepared.profile().command(Path.of("java"),prepared.game(),natives,account,launcher,List.of());
        JavaLaunch launch=JavaLaunch.parse(line.subList(1,line.size()));
        List<String> options=new ArrayList<>();
        for(String option:launch.jvmOptions()) if(!option.startsWith("-Xmx") && !option.startsWith("-Xms") && !option.startsWith("-Xss")) options.add(option);
        Map<String,String> properties=new LinkedHashMap<>(launch.properties());
        properties.keySet().removeIf(k->k.startsWith("mmlf."));
        properties.put("org.lwjgl.system.SharedLibraryExtractPath",natives.toString());
        launch=new JavaLaunch(launch.mainClass(),launch.classpath(),launch.modulePath(),properties,options,launch.args());
        return RuntimeSpec.java(prepared.remote(),prepared.remote(),prepared.game(),sessionDirectory.resolve(prepared.remote()+".log"),launch);
    }
}
