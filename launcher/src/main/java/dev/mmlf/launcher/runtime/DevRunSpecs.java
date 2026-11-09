package dev.mmlf.launcher.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mmlf.launcher.SessionHost;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Development runtimes from this project's Gradle setup. For the in-JVM backend the launch is read from what each
 * loader's Gradle plugin prepares for its own {@code runClient} (Loom's {@code launch.cfg}, ModDev's run files, and the
 * class path {@code writeClientRunSpec} records), so the loader starts the way its own dev run would start it.
 */
public final class DevRunSpecs {
    /** Development run tasks; a runtime not listed here cannot be started by a development session. */
    public static final Map<String,String> RUN_TASKS=Map.of("fabric",":fabric-bridge:runClient","neoforge",":neoforge-bridge:runClient");
    private DevRunSpecs() {}

    /** Stages the project's third-party mods for one runtime into its session game directory. */
    public static Path prepareGameDirectory(SessionHost session,Path root,String runtime) throws Exception {
        Path game=session.directory().resolve(runtime);
        SessionHost.stageMods(SessionHost.modsSource(root),SessionHost.LOADERS.get(runtime),game.resolve("mods"));
        // Explicit test-only shader pack: the remote directory is newly created for this session.
        String testPack=System.getenv("MMLF_TEST_SHADERPACK");
        if(testPack!=null && !testPack.isBlank()) stageTestShaderpack(game,Path.of(testPack));
        session.suppressEarlyWindow(runtime,game);
        return game;
    }

    static void stageTestShaderpack(Path game,Path pack) throws IOException {
        Path source=pack.toAbsolutePath().normalize();
        if(!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".zip")) throw new IOException("Invalid test shader pack: "+source);
        Path packs=game.resolve("shaderpacks"),config=game.resolve("config");
        Files.createDirectories(packs); Files.createDirectories(config);
        Files.copy(source,packs.resolve(source.getFileName()),StandardCopyOption.REPLACE_EXISTING);
        Properties options=new Properties(); options.setProperty("enableShaders","true"); options.setProperty("shaderPack",source.getFileName().toString());
        try(var writer=Files.newBufferedWriter(config.resolve("iris.properties"),StandardCharsets.UTF_8)) { options.store(writer,"MMLF isolated shader test"); }
    }

    /** In-JVM backend: the runtime's native dev launch, with its game directory in the session. */
    public static RuntimeSpec inJvm(SessionHost session,Path root,String runtime) throws Exception {
        Path game=prepareGameDirectory(session,root,runtime);
        JavaLaunch launch=switch(runtime) {
            case "fabric" -> fabric(root,game);
            case "neoforge" -> neoforge(root,game);
            default -> throw new IllegalArgumentException("unknown runtime "+runtime);
        };
        return RuntimeSpec.java(runtime,runtime,game,session.directory().resolve(runtime+".log"),launch);
    }

    /** Loom: Knot directly (what its dev launch injector would call), with launch.cfg's properties and arguments. */
    static JavaLaunch fabric(Path root,Path game) throws IOException {
        Path project=root.resolve("fabric-bridge");
        Path cfg=project.resolve(".gradle/loom-cache/launch.cfg");
        Map<String,List<String>> sections=launchConfig(require(cfg));
        Map<String,String> props=new LinkedHashMap<>();
        for(String section:List.of("commonProperties","clientProperties"))
            for(String line:sections.getOrDefault(section,List.of())) { int eq=line.indexOf('='); props.put(eq<0?line:line.substring(0,eq),eq<0?"":line.substring(eq+1)); }
        List<String> args=new ArrayList<>();
        for(String section:List.of("commonArgs","clientArgs")) args.addAll(sections.getOrDefault(section,List.of()));
        String log4j=props.get("log4j.configurationFile");
        if(log4j!=null) props.put("log4j.configurationFile",gameLog4j(Path.of(log4j),game).toString());
        props.put("org.lwjgl.system.SharedLibraryExtractPath",project.resolve("run/natives-fabric").toAbsolutePath().toString());
        JavaLaunch launch=new JavaLaunch("net.fabricmc.loader.impl.launch.knot.KnotClient",runSpecClasspath(project),List.of(),props,List.of(),args);
        return launch.withArg("--gameDir",game.toAbsolutePath().toString()).withArg("--width","1280").withArg("--height","720");
    }

    /** ModDev: BootstrapLauncher with the VM and program arguments ModDev wrote for runClient. */
    static JavaLaunch neoforge(Path root,Path game) throws IOException {
        Path project=root.resolve("neoforge-bridge");
        Path moddev=project.resolve("build/moddev");
        List<String> vm=argumentFile(require(moddev.resolve("clientRunVmArgs.txt")));
        List<String> program=argumentFile(require(moddev.resolve("clientRunProgramArgs.txt")));
        // Gradle adds the mod folders as a JVM argument of the task, not of the argument file.
        for(String a:runSpecJvmArgs(project)) if(a.startsWith("-Dfml.modFolders=")) vm.add(a);
        List<String> line=new ArrayList<>(vm);
        line.addAll(program);
        JavaLaunch launch=JavaLaunch.parse(line);
        Map<String,String> props=new LinkedHashMap<>(launch.properties());
        // FML's dev launch finds the game on the JVM's system class path, which in this JVM belongs to the host. Its
        // own "minecraft" mod-folder group names the game instead: the patched NeoForge jar ModDev puts on that path.
        String version=launch.arg("--fml.neoForgeVersion");
        // The group must be a directory (FML checks for net/minecraft/client/Minecraft.class in it), so it is unpacked once.
        Path patched=require(moddev.resolve("artifacts").resolve("neoforge-"+version+".jar"));
        Path classes=unpacked(patched,project.resolve("build/mmlf/neoforge-"+version+"-game"));
        String folders=props.get("fml.modFolders");
        props.put("fml.modFolders",(folders==null||folders.isBlank()?"":folders+File.pathSeparator)+"minecraft%%"+classes.toAbsolutePath());
        // Identity and session come from the in-JVM context; these JVM-wide values belong to the host runtime.
        props.keySet().removeIf(k->k.startsWith("mmlf."));
        String log4j=props.get("log4j2.configurationFile");
        if(log4j!=null) props.put("log4j2.configurationFile",gameLog4j(Path.of(log4j),game).toString());
        props.put("org.lwjgl.system.SharedLibraryExtractPath",project.resolve("run/natives-neoforge").toAbsolutePath().toString());
        launch=new JavaLaunch(launch.mainClass(),launch.classpath(),launch.modulePath(),props,launch.jvmOptions(),launch.args());
        return launch.withArg("--gameDir",game.toAbsolutePath().toString()).withArg("--width","1280").withArg("--height","720");
    }

    /** {@code jar} unpacked into {@code directory}, unpacked again only when the jar changed. */
    static Path unpacked(Path jar,Path directory) throws IOException {
        Path stamp=directory.resolve(".mmlf-source");
        String source=jar.toAbsolutePath()+"|"+Files.size(jar)+"|"+Files.getLastModifiedTime(jar).toMillis();
        if(Files.isRegularFile(stamp) && Files.readString(stamp).equals(source)) return directory;
        if(Files.exists(directory)) try(var walk=Files.walk(directory)) { for(Path p:walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        Files.createDirectories(directory);
        try(var zip=new java.util.zip.ZipFile(jar.toFile())) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var e=entries.nextElement();
                Path out=directory.resolve(e.getName()).normalize();
                if(!out.startsWith(directory)) throw new IOException("unsafe entry "+e.getName());
                if(e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                try(var in=zip.getInputStream(e)) { Files.copy(in,out,StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        Files.writeString(stamp,source);
        return directory;
    }
    private static Path require(Path file) throws IOException {
        if(!Files.isRegularFile(file)) throw new IOException("missing "+file+"; run the Gradle task that prepares it (writeClientRunSpec / prepareClientRun)");
        return file;
    }
    private static JsonObject runSpec(Path project) throws IOException {
        return JsonParser.parseString(Files.readString(require(project.resolve("build/mmlf/client-run.json")),StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static List<Path> runSpecClasspath(Path project) throws IOException {
        List<Path> out=new ArrayList<>();
        runSpec(project).getAsJsonArray("classpath").forEach(e->out.add(Path.of(e.getAsString())));
        return out;
    }
    private static List<String> runSpecJvmArgs(Path project) throws IOException {
        List<String> out=new ArrayList<>();
        runSpec(project).getAsJsonArray("jvmArgs").forEach(e->out.add(e.getAsString()));
        return out;
    }

    /** Loom's launch.cfg: section names at column 0, their values indented by a tab. */
    static Map<String,List<String>> launchConfig(Path file) throws IOException {
        Map<String,List<String>> sections=new LinkedHashMap<>();
        List<String> current=null;
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            if(line.isBlank()) continue;
            if(!line.startsWith("\t")) { current=new ArrayList<>(); sections.put(line.trim(),current); }
            else if(current!=null) current.add(line.substring(1));
        }
        return sections;
    }
    /** A Java argument file as ModDev writes it: one argument per line, backslashes escaped, {@code #} comments. */
    static List<String> argumentFile(Path file) throws IOException {
        List<String> out=new ArrayList<>();
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            String t=line.strip();
            if(t.isEmpty() || t.startsWith("#")) continue;
            if(t.length()>1 && t.startsWith("\"") && t.endsWith("\"")) t=t.substring(1,t.length()-1);
            out.add(t.replace("\\\\","\\"));
        }
        return out;
    }
    /**
     * The loader's log configuration writes to {@code logs/} relative to the working directory, which one JVM shares
     * between both runtimes; this copy writes into the runtime's own game directory instead.
     */
    static Path gameLog4j(Path config,Path game) throws IOException {
        if(!Files.isRegularFile(config)) return config;
        String logs=game.resolve("logs").toAbsolutePath().toString().replace('\\','/');
        String xml=Files.readString(config,StandardCharsets.UTF_8).replace("\"logs/",'"'+logs+'/');
        Path out=game.resolve("mmlf-log4j.xml");
        Files.createDirectories(game);
        Files.writeString(out,xml,StandardCharsets.UTF_8);
        return out;
    }
}
