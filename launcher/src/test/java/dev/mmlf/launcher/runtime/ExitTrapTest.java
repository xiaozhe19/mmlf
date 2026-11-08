package dev.mmlf.launcher.runtime;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.*;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A started runtime's loader classes cannot end the JVM: their System.exit ends only the runtime's thread. */
class ExitTrapTest {
    @TempDir Path dir;

    private Path compile(String pkg,String source) throws Exception {
        Path src=dir.resolve("src").resolve(pkg.replace('.','/')).resolve("Fails.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src,"package "+pkg+";\n"+source);
        Path out=Files.createDirectories(dir.resolve("classes-"+pkg));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",out.toString(),src.toString()));
        return out;
    }
    private static final String FAILS="public final class Fails { public static String run() { System.exit(3); return \"returned\"; } public static long now() { return System.nanoTime(); } }";

    @Test void loaderExitBecomesTheRuntimesFailure() throws Exception {
        Path out=compile("net.fabricmc.loader.impl.test",FAILS);
        try(var loader=new InJvmBackend.TrappingLoader("trap-test",new URL[]{out.toUri().toURL()},JdkOnlyLoader.get())) {
            Class<?> c=loader.loadClass("net.fabricmc.loader.impl.test.Fails");
            assertSame(loader,c.getClassLoader());
            assertNotNull(c.getProtectionDomain().getCodeSource(),"the loader finds its own jar through its code source");
            assertEquals(out.toUri().toURL(),c.getProtectionDomain().getCodeSource().getLocation());
            assertTrue((long) c.getMethod("now").invoke(null)>0,"other System calls are untouched");
            var e=assertThrows(InvocationTargetException.class,()->c.getMethod("run").invoke(null));
            assertTrue(e.getCause().getMessage().startsWith(InJvmExitTrap.MESSAGE),e.getCause().toString());
            assertTrue(e.getCause().getMessage().contains("(3)"));
            assertSame(loader,loader.loadClass(InJvmExitTrap.class.getName()).getClassLoader(),"the trap is the runtime's own copy");
        }
    }

    @Test void otherClassesAreNotPatched() throws Exception {
        Path out=compile("com.example.game",FAILS);
        try(var loader=new InJvmBackend.TrappingLoader("trap-test",new URL[]{out.toUri().toURL()},JdkOnlyLoader.get())) {
            byte[] original=Files.readAllBytes(out.resolve("com/example/game/Fails.class"));
            assertSame(original,ClassPatch.redirectStatic(original,"java/lang/Runtime","halt","x/Y"));
            Class<?> c=loader.loadClass("com.example.game.Fails");
            assertTrue(List.of(c.getMethods()).stream().anyMatch(m->m.getName().equals("run")));
            // Not invoked: unpatched, it would end the test JVM.
        }
    }
}
