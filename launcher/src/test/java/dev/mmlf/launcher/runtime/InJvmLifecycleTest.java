package dev.mmlf.launcher.runtime;

import dev.mmlf.common.InJvm;
import dev.mmlf.launcher.SessionHost;
import dev.mmlf.launcher.runtime.fake.FakeRuntime;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The in-JVM backend's lifecycle with a stand-in runtime: phases, stop, one clean-up, stall and failure reporting. */
class InJvmLifecycleTest {
    private static final String LOADER="neoforge";
    @TempDir Path dir;
    private SessionHost host;
    private RuntimeManager manager;

    @BeforeEach void session() throws Exception {
        System.setProperty("fabric.test.hostOnly","host-value");
        host=SessionHost.create(dir.resolve("runtime"),null,"fabric",false);
        manager=new RuntimeManager(host,new InJvmBackend(host));
    }
    @AfterEach void end() {
        manager.close();
        System.clearProperty("mmlf.injvm.gameTimeoutMs");
    }

    private RuntimeSpec spec(String mode) throws Exception {
        Path classes=Path.of(FakeRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        JavaLaunch launch=new JavaLaunch(FakeRuntime.class.getName(),List.of(classes),List.of(),Map.of("mmlf.test.scoped","runtime"),List.of(),List.of(LOADER,mode));
        Path game=Files.createDirectories(host.directory().resolve(LOADER));
        return RuntimeSpec.java(LOADER,LOADER,game,host.directory().resolve(LOADER+".log"),launch);
    }
    private static void await(RuntimeHandle handle,RuntimeHandle.State state) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(handle.state()!=state && System.nanoTime()<deadline) Thread.sleep(20);
        assertEquals(state,handle.state());
    }
    private static int cleanups() { return ((AtomicInteger) InJvm.registry().get("test:cleanups")).get(); }

    @Test void phasesStopAndOneCleanUp() throws Exception {
        RuntimeHandle handle=manager.start(spec("normal"));
        await(handle,RuntimeHandle.State.IN_WORLD);
        // Scoped properties: the runtime and the threads it starts see its launch's values and not the host's
        // loader-only keys; the host sees neither those values nor what the runtime wrote.
        assertEquals("runtime",InJvm.registry().get("test:sawMain"));
        assertEquals("runtime",InJvm.registry().get("test:sawChild"));
        assertEquals("null",InJvm.registry().get("test:sawHidden"));
        assertEquals("host-value",System.getProperty("fabric.test.hostOnly"));
        assertNull(System.getProperty("mmlf.test.scoped"));
        assertNull(System.getProperty("mmlf.test.written"));
        assertThrows(IllegalStateException.class,()->manager.start(spec("normal")),"one runtime per loader");
        handle.stop(Duration.ofSeconds(10));
        RuntimeHandle.Exit exit=handle.exit().get(10,TimeUnit.SECONDS);
        assertEquals(0,exit.code());
        assertFalse(exit.failed());
        assertEquals(RuntimeHandle.State.EXITED,handle.state());
        assertEquals(1,cleanups(),"the runtime's clean-up runs once");
        manager.close();
        assertEquals(1,cleanups(),"closing the session again does not clean up again");
        for(String key:List.of("phase:","cleanup:","running:","context:","stop:")) assertNull(InJvm.registry().get(key+LOADER),key);
    }

    @Test void stalledPhaseIsReportedAndStopped() throws Exception {
        System.setProperty("mmlf.injvm.gameTimeoutMs","400");
        List<String> stalls=new CopyOnWriteArrayList<>();
        manager.onStall((h,reason)->stalls.add(reason));
        RuntimeHandle handle=manager.start(spec("stall"));
        String reason=handle.stalled().get(10,TimeUnit.SECONDS);
        assertTrue(reason.contains("bridge"),reason);
        // Asked to stop by the stall, the runtime ends itself.
        assertEquals(0,handle.exit().get(10,TimeUnit.SECONDS).code());
        assertEquals(List.of(reason),stalls);
        assertTrue(Files.readString(host.directory().resolve(LOADER+".log")).contains(reason));
    }

    @Test void failureEndsFailedWithCause() throws Exception {
        RuntimeHandle handle=manager.start(spec("throw"));
        RuntimeHandle.Exit exit=handle.exit().get(10,TimeUnit.SECONDS);
        assertTrue(exit.failed());
        assertTrue(exit.failure().contains("fake runtime failed"),exit.failure());
        assertEquals(RuntimeHandle.State.FAILED,handle.state());
        assertEquals(1,cleanups());
        assertFalse(handle.alive());
    }
}
