package dev.mmlf.bridge;

import dev.mmlf.common.InJvm;
import dev.mmlf.launcher.runtime.InJvmBackend;
import java.util.Map;

/**
 * {@code System.exit} for a runtime started in its host's JVM. The game calls it only at the end of
 * {@code Minecraft.destroy} (closing) and {@code Minecraft.crash} (after the crash report is written); every caller
 * returns right after it, so the call records the exit code for the host and returns, and the game's own code then
 * unwinds this runtime's main thread. Throwing instead would reach the loader's launch, which treats any throwable as
 * a crash and ends the whole JVM itself. Outside in-JVM sessions this is the game's System.exit, unchanged.
 */
public final class InJvmExit {
    private InJvmExit() {}
    public static void exit(int code) {
        RuntimeContext context=RuntimeContext.current();
        if(!context.inJvm()) { System.exit(code); return; }
        InJvm.registry().put(InJvmBackend.exitKey(context.loader()),code);
        dev.mmlf.common.MmlfLog.info("connection","injvm_exit",Map.of("code",code));
    }
}
