package dev.mmlf.bridge.render;

import dev.mmlf.bridge.BridgeCore;
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
        if(!BridgeCore.connected()) return;
        int index=(sodium ? 2 : 0)+(accepted ? 0 : 1);
        if(!SEEN[index].get() && SEEN[index].compareAndSet(false,true)) {
            MmlfLog.info("render", "mesh_hook_observed", Map.of(
                "path",sodium ? "sodium" : "vanilla", "accepted",accepted,
                "role",BridgeCore.isPrimary() ? "near" : "far", "runtime",BridgeCore.runtimeId()));
        }
    }
}
