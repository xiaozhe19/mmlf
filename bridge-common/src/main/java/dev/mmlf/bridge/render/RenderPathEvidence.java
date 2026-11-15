package dev.mmlf.bridge.render;

import dev.mmlf.common.MmlfLog;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-time evidence that a mesh hook actually ran, including an accepted and a filtered block where available. */
public final class RenderPathEvidence {
    private RenderPathEvidence() {}
    private static final AtomicBoolean[] SEEN = {
        new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean()
    };

    public static void observed(boolean sodium, boolean accepted) {
        if(!dev.mmlf.bridge.transport.SessionLink.connected()) return;
        int index=(sodium ? 2 : 0)+(accepted ? 0 : 1);
        if(!SEEN[index].get() && SEEN[index].compareAndSet(false,true)) {
            MmlfLog.info("render", "mesh_hook_observed", Map.of(
                "path",sodium ? "sodium" : "vanilla", "accepted",accepted,
                "role",dev.mmlf.bridge.transport.SessionLink.isPrimary() ? "near" : "far", "runtime",dev.mmlf.bridge.transport.SessionLink.runtimeId()));
        }
    }
}
