package dev.mmlf.bridge;

import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * Two LWJGL copies in one JVM, one per runtime. LWJGL keeps one table JVM-wide: in a reserved slot of the JNI function
 * table it stores the "missing function" fallback for threads without a current GL context, and refuses to start GL
 * when another copy already filled it. Each thread with a context uses its own JNIEnv copy, so either table serves as
 * that fallback. While this runtime's first context is created the slot is handed to it empty, so it builds its own
 * table; then the host's value is put back.
 */
public final class InJvmLwjgl {
    private InJvmLwjgl() {}
    private static final int CAPABILITIES_OFFSET=3*org.lwjgl.system.Pointer.POINTER_SIZE;
    /** Registry key (Long): the host's table, recorded by the runtime that yielded the slot, for checks after it ends. */
    private static final String HOST_TABLE="lwjgl:hostMissingTable";
    /** Registry key (Long): the table this runtime's copy built while it held the slot. */
    private static final String GUEST_TABLE="lwjgl:guestMissingTable";

    private static long jniNativeInterface() throws ReflectiveOperationException {
        var field=org.lwjgl.system.ThreadLocalUtil.class.getDeclaredField("JNI_NATIVE_INTERFACE");
        field.setAccessible(true);
        return field.getLong(null);
    }
    /** Empties the slot if another copy filled it; returns what to restore, or 0 when nothing was changed. */
    public static long yieldMissingTable() {
        if(!RuntimeContext.current().inJvm()) return 0;
        try {
            long env=jniNativeInterface();
            long reserved0=MemoryUtil.memGetAddress(env);
            long current=MemoryUtil.memGetAddress(env+CAPABILITIES_OFFSET);
            if(current==reserved0) return 0;
            MemoryUtil.memPutAddress(env+CAPABILITIES_OFFSET,reserved0);
            dev.mmlf.common.InJvm.registry().put(HOST_TABLE,current);
            dev.mmlf.common.MmlfLog.info("render","lwjgl_slot_yielded",Map.of());
            return current;
        } catch(ReflectiveOperationException|RuntimeException e) {
            dev.mmlf.common.MmlfLog.warn("render","lwjgl_slot_unavailable",Map.of("exception",e.toString()));
            return 0;
        }
    }
    public static void restoreMissingTable(long saved) {
        if(saved==0) return;
        try {
            long env=jniNativeInterface();
            dev.mmlf.common.InJvm.registry().put(GUEST_TABLE,MemoryUtil.memGetAddress(env+CAPABILITIES_OFFSET));
            MemoryUtil.memPutAddress(env+CAPABILITIES_OFFSET,saved);
        }
        catch(ReflectiveOperationException|RuntimeException e) { dev.mmlf.common.MmlfLog.warn("render","lwjgl_slot_restore_failed",Map.of("exception",e.toString())); }
    }
    /**
     * Host side, after a runtime ended: the slot must hold the host's table, never the ended runtime's (its library may
     * be unloaded with its class loader). Puts the host's back when it does not, and reports what it found.
     */
    public static void verifyHostTable() {
        var registry=dev.mmlf.common.InJvm.registry();
        if(!(registry.get(HOST_TABLE) instanceof Long host)) return;
        try {
            long env=jniNativeInterface();
            long current=MemoryUtil.memGetAddress(env+CAPABILITIES_OFFSET);
            boolean guest=registry.get(GUEST_TABLE) instanceof Long g && g==current;
            if(current!=host) MemoryUtil.memPutAddress(env+CAPABILITIES_OFFSET,host);
            dev.mmlf.common.MmlfLog.info("render","lwjgl_slot_verified",Map.of("held","0x"+Long.toHexString(current),"host",current==host,"guest",guest,"restored",current!=host));
        } catch(ReflectiveOperationException|RuntimeException e) {
            dev.mmlf.common.MmlfLog.warn("render","lwjgl_slot_verify_failed",Map.of("exception",e.toString()));
        }
    }
}
