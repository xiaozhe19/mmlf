package dev.mmlf.launcher.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One runtime a session starts. Either a {@link JavaLaunch} (both backends) or, for development runs only, a command
 * that starts it some other way (Gradle run tasks), which only the process backend can run.
 *
 * @param id runtime id in the session
 * @param loader native loader: {@code fabric} or {@code neoforge}
 * @param gameDirectory the runtime's own game directory
 * @param log where its output goes
 * @param java Java launch, or null for a command
 * @param command command line and working directory for the process backend, or null for a Java launch
 */
public record RuntimeSpec(String id,String loader,Path gameDirectory,Path log,JavaLaunch java,Command command) {
    public record Command(List<String> line,Path workingDirectory,List<String> runtimes) {
        public Command { line=List.copyOf(line); runtimes=List.copyOf(runtimes); }
    }
    public RuntimeSpec {
        Objects.requireNonNull(id); Objects.requireNonNull(loader); Objects.requireNonNull(log);
        if((java==null)==(command==null)) throw new IllegalArgumentException("a runtime is either a Java launch or a command");
        if(!id.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("invalid runtime id "+id);
    }
    public static RuntimeSpec java(String id,String loader,Path gameDirectory,Path log,JavaLaunch java) { return new RuntimeSpec(id,loader,gameDirectory,log,java,null); }
    public static RuntimeSpec command(String id,String loader,Path log,Command command) { return new RuntimeSpec(id,loader,null,log,null,command); }
}
