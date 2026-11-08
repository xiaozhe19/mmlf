package dev.mmlf.bridge;

import dev.mmlf.common.InJvm;
import dev.mmlf.common.Session;
import java.nio.file.Path;
import java.util.Map;

/**
 * Who this runtime is and which session it belongs to. Resolved once, before the window exists, from the source that
 * started it: the in-JVM backend's registry entry for this loader, or the system properties and environment of a
 * process the process backend started. In one JVM the properties and environment belong to the host runtime, so a
 * runtime started in-JVM never reads its identity from them.
 *
 * @param id runtime id
 * @param loader the native loader this copy of the bridge runs in
 * @param sessionPath session file to join, or null when nothing started this runtime as part of a session
 * @param background a runtime someone else hosts: no visible window, no own session, never hosts one
 * @param inJvm started by the in-JVM backend in its host's JVM
 * @param host the process that started this background runtime, for process-backend runtimes that were told
 */
public record RuntimeContext(String id,String loader,String sessionPath,boolean background,boolean inJvm,ProcessHandle host) {
    private static volatile RuntimeContext current;

    public static RuntimeContext current() {
        RuntimeContext c=current;
        if(c==null) synchronized(RuntimeContext.class) { if((c=current)==null) current=c=resolve(detectLoader()); }
        return c;
    }

    /** The loader whose classes this copy of the bridge sees; the other loader's are invisible by isolation. */
    static String detectLoader() {
        ClassLoader own=RuntimeContext.class.getClassLoader();
        if(present("net.fabricmc.loader.api.FabricLoader",own)) return "fabric";
        if(present("net.neoforged.fml.loading.FMLLoader",own)) return "neoforge";
        throw new IllegalStateException("MMLF bridge loaded outside Fabric and NeoForge");
    }
    private static boolean present(String name,ClassLoader loader) {
        try { Class.forName(name,false,loader); return true; } catch(ClassNotFoundException|LinkageError e) { return false; }
    }

    static RuntimeContext resolve(String loader) {
        Map<String,String> injvm=InJvm.context(loader);
        if(injvm!=null) return new RuntimeContext(injvm.get("runtime"),loader,injvm.get("session"),!"true".equals(injvm.get("primary")),true,null);
        String sessionPath=System.getProperty("mmlf.session",System.getenv("MMLF_SESSION"));
        if(sessionPath!=null && sessionPath.isBlank()) sessionPath=null;
        String id=System.getProperty("mmlf.runtime",loader);
        boolean background=false;
        if(sessionPath!=null) {
            try { background=!Session.read(Path.of(sessionPath)).primary().equals(id); } catch(Exception ignored) { }
        }
        return new RuntimeContext(id,loader,sessionPath,background,false,hostProcess());
    }
    private static ProcessHandle hostProcess() {
        try { String pid=System.getenv("MMLF_HOST_PID"); return pid==null?null:ProcessHandle.of(Long.parseLong(pid)).orElse(null); }
        catch(RuntimeException e) { return null; }
    }
    /** A background window stays hidden when the host asked for it (the process backend says so; in-JVM always). */
    public boolean hiddenWindow() { return background && (inJvm || "true".equals(System.getenv("MMLF_HIDE_SECONDARY"))); }
    /** Only a runtime nothing else started may host a session; this is what keeps runtimes from starting each other. */
    public boolean mayHost() { return sessionPath==null && !inJvm; }
}
