package dev.mmlf.launcher;

import dev.mmlf.launcher.runtime.JavaLaunch;
import dev.mmlf.launcher.runtime.RuntimeSpec;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A release session's other runtime from a launcher's installation, shaped like real version files (vanilla base,
 * loader version inheriting from it): staged once, then launched in the primary's JVM or as a process.
 */
class ReleaseRemoteTest {
    @TempDir Path root;
    private static final String VANILLA="""
        {"id":"1.21.1","type":"release","mainClass":"net.minecraft.client.main.Main","assetIndex":{"id":"17"},
         "libraries":[{"name":"org.ow2.asm:asm:9.6"},{"name":"org.lwjgl:lwjgl:3.3.3"}],
         "arguments":{
          "game":["--username","${auth_player_name}","--version","${version_name}","--gameDir","${game_directory}","--assetsDir","${assets_root}",
                  "--assetIndex","${assets_index_name}","--uuid","${auth_uuid}","--accessToken","${auth_access_token}","--userType","${user_type}"],
          "jvm":["-Djava.library.path=${natives_directory}","-Djna.tmpdir=${natives_directory}","-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives_directory}",
                 "-Dminecraft.launcher.brand=${launcher_name}","-Dminecraft.launcher.version=${launcher_version}",
                 {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"},
                 "-Xss1M","-cp","${classpath}"]}}
        """;
    private static final String NEOFORGE="""
        {"id":"neoforge-21.1.265","inheritsFrom":"1.21.1","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
         "libraries":[{"name":"org.ow2.asm:asm:9.7"},{"name":"cpw.mods:bootstraplauncher:2.0.2"},{"name":"cpw.mods:securejarhandler:3.0.8"},{"name":"net.neoforged:neoforge:21.1.265:universal"}],
         "arguments":{"game":["--fml.neoForgeVersion","21.1.265","--fml.mcVersion","1.21.1","--launchTarget","forgeclient"],
          "jvm":["-Djava.net.preferIPv6Addresses=system","-DignoreList=client-extra,neoforge-","-DlibraryDirectory=${library_directory}",
                 "-p","${library_directory}/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar${classpath_separator}${library_directory}/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar${classpath_separator}${library_directory}/org/ow2/asm/asm/9.7/asm-9.7.jar",
                 "--add-modules","ALL-MODULE-PATH","--add-opens","java.base/java.util.jar=cpw.mods.securejarhandler","--add-exports","java.base/sun.security.util=cpw.mods.securejarhandler"]}}
        """;
    private static final String FABRIC="""
        {"id":"fabric-loader-0.16.10-1.21.1","inheritsFrom":"1.21.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
         "libraries":[{"name":"net.fabricmc:fabric-loader:0.16.10"},{"name":"net.fabricmc:intermediary:1.21.1"}],
         "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]}}
        """;
    private static final VersionProfile.Account ACCOUNT=new VersionProfile.Account("Steve","0123abcd","token-1","msa","","");
    private static final Map<String,String> LAUNCHER=Map.of("name","PCL","version","2.9");

    private void install(String id,String json) throws Exception {
        Path dir=root.resolve("versions").resolve(id); Files.createDirectories(dir); Files.writeString(dir.resolve(id+".json"),json);
    }
    /** Every file the version needs, present: a launcher that launched it once has them. */
    private void complete(String id) throws Exception {
        for(Path file:VersionProfile.load(root,id).missingFiles()) { Files.createDirectories(file.getParent()); Files.write(file,new byte[0]); }
    }
    private static void jar(Path file,String... entries) throws Exception {
        Files.createDirectories(file.getParent());
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(file))) {
            for(String name:entries) { out.putNextEntry(new ZipEntry(name)); if(name.equals("fabric.mod.json")) out.write("{\"id\":\"x\"}".getBytes()); out.closeEntry(); }
        }
    }

    @Test void neoForgeRemoteRunsInTheFabricPrimarysJvm() throws Exception {
        install("1.21.1",VANILLA); install("neoforge-21.1.265",NEOFORGE); install("fabric-loader-0.16.10-1.21.1",FABRIC);
        complete("neoforge-21.1.265");
        // Version-isolated instance: the NeoForge version's own folder holds its mods.
        Path instance=root.resolve("versions/neoforge-21.1.265");
        jar(instance.resolve("mods/mmlf-neoforge.jar"),"META-INF/neoforge.mods.toml","dev/mmlf/bridge/BridgeCore.class");
        jar(instance.resolve("mods/farmersdelight.jar"),"META-INF/neoforge.mods.toml");
        jar(instance.resolve("mods/sodium-fabric.jar"),"fabric.mod.json");
        Path gameDirectory=root.resolve("versions/fabric-loader-0.16.10-1.21.1");
        List<Double> steps=new ArrayList<>();

        var prepared=ReleaseRemote.prepare(root,"fabric","1.21.1",gameDirectory,(f,t)->steps.add(f));
        assertEquals("neoforge",prepared.remote());
        assertEquals(instance,prepared.instance());
        assertEquals(List.of("farmersdelight.jar","mmlf-neoforge.jar"),prepared.mods().stream().map(p->p.getFileName().toString()).sorted().toList());
        assertEquals(gameDirectory.resolve("mmlf/neoforge"),prepared.game());
        assertTrue(Files.isRegularFile(prepared.game().resolve("mods/mmlf-neoforge.jar")));
        assertFalse(Files.exists(prepared.game().resolve("mods/sodium-fabric.jar")),"only the remote loader's mods");
        assertEquals(List.of(0.1,0.3,0.5),steps);

        RuntimeSpec spec=ReleaseRemote.inJvm(prepared,root.resolve("session"),ACCOUNT,LAUNCHER);
        assertEquals("neoforge",spec.id()); assertEquals("neoforge",spec.loader());
        assertNull(spec.command());
        JavaLaunch launch=spec.java();
        assertEquals("cpw.mods.bootstraplauncher.BootstrapLauncher",launch.mainClass());
        Path libraries=root.resolve("libraries");
        assertEquals(List.of(libraries.resolve("cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar"),libraries.resolve("cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar"),
            libraries.resolve("org/ow2/asm/asm/9.7/asm-9.7.jar")),launch.modulePath());
        assertTrue(launch.classpath().contains(root.resolve("versions/1.21.1/1.21.1.jar")),"the game's class path for its legacy class path");
        assertTrue(launch.classpath().contains(libraries.resolve("org/ow2/asm/asm/9.7/asm-9.7.jar")));
        assertFalse(launch.classpath().contains(libraries.resolve("org/ow2/asm/asm/9.6/asm-9.6.jar")),"the loader's library wins");
        assertTrue(launch.jvmOptions().containsAll(List.of("--add-opens","java.base/java.util.jar=cpw.mods.securejarhandler","--add-modules","ALL-MODULE-PATH")));
        assertFalse(launch.jvmOptions().contains("-Xss1M"),"thread and heap sizes are the primary JVM's");
        Path natives=prepared.game().resolve("natives");
        assertEquals(natives.toString(),launch.property("org.lwjgl.system.SharedLibraryExtractPath"),"its own LWJGL natives");
        assertEquals(libraries.toString(),launch.property("libraryDirectory"));
        assertEquals("PCL",launch.property("minecraft.launcher.brand"));
        assertTrue(launch.properties().keySet().stream().noneMatch(k->k.startsWith("mmlf.")),"identity comes from the in-JVM registry");
        assertEquals(prepared.game().toString(),launch.arg("--gameDir"));
        assertEquals("forgeclient",launch.arg("--launchTarget"));
        assertEquals("token-1",launch.arg("--accessToken"));
        assertEquals("Steve",launch.arg("--username"));
        assertEquals(root.resolve("session/neoforge.log"),spec.log());
        assertEquals(prepared.game(),spec.gameDirectory());
        // The same launch round-trips to the command line the process backend would run.
        List<String> line=launch.command(Path.of("java"),List.of());
        assertEquals(launch,JavaLaunch.parse(line.subList(1,line.size())));
    }

    @Test void fabricRemoteAndTheProcessFallback() throws Exception {
        install("1.21.1",VANILLA); install("fabric-loader-0.16.10-1.21.1",FABRIC);
        complete("fabric-loader-0.16.10-1.21.1");
        jar(root.resolve("mods/mmlf-fabric.jar"),"fabric.mod.json","dev/mmlf/bridge/BridgeCore.class");
        Path gameDirectory=root.resolve("neoforge-game");
        var prepared=ReleaseRemote.prepare(root,"neoforge","1.21.1",gameDirectory,(f,t)->{});
        assertEquals(root,prepared.instance(),"no isolated mods folder: the shared instance");

        JavaLaunch launch=ReleaseRemote.inJvm(prepared,root.resolve("s"),ACCOUNT,LAUNCHER).java();
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient",launch.mainClass());
        assertTrue(launch.modulePath().isEmpty());
        assertTrue(launch.classpath().contains(root.resolve("libraries/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar")));
        assertEquals(" net.minecraft.client.main.Main ",launch.property("FabricMcEmu"));

        RuntimeSpec process=ReleaseRemote.process(prepared,root.resolve("s"),Path.of("javaw"),ACCOUNT,LAUNCHER,List.of("-Dmmlf.runtime=fabric"));
        assertNull(process.java());
        List<String> line=process.command().line();
        assertEquals("javaw",line.getFirst()); assertEquals("-Dmmlf.runtime=fabric",line.get(1));
        assertEquals(prepared.game(),process.command().workingDirectory());
    }

    @Test void missingRemoteVersionIsReportedBeforeAnythingIsStaged() throws Exception {
        install("1.21.1",VANILLA); install("fabric-loader-0.16.10-1.21.1",FABRIC);
        Path gameDirectory=root.resolve("game");
        var e=assertThrows(IllegalStateException.class,()->ReleaseRemote.prepare(root,"fabric","1.21.1",gameDirectory,(f,t)->{}));
        assertTrue(e.getMessage().contains("NeoForge"),e.getMessage());
        assertFalse(Files.exists(gameDirectory.resolve("mmlf")));
    }
}
