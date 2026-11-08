package dev.mmlf.launcher.runtime;

import dev.mmlf.common.InJvm;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ScopedPropertiesTest {
    private ScopedProperties props;
    private ScopedProperties.Scope scope;
    private final ThreadGroup group=new ThreadGroup("mmlf-test-scope");

    @BeforeEach void open() {
        props=ScopedProperties.install();
        scope=props.open("test",group);
    }
    @AfterEach void close() {
        props.close(scope);
        for(String k:new String[]{"t.own","t.hidden","t.host","t.written","t.removed"}) System.clearProperty(k);
    }

    /** Runs {@code task} on a thread of the scope's group and returns its result. */
    private <T> T inScope(Callable<T> task) throws Exception {
        FutureTask<T> f=new FutureTask<>(task);
        Thread t=new Thread(group,f,"scoped"); t.start(); t.join(5000);
        return f.get();
    }

    @Test void installsOnceAndKeepsTheRegistry() {
        var registry=InJvm.registry();
        assertSame(props,ScopedProperties.install());
        assertSame(props,System.getProperties());
        assertSame(registry,InJvm.registry(),"the registry (a non-String value) survives the swap");
    }

    @Test void runtimeAndHostSeeTheirOwnValues() throws Exception {
        System.setProperty("t.hidden","host"); System.setProperty("t.host","host");
        scope.set("t.own","runtime"); scope.set("t.hidden",null);
        assertEquals("runtime",inScope(()->System.getProperty("t.own")));
        assertNull(inScope(()->System.getProperty("t.hidden")));
        assertEquals("host",inScope(()->System.getProperty("t.host")),"keys it does not overlay are the host's");
        assertNull(System.getProperty("t.own"));
        assertEquals("host",System.getProperty("t.hidden"));
        assertEquals(Long.valueOf(7),inScope(()->{ System.setProperty("t.written","7"); return Long.getLong("t.written"); }));
        assertNull(System.getProperty("t.written"),"what the runtime writes stays its own");
        assertEquals("gone",inScope(()->{ System.clearProperty("t.host"); return System.getProperty("t.host","gone"); }));
        assertEquals("host",System.getProperty("t.host"),"clearing from the runtime does not clear the host's");
    }

    @Test void enumerationShowsTheRuntimeView() throws Exception {
        System.setProperty("t.hidden","host");
        scope.set("t.own","runtime"); scope.set("t.hidden",null);
        var names=inScope(()->System.getProperties().stringPropertyNames());
        assertTrue(names.contains("t.own")); assertFalse(names.contains("t.hidden"));
        assertTrue(inScope(()->System.getProperties().keySet().contains("t.own")));
        assertFalse(System.getProperties().stringPropertyNames().contains("t.own"));
    }

    @Test void sharedKeysAndRegistryAreNotScoped() throws Exception {
        inScope(()->System.setProperty("mmlf.injvm.test","shared"));
        assertEquals("shared",System.getProperty("mmlf.injvm.test"));
        assertSame(InJvm.registry(),inScope(InJvm::registry));
        System.clearProperty("mmlf.injvm.test");
    }

    @Test void contextLoaderMakesAThreadTheRuntimes() throws Exception {
        try(URLClassLoader runtime=new URLClassLoader(new URL[0],null); URLClassLoader child=new URLClassLoader(new URL[0],runtime)) {
            scope.addLoader(runtime);
            scope.set("t.own","runtime");
            FutureTask<String> f=new FutureTask<>(()->System.getProperty("t.own"));
            Thread t=new Thread(f,"pool-like"); t.setContextClassLoader(child); t.start(); t.join(5000);
            assertEquals("runtime",f.get(),"a thread outside the group whose context loader descends from the runtime's");
        }
    }
}
