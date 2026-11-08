package dev.mmlf.launcher.runtime;

/**
 * Where a started runtime's loader calls {@code System.exit} before the game's own exits are redirected (its error
 * paths while loading). Defined again inside each such runtime's class loader and reached through a patched method
 * reference ({@link ClassPatch}), it refers to JDK types only. Instead of ending the JVM, and with it the host, it
 * ends the runtime's main thread with an error the backend reports as the runtime's failure.
 */
public final class InJvmExitTrap {
    private InJvmExitTrap() {}
    /** Prefix of the error's message; the backend recognises it. */
    public static final String MESSAGE="MMLF: runtime loader called System.exit(";
    public static void exit(int code) { throw new Error(MESSAGE+code+")"); }
}
