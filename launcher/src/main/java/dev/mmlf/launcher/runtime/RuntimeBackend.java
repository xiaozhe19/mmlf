package dev.mmlf.launcher.runtime;

/** Starts runtimes of one session in some form: separate processes, or isolated class loaders in this JVM. */
public interface RuntimeBackend {
    String name();
    RuntimeHandle start(RuntimeSpec spec) throws Exception;
}
