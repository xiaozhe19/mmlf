package dev.mmlf.launcher.runtime.fake;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for a loader's launch in {@code InJvmLifecycleTest}: loaded in its own class loader under the JDK-only
 * root, it sees nothing of MMLF and talks to the backend through the JVM-wide registry only, as a bridge does.
 * {@code args}: loader, mode ({@code normal}: reaches its world and waits for a stop; {@code stall}: stays in the
 * bridge phase until asked to stop; {@code throw}: fails after its bridge is up).
 */
public final class FakeRuntime {
    private FakeRuntime() {}
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var registry=(ConcurrentMap<String,Object>) System.getProperties().get("mmlf.injvm.registry");
        String loader=args[0], mode=args[1];
        AtomicInteger cleanups=new AtomicInteger();
        registry.put("test:cleanups",cleanups);
        registry.put("cleanup:"+loader,(Runnable) cleanups::incrementAndGet);
        // What this runtime sees of the properties, from its main thread and from a thread it starts.
        registry.put("test:sawMain",String.valueOf(System.getProperty("mmlf.test.scoped")));
        Thread child=new Thread(()->registry.put("test:sawChild",String.valueOf(System.getProperty("mmlf.test.scoped"))));
        child.start(); child.join();
        registry.put("test:sawHidden",String.valueOf(System.getProperty("fabric.test.hostOnly")));
        System.setProperty("mmlf.test.written","by-runtime");
        registry.put("phase:"+loader,"bridge");
        registry.put("initialized:"+loader,Boolean.TRUE);
        if(mode.equals("throw")) throw new IllegalStateException("fake runtime failed");
        if(!mode.equals("stall")) {
            pause(); registry.put("phase:"+loader,"game");
            pause(); registry.put("phase:"+loader,"world");
        }
        while(registry.get("stop:"+loader)==null) Thread.sleep(20);
        registry.put("phase:"+loader,"stopping");
        pause();
        registry.put("exit:"+loader,0);
    }
    /** Longer than the backend's 100 ms poll, so every phase is seen. */
    private static void pause() throws InterruptedException { Thread.sleep(250); }
}
