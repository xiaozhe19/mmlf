import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Can two LWJGL/GLFW copies in one JVM (two class loaders, as MMLF's near and far runtimes) share GL objects?
 *
 * The near side creates its context first (as Minecraft's primary does), the far side creates its own and joins the
 * near context's share group with wglShareLists before creating any object. Then, for each of 120 frames, the far
 * side renders a frame-dependent colour into a shared texture (and a depth value into a shared depth texture),
 * fences, and hands the texture names and fence over; the near side waits on the fence on the GPU (glWaitSync),
 * samples both textures into its own framebuffer, reads one pixel back and checks it.
 *
 * Usage (Windows): javac -cp <lwjgl jars> -d out ShareProbe.java ShareProbeSide.java
 *                  java -cp out ShareProbe cp.txt out [idle|current]
 * cp.txt lists the LWJGL 3.3.3 jars (lwjgl, lwjgl-glfw, lwjgl-opengl and their natives-windows jars), one per line.
 * "current": the near context is current on its thread while the far one joins (what MMLF would face); "idle": not.
 */
public class ShareProbe {
    public static void main(String[] a) throws Exception {
        List<URL> urls=new ArrayList<>();
        for(String p:Files.readAllLines(Path.of(a[0]))) if(!p.isBlank()) urls.add(Path.of(p.trim()).toUri().toURL());
        urls.add(Path.of(a[1]).toUri().toURL());
        String mode=a.length>2?a[2]:"current";
        // Each side: its own LWJGL copy and its own copy of ShareProbeSide; only JDK types cross.
        ClassLoader near=new URLClassLoader("near",urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader());
        ClassLoader far=new URLClassLoader("far",urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader());
        Map<String,Object> link=new ConcurrentHashMap<>();
        link.put("mode",mode);
        link.put("frames",new SynchronousQueue<long[]>());
        link.put("acks",new SynchronousQueue<long[]>());
        link.put("nearContext",new CompletableFuture<Long>());
        link.put("shared",new CompletableFuture<Boolean>());
        link.put("nearCapabilities",new CompletableFuture<Boolean>());
        ExecutorService threads=Executors.newFixedThreadPool(2);
        Future<?> n=threads.submit(()->side(near,"near",link));
        Future<?> f=threads.submit(()->side(far,"far",link));
        try {
            String nearResult=(String)n.get(120,TimeUnit.SECONDS);
            String farResult=(String)f.get(120,TimeUnit.SECONDS);
            System.out.println(nearResult);
            System.out.println(farResult);
            if(!nearResult.contains("frames=120 correct=120 wrong=0") || !nearResult.contains("glError=0")
                || !farResult.contains("shared=true") || !farResult.contains("glError=0")) {
                throw new IllegalStateException("ShareProbe did not complete all frame checks");
            }
        } finally {
            threads.shutdownNow();
        }
    }
    private static String side(ClassLoader loader,String role,Map<String,Object> link) {
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> side=loader.loadClass("ShareProbeSide");
            return (String) side.getMethod(role,Map.class,String.class).invoke(null,link,"natives-"+role);
        } catch(Throwable t) {
            ((CompletableFuture<?>) link.get("shared")).completeExceptionally(t);
            ((CompletableFuture<?>) link.get("nearContext")).completeExceptionally(t);
            ((CompletableFuture<?>) link.get("nearCapabilities")).completeExceptionally(t);
            StringBuilder b=new StringBuilder(role+": FAIL");
            Throwable last=t;
            for(Throwable c=t;c!=null;c=c.getCause()) { b.append(System.lineSeparator()).append("   ").append(c); last=c; }
            for(StackTraceElement e:Arrays.copyOf(last.getStackTrace(),Math.min(8,last.getStackTrace().length))) b.append(System.lineSeparator()).append("      at ").append(e);
            return b.toString();
        }
    }
}
