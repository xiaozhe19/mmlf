package dev.mmlf.launcher;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * A game version as the official launcher, HMCL and PCL install it under {@code .minecraft/versions}: the version JSON
 * merged along {@code inheritsFrom}, with launcher rules evaluated for this machine. Only reads what the launcher already
 * installed; nothing is downloaded.
 */
public final class VersionProfile {
    public enum Loader { FABRIC, NEOFORGE, OTHER }
    /** Values a launcher substitutes into the version's argument templates. */
    public record Account(String name,String uuid,String accessToken,String type,String xuid,String clientId) {}

    private final Path root;
    private final String id, mainClass, assetIndex, type, jarId;
    private final List<JsonObject> libraries;
    private final List<JsonElement> jvm, game;

    private VersionProfile(Path root,String id,String mainClass,String assetIndex,String type,String jarId,List<JsonObject> libraries,List<JsonElement> jvm,List<JsonElement> game) {
        this.root=root; this.id=id; this.mainClass=mainClass; this.assetIndex=assetIndex; this.type=type; this.jarId=jarId;
        this.libraries=libraries; this.jvm=jvm; this.game=game;
    }
    public String id() { return id; }
    public String mainClass() { return mainClass; }

    /** Reads {@code versions/<id>/<id>.json} and every version it inherits from; the child's values win. */
    public static VersionProfile load(Path root,String id) throws IOException {
        List<JsonObject> chain=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(String current=id; current!=null; ) {
            if(!seen.add(current)) throw new IOException("版本 "+id+" 的 inheritsFrom 形成了循环");
            Path file=root.resolve("versions").resolve(current).resolve(current+".json");
            if(!Files.isRegularFile(file)) throw new IOException("找不到版本文件 "+file);
            JsonObject json;
            try { json=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch(RuntimeException e) { throw new IOException("版本文件无法解析: "+file,e); }
            chain.add(json);
            current=json.has("inheritsFrom")?json.get("inheritsFrom").getAsString():null;
        }
        String mainClass=null, assetIndex=null, type=null, jar=null;
        List<JsonObject> libraries=new ArrayList<>();
        Set<String> libraryKeys=new HashSet<>();
        for(JsonObject json:chain) {
            if(mainClass==null && json.has("mainClass")) mainClass=json.get("mainClass").getAsString();
            if(assetIndex==null && json.has("assetIndex")) assetIndex=json.getAsJsonObject("assetIndex").get("id").getAsString();
            if(assetIndex==null && json.has("assets")) assetIndex=json.get("assets").getAsString();
            if(type==null && json.has("type")) type=json.get("type").getAsString();
            if(jar==null && json.has("jar")) jar=json.get("jar").getAsString();
            // A loader replaces a library its parent also names (ASM, for one); the child's version is the one loaded.
            if(json.has("libraries")) for(JsonElement element:json.getAsJsonArray("libraries")) {
                JsonObject library=element.getAsJsonObject();
                if(libraryKeys.add(libraryKey(library.get("name").getAsString()))) libraries.add(library);
            }
        }
        if(mainClass==null) throw new IOException("版本 "+id+" 没有 mainClass");
        if(chain.stream().anyMatch(j->j.has("minecraftArguments") && !j.has("arguments"))) throw new IOException("版本 "+id+" 使用旧版启动参数格式，不受支持");
        List<JsonElement> jvm=new ArrayList<>(), game=new ArrayList<>();
        // The base version's arguments come first, as launchers order them.
        for(int i=chain.size()-1;i>=0;i--) {
            JsonObject arguments=chain.get(i).getAsJsonObject("arguments");
            if(arguments==null) continue;
            if(arguments.has("jvm")) arguments.getAsJsonArray("jvm").forEach(jvm::add);
            if(arguments.has("game")) arguments.getAsJsonArray("game").forEach(game::add);
        }
        // Launchers copy the base jar next to an inheriting version; fall back to the base version's own jar.
        String jarId=jar!=null?jar:Files.isRegularFile(root.resolve("versions").resolve(id).resolve(id+".jar"))?id:chainId(chain.getLast(),id);
        return new VersionProfile(root,id,mainClass,assetIndex==null?"legacy":assetIndex,type==null?"release":type,jarId,List.copyOf(libraries),List.copyOf(jvm),List.copyOf(game));
    }
    private static String chainId(JsonObject json,String fallback) { return json.has("id")?json.get("id").getAsString():fallback; }
    /** group:artifact[:classifier]: two versions of one library are the same library. */
    static String libraryKey(String name) {
        String[] parts=name.split("@")[0].split(":");
        return parts.length>3?parts[0]+":"+parts[1]+":"+parts[3]:parts[0]+":"+(parts.length>1?parts[1]:"");
    }

    public Loader loader() {
        if(libraries.stream().anyMatch(l->l.get("name").getAsString().startsWith("net.neoforged:neoforge:")) || gameValue("--fml.neoForgeVersion")!=null) return Loader.NEOFORGE;
        if(mainClass.startsWith("net.fabricmc.loader.") || libraries.stream().anyMatch(l->l.get("name").getAsString().startsWith("net.fabricmc:fabric-loader:"))) return Loader.FABRIC;
        return Loader.OTHER;
    }
    /** The loader's own version, used to choose between several installs of one loader. */
    public String loaderVersion() {
        String prefix=loader()==Loader.NEOFORGE?"net.neoforged:neoforge:":"net.fabricmc:fabric-loader:";
        String fromArgs=loader()==Loader.NEOFORGE?gameValue("--fml.neoForgeVersion"):null;
        if(fromArgs!=null) return fromArgs;
        return libraries.stream().map(l->l.get("name").getAsString()).filter(n->n.startsWith(prefix)).map(n->n.split(":")[2]).findFirst().orElse("");
    }
    /** The Minecraft version this loader runs on, or null when the profile does not say. */
    public String minecraftVersion() {
        if(loader()==Loader.NEOFORGE) { String value=gameValue("--fml.mcVersion"); if(value!=null) return value; }
        if(loader()==Loader.FABRIC) for(JsonObject library:libraries) {
            String name=library.get("name").getAsString();
            if(name.startsWith("net.fabricmc:intermediary:")) return name.split(":")[2];
        }
        return null;
    }
    private String gameValue(String option) {
        for(int i=0;i+1<game.size();i++)
            if(game.get(i).isJsonPrimitive() && game.get(i).getAsString().equals(option) && game.get(i+1).isJsonPrimitive()) return game.get(i+1).getAsString();
        return null;
    }

    /** Library archives for this machine, in classpath order. */
    public List<Path> libraryFiles() {
        List<Path> files=new ArrayList<>();
        for(JsonObject library:libraries) {
            if(!allowed(library.getAsJsonArray("rules"))) continue;
            // Pre-1.19 native archives are extracted by launchers; 1.21 ships its natives as ordinary classpath libraries.
            if(library.has("natives") && !library.has("downloads")) continue;
            JsonObject artifact=library.has("downloads")?library.getAsJsonObject("downloads").getAsJsonObject("artifact"):null;
            if(library.has("downloads") && artifact==null) continue;
            String path=artifact!=null && artifact.has("path")?artifact.get("path").getAsString():mavenPath(library.get("name").getAsString());
            files.add(root.resolve("libraries").resolve(path));
        }
        return files;
    }
    public Path clientJar() { return root.resolve("versions").resolve(jarId).resolve(jarId+".jar"); }
    /** Files the launch needs that the launcher has not installed; empty when the version is complete. */
    public List<Path> missingFiles() {
        List<Path> missing=new ArrayList<>();
        for(Path file:libraryFiles()) if(!Files.isRegularFile(file)) missing.add(file);
        if(!Files.isRegularFile(clientJar())) missing.add(clientJar());
        return missing;
    }
    static String mavenPath(String name) {
        String[] coordinates=name.split("@");
        String extension=coordinates.length>1?coordinates[1]:"jar";
        String[] parts=coordinates[0].split(":");
        if(parts.length<3) throw new IllegalArgumentException("invalid library name "+name);
        String file=parts[1]+"-"+parts[2]+(parts.length>3?"-"+parts[3]:"")+"."+extension;
        return parts[0].replace('.','/')+"/"+parts[1]+"/"+parts[2]+"/"+file;
    }

    /** JVM arguments, main class and game arguments; {@code extraJvm} goes before the main class. */
    public List<String> command(Path java,Path gameDirectory,Path nativesDirectory,Account account,Map<String,String> launcher,List<String> extraJvm) {
        String separator=System.getProperty("path.separator");
        List<String> classpath=new ArrayList<>();
        for(Path file:libraryFiles()) classpath.add(file.toString());
        classpath.add(clientJar().toString());
        Map<String,String> values=new HashMap<>();
        values.put("auth_player_name",account.name()); values.put("auth_uuid",account.uuid()); values.put("auth_access_token",account.accessToken());
        values.put("auth_session",account.accessToken()); values.put("user_type",account.type()); values.put("auth_xuid",account.xuid()); values.put("clientid",account.clientId());
        values.put("user_properties","{}"); values.put("version_name",id); values.put("version_type",type);
        values.put("game_directory",gameDirectory.toString()); values.put("assets_root",root.resolve("assets").toString()); values.put("game_assets",root.resolve("assets").toString());
        values.put("assets_index_name",assetIndex); values.put("library_directory",root.resolve("libraries").toString());
        values.put("natives_directory",nativesDirectory.toString()); values.put("classpath_separator",separator);
        values.put("classpath",String.join(separator,classpath));
        values.put("launcher_name",launcher.getOrDefault("name","minecraft-launcher")); values.put("launcher_version",launcher.getOrDefault("version","unknown"));
        List<String> command=new ArrayList<>();
        command.add(java.toString());
        command.addAll(extraJvm);
        expand(jvm,values,command);
        command.add(mainClass);
        expand(game,values,command);
        return command;
    }
    private static final Pattern PLACEHOLDER=Pattern.compile("\\$\\{([a-zA-Z_]+)}");
    private static void expand(List<JsonElement> arguments,Map<String,String> values,List<String> out) {
        for(JsonElement argument:arguments) {
            if(argument.isJsonPrimitive()) { out.add(substitute(argument.getAsString(),values)); continue; }
            JsonObject conditional=argument.getAsJsonObject();
            if(!allowed(conditional.getAsJsonArray("rules"))) continue;
            JsonElement value=conditional.get("value");
            if(value.isJsonArray()) for(JsonElement item:value.getAsJsonArray()) out.add(substitute(item.getAsString(),values));
            else out.add(substitute(value.getAsString(),values));
        }
    }
    static String substitute(String template,Map<String,String> values) {
        var matcher=PLACEHOLDER.matcher(template);
        StringBuilder out=new StringBuilder();
        while(matcher.find()) {
            String value=values.get(matcher.group(1));
            matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(value==null?matcher.group():value));
        }
        return matcher.appendTail(out).toString();
    }

    /** Launcher rule lists: disallowed unless a matching rule allows; no optional feature (demo, custom size) is enabled. */
    static boolean allowed(JsonArray rules) {
        if(rules==null) return true;
        boolean allowed=false;
        for(JsonElement element:rules) {
            JsonObject rule=element.getAsJsonObject();
            if(rule.has("features")) continue;
            if(rule.has("os") && !osMatches(rule.getAsJsonObject("os"))) continue;
            allowed="allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }
    private static boolean osMatches(JsonObject os) {
        String name=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String current=name.contains("win")?"windows":name.contains("mac")?"osx":"linux";
        if(os.has("name") && !os.get("name").getAsString().equals(current)) return false;
        if(os.has("arch") && !Pattern.compile(os.get("arch").getAsString()).matcher(System.getProperty("os.arch")).find()) return false;
        return !os.has("version") || Pattern.compile(os.get("version").getAsString()).matcher(System.getProperty("os.version")).find();
    }

    /** Every installed version that names the given loader and Minecraft version. */
    public static List<VersionProfile> find(Path root,Loader loader,String minecraftVersion) throws IOException {
        Path versions=root.resolve("versions");
        List<VersionProfile> found=new ArrayList<>();
        if(!Files.isDirectory(versions)) return found;
        try(var directories=Files.list(versions)) {
            for(Path directory:directories.filter(Files::isDirectory).toList()) {
                String id=directory.getFileName().toString();
                if(!Files.isRegularFile(directory.resolve(id+".json"))) continue;
                try {
                    VersionProfile profile=load(root,id);
                    if(profile.loader()==loader && minecraftVersion.equals(profile.minecraftVersion())) found.add(profile);
                } catch(IOException | RuntimeException ignored) { }
            }
        }
        found.sort(Comparator.comparing(VersionProfile::loaderVersion,VersionProfile::compareVersions).reversed().thenComparing(VersionProfile::id));
        return found;
    }
    /** Numeric-aware comparison: 21.1.265 is newer than 21.1.77. */
    static int compareVersions(String a,String b) {
        String[] x=a.split("[.+-]"), y=b.split("[.+-]");
        for(int i=0;i<Math.max(x.length,y.length);i++) {
            String p=i<x.length?x[i]:"", q=i<y.length?y[i]:"";
            int c=p.matches("\\d+") && q.matches("\\d+")?Long.compare(Long.parseLong(p),Long.parseLong(q)):p.compareTo(q);
            if(c!=0) return c;
        }
        return 0;
    }
}
