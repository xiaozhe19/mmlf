package dev.mmlf.launcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class VersionProfileTest {
 @TempDir Path root;
 private static final String VANILLA="""
  {"id":"1.21.1","type":"release","mainClass":"net.minecraft.client.main.Main","assetIndex":{"id":"17"},
   "libraries":[
    {"name":"org.ow2.asm:asm:9.3","downloads":{"artifact":{"path":"org/ow2/asm/asm/9.3/asm-9.3.jar"}}},
    {"name":"org.lwjgl:lwjgl:3.3.3:natives-windows","rules":[{"action":"allow","os":{"name":"windows"}}]},
    {"name":"org.lwjgl:lwjgl:3.3.3:natives-linux","rules":[{"action":"allow","os":{"name":"linux"}}]},
    {"name":"org.lwjgl:lwjgl:3.3.3:natives-macos","rules":[{"action":"allow","os":{"name":"osx"}}]}],
   "arguments":{
    "game":["--username","${auth_player_name}","--gameDir","${game_directory}","--assetIndex","${assets_index_name}","--accessToken","${auth_access_token}",
     {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}],
    "jvm":["-Djava.library.path=${natives_directory}","-cp","${classpath}"]}}
  """;
 private static final String NEOFORGE="""
  {"id":"neoforge-21.1.265","inheritsFrom":"1.21.1","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
   "libraries":[{"name":"org.ow2.asm:asm:9.7"},{"name":"net.neoforged:neoforge:21.1.265:universal"}],
   "arguments":{"game":["--fml.neoForgeVersion","21.1.265","--fml.mcVersion","1.21.1"],"jvm":["-DlibraryDirectory=${library_directory}"]}}
  """;
 private void install(String id,String json) throws Exception {
  Path dir=root.resolve("versions").resolve(id); Files.createDirectories(dir); Files.writeString(dir.resolve(id+".json"),json);
 }
 private void touch(Path file) throws Exception { Files.createDirectories(file.getParent()); Files.write(file,new byte[0]); }

 @Test void mergesInheritanceAndBuildsTheLaunchCommand() throws Exception {
  install("1.21.1",VANILLA); install("neoforge-21.1.265",NEOFORGE);
  VersionProfile profile=VersionProfile.load(root,"neoforge-21.1.265");
  assertEquals(VersionProfile.Loader.NEOFORGE,profile.loader());
  assertEquals("1.21.1",profile.minecraftVersion());
  assertEquals("21.1.265",profile.loaderVersion());
  List<Path> libraries=profile.libraryFiles();
  // The loader's ASM replaces the base version's; only this machine's natives remain.
  assertTrue(libraries.contains(root.resolve("libraries/org/ow2/asm/asm/9.7/asm-9.7.jar")));
  assertFalse(libraries.contains(root.resolve("libraries/org/ow2/asm/asm/9.3/asm-9.3.jar")));
  assertEquals(1,libraries.stream().filter(p->p.getFileName().toString().startsWith("lwjgl-3.3.3-natives")).count());
  assertEquals(root.resolve("versions/1.21.1/1.21.1.jar"),profile.clientJar());
  assertFalse(profile.missingFiles().isEmpty());

  var account=new VersionProfile.Account("Steve","0123","secret-token","msa","","");
  List<String> command=profile.command(Path.of("java"),root.resolve("game"),root.resolve("natives"),account,Map.of("name","HMCL","version","3"),List.of("-Dmmlf.runtime=neoforge"));
  assertEquals("java",command.getFirst());
  assertEquals("-Dmmlf.runtime=neoforge",command.get(1));
  int main=command.indexOf("cpw.mods.bootstraplauncher.BootstrapLauncher");
  assertTrue(main>0);
  assertTrue(command.subList(0,main).contains("-DlibraryDirectory="+root.resolve("libraries")));
  String classpath=command.get(command.indexOf("-cp")+1);
  assertTrue(classpath.endsWith(root.resolve("versions/1.21.1/1.21.1.jar").toString()));
  List<String> game=command.subList(main+1,command.size());
  assertEquals("Steve",game.get(game.indexOf("--username")+1));
  assertEquals("secret-token",game.get(game.indexOf("--accessToken")+1));
  assertEquals("17",game.get(game.indexOf("--assetIndex")+1));
  assertEquals(root.resolve("game").toString(),game.get(game.indexOf("--gameDir")+1));
  assertFalse(game.contains("--demo"));
  assertTrue(game.indexOf("--username")<game.indexOf("--fml.neoForgeVersion"));
 }

 @Test void picksTheNewestCompleteLoaderForTheMinecraftVersion() throws Exception {
  install("1.21.1",VANILLA); install("neoforge-21.1.265",NEOFORGE);
  install("neoforge-21.1.77",NEOFORGE.replace("21.1.265","21.1.77"));
  install("neoforge-1.20",NEOFORGE.replace("\"1.21.1\"]","\"1.20.1\"]").replace("21.1.265","20.4.1"));
  install("fabric-1.21.1","""
   {"id":"fabric-1.21.1","inheritsFrom":"1.21.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
    "libraries":[{"name":"net.fabricmc:fabric-loader:0.16.10"},{"name":"net.fabricmc:intermediary:1.21.1"}]}
   """);
  var found=VersionProfile.find(root,VersionProfile.Loader.NEOFORGE,"1.21.1");
  assertEquals(List.of("neoforge-21.1.265","neoforge-21.1.77"),found.stream().map(VersionProfile::id).toList());
  var fabric=VersionProfile.find(root,VersionProfile.Loader.FABRIC,"1.21.1");
  assertEquals("1.21.1",fabric.getFirst().minecraftVersion());

  var missing=assertThrows(IllegalStateException.class,()->Manager.remoteVersion(root,"neoforge","1.21.1"));
  assertTrue(missing.getMessage().contains("补全"));
  VersionProfile chosen=VersionProfile.load(root,"neoforge-21.1.265");
  for(Path file:chosen.missingFiles()) touch(file);
  assertEquals("neoforge-21.1.265",Manager.remoteVersion(root,"neoforge","1.21.1").id());
  var none=assertThrows(IllegalStateException.class,()->Manager.remoteVersion(root,"neoforge","1.21.4"));
  assertTrue(none.getMessage().contains("NeoForge"));
 }

 @Test void remoteModsMustIncludeTheBridge() throws Exception {
  Path mods=root.resolve("mods"); Files.createDirectories(mods);
  jar(mods.resolve("other.jar"),"META-INF/neoforge.mods.toml");
  jar(mods.resolve("fabric-only.jar"),"fabric.mod.json");
  assertThrows(IllegalStateException.class,()->Manager.remoteMods(mods,"neoforge"));
  jar(mods.resolve("mmlf-neoforge.jar"),"META-INF/neoforge.mods.toml","dev/mmlf/bridge/BridgeCore.class");
  var found=Manager.remoteMods(mods,"neoforge");
  assertEquals(List.of("mmlf-neoforge.jar","other.jar"),found.stream().map(p->p.getFileName().toString()).toList());
  Path game=root.resolve("session/neoforge");
  Files.createDirectories(root.resolve("config/sub")); Files.writeString(root.resolve("config/sub/a.toml"),"x=1");
  Files.writeString(root.resolve("options.txt"),String.join(System.lineSeparator(),"lang:zh_cn","onboardAccessibility:true"));
  Manager.stage(found,root,game);
  assertEquals(List.of("lang:zh_cn","onboardAccessibility:false"),Files.readAllLines(game.resolve("options.txt")));
  assertTrue(Files.isRegularFile(game.resolve("mods/mmlf-neoforge.jar")));
  Files.delete(mods.resolve("other.jar"));
  Manager.stage(Manager.remoteMods(mods,"neoforge"),root,game);
  assertFalse(Files.exists(game.resolve("mods/other.jar")));
  assertTrue(Files.isRegularFile(mods.resolve("mmlf-neoforge.jar")));
  assertEquals("x=1",Files.readString(game.resolve("config/sub/a.toml")));
 }

 @Test void keepsOnlyRecentSessions() throws Exception {
  Path runtime=root.resolve("sessions");
  for(int i=0;i<4;i++) { Path dir=runtime.resolve("session-"+i); touch(dir.resolve("x.log")); dir.toFile().setLastModified(1_000_000L*(i+1)); }
  Manager.removeOldSessions(runtime);
  try(var left=Files.list(runtime)) { assertEquals(Set.of("session-2","session-3"),new HashSet<>(left.map(p->p.getFileName().toString()).toList())); }
 }

 @Test void remoteFailureNamesTheCrashCause() throws Exception {
  Path game=root.resolve("fabric"), log=root.resolve("fabric.log");
  assertTrue(Manager.remoteFailure("Fabric",1,game,log,0).contains("退出码 1"));
  Files.createDirectories(game.resolve("crash-reports"));
  Files.writeString(game.resolve("crash-reports/crash-1.txt"),String.join(System.lineSeparator(),
   "---- Minecraft Crash Report ----","Description: Initializing game","","java.lang.RuntimeException: Mixin transformation failed",
   "Caused by: org.spongepowered.asm.mixin.throwables.MixinApplyError: outer","Caused by: InvalidInjectionException: could not find isKeyDown",
   "A detailed walkthrough of the error","Caused by: later duplicate"));
  String text=Manager.remoteFailure("Fabric",1,game,log,0);
  assertFalse(Manager.remoteFailure("Fabric",1,game,log,System.currentTimeMillis()+60_000).contains("crash-1.txt"));
  assertTrue(text.contains("Initializing game") && text.contains("could not find isKeyDown") && !text.contains("later duplicate") && text.contains("crash-1.txt"),text);
 }

 private static void jar(Path file,String... entries) throws Exception {
  try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(file))) {
   for(String name:entries) { out.putNextEntry(new ZipEntry(name)); if(name.equals("fabric.mod.json")) out.write("{\"id\":\"x\"}".getBytes()); out.closeEntry(); }
  }
 }
}
