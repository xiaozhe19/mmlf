package dev.mmlf.launcher.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One runtime a session starts: a {@link JavaLaunch} the in-JVM backend runs inside an isolated class loader.
 *
 * @param id runtime id in the session
 * @param loader native loader: {@code fabric} or {@code neoforge}
 * @param gameDirectory the runtime's own game directory
 * @param log where its output goes
 * @param java Java launch
 */
public record RuntimeSpec(String id,String loader,Path gameDirectory,Path log,JavaLaunch java) {
    public RuntimeSpec {
        Objects.requireNonNull(id); Objects.requireNonNull(loader); Objects.requireNonNull(log); Objects.requireNonNull(java);
        if(!id.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("invalid runtime id "+id);
    }
    public static RuntimeSpec java(String id,String loader,Path gameDirectory,Path log,JavaLaunch java) { return new RuntimeSpec(id,loader,gameDirectory,log,java); }
}
