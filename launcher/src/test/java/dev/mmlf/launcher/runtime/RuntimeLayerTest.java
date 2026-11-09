package dev.mmlf.launcher.runtime;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLayerTest {
    /** Calls the method the patch redirects. */
    public static final class Caller { public static ModuleLayer call() { return ModuleLayer.boot(); } }
    /** Where the patched call goes instead. */
    public static final class Replacement { public static ModuleLayer boot() { return ModuleLayer.empty(); } }

    @Test void redirectedStaticCallReachesTheReplacement() throws Exception {
        String name=Caller.class.getName();
        byte[] original;
        try(var in=Caller.class.getResourceAsStream("/"+name.replace('.','/')+".class")) { original=in.readAllBytes(); }
        byte[] patched=ClassPatch.redirectStatic(original,"java/lang/ModuleLayer","boot",Replacement.class.getName().replace('.','/'));
        assertNotEquals(original.length,patched.length);
        ClassLoader loader=new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String n,boolean resolve) throws ClassNotFoundException {
                if(!n.equals(name)) return super.loadClass(n,resolve);
                synchronized(getClassLoadingLock(n)) { Class<?> c=findLoadedClass(n); return c!=null?c:defineClass(n,patched,0,patched.length); }
            }
        };
        Object result=loader.loadClass(name).getMethod("call").invoke(null);
        assertSame(ModuleLayer.empty(),result);
        assertSame(ModuleLayer.boot(),Caller.call());
    }

    @Test void patchLeavesClassesWithoutTheCallUnchanged() throws Exception {
        byte[] original;
        try(var in=Replacement.class.getResourceAsStream("/"+Replacement.class.getName().replace('.','/')+".class")) { original=in.readAllBytes(); }
        assertSame(original,ClassPatch.redirectStatic(original,"java/lang/ModuleLayer","boot","x/Y"));
    }

    @Test void javaCommandLinesRoundTrip() {
        String sep=System.getProperty("path.separator");
        List<String> line=List.of("-Da=1","-Db=x=y","-p","m1.jar"+sep+"m2.jar","--add-modules","ALL-MODULE-PATH",
            "--add-opens","java.base/java.util.jar=cpw.mods.securejarhandler","-Xss2m","-cp","c.jar","main.Class","--gameDir",".","--width","1280");
        JavaLaunch launch=JavaLaunch.parse(line);
        assertEquals("main.Class",launch.mainClass());
        assertEquals(Map.of("a","1","b","x=y"),launch.properties());
        assertEquals(List.of(Path.of("m1.jar"),Path.of("m2.jar")),launch.modulePath());
        assertEquals(List.of(Path.of("c.jar")),launch.classpath());
        assertEquals(List.of("--add-modules","ALL-MODULE-PATH","--add-opens","java.base/java.util.jar=cpw.mods.securejarhandler","-Xss2m"),launch.jvmOptions());
        assertEquals(".",launch.arg("--gameDir"));
        JavaLaunch moved=launch.withArg("--gameDir","/abs").withArg("--height","720");
        assertEquals("/abs",moved.arg("--gameDir"));
        assertEquals("720",moved.arg("--height"));
        JavaLaunch again=JavaLaunch.parse(moved.command(Path.of("java"),List.of()).subList(1,moved.command(Path.of("java"),List.of()).size()));
        assertEquals(moved,again);
    }

    @Test void devRunFilesParse() throws Exception {
        Path dir=Files.createTempDirectory("mmlf-devrun");
        Path cfg=dir.resolve("launch.cfg");
        Files.writeString(cfg,"commonProperties\n\tfabric.development=true\n\tlog4j.configurationFile=C:\\x\\log4j.xml\nclientArgs\n\t--assetIndex\n\t1.21.1-17\n");
        var sections=DevRunSpecs.launchConfig(cfg);
        assertEquals(List.of("fabric.development=true","log4j.configurationFile=C:\\x\\log4j.xml"),sections.get("commonProperties"));
        assertEquals(List.of("--assetIndex","1.21.1-17"),sections.get("clientArgs"));
        Path args=dir.resolve("args.txt");
        Files.writeString(args,"# Main Class\ncpw.mods.bootstraplauncher.BootstrapLauncher\n\n# Program\n--gameDir\n.\n-DlegacyClassPath.file=D:\\\\Project\\\\cp.txt\n");
        assertEquals(List.of("cpw.mods.bootstraplauncher.BootstrapLauncher","--gameDir",".","-DlegacyClassPath.file=D:\\Project\\cp.txt"),DevRunSpecs.argumentFile(args));
        Path log4j=dir.resolve("log4j.xml");
        Files.writeString(log4j,"<A fileName=\"logs/latest.log\" filePattern=\"logs/%d.log.gz\"/>");
        String rewritten=Files.readString(DevRunSpecs.gameLog4j(log4j,dir.resolve("game")));
        assertFalse(rewritten.contains("\"logs/"));
        assertTrue(rewritten.contains(dir.resolve("game").resolve("logs").toAbsolutePath().toString().replace('\\','/')+"/latest.log"));
    }
}
