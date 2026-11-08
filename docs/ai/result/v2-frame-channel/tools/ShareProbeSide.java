import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWGL;
import org.lwjgl.opengl.*;
import org.lwjgl.system.Configuration;
import static org.lwjgl.opengl.GL33C.*;

/** One side of {@link ShareProbe}, loaded once per class loader; talks to the other side through JDK types only. */
public final class ShareProbeSide {
    private ShareProbeSide() {}
    static final int W=256, H=256, FRAMES=120;

    private static long window(String extract) {
        Configuration.SHARED_LIBRARY_EXTRACT_PATH.set(Path.of(extract).toAbsolutePath().toString());
        if(!GLFW.glfwInit()) throw new IllegalStateException("glfwInit");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long w=GLFW.glfwCreateWindow(W,H,"share-probe",0,0);
        if(w==0) throw new IllegalStateException("window");
        return w;
    }
    private static float[] colour(long frame) { return new float[]{(frame*37%256)/255f,(frame*91%256)/255f,(frame*13%256)/255f,1f}; }
    private static float depth(long frame) { return 0.25f+(frame%64)/256f; }

    @SuppressWarnings("unchecked")
    public static String near(Map<String,Object> link,String extract) throws Exception {
        long w=window(extract);
        boolean current="current".equals(link.get("mode"));
        if(current) { GLFW.glfwMakeContextCurrent(w); GL.createCapabilities(); }
        ((CompletableFuture<Long>) link.get("nearContext")).complete(GLFWNativeWGL.glfwGetWGLContext(w));
        boolean shared=((CompletableFuture<Boolean>) link.get("shared")).get(30,TimeUnit.SECONDS);
        if(!shared) return "near: far could not join the share group";
        if(!current) { GLFW.glfwMakeContextCurrent(w); GL.createCapabilities(); }
        ((CompletableFuture<Boolean>) link.get("nearCapabilities")).complete(true);
        String renderer=glGetString(GL_RENDERER);
        // The near side's own target, as Minecraft's main render target: colour and depth written by a full-screen pass.
        int fbo=glGenFramebuffers(), colour=glGenTextures(), depthTex=glGenTextures();
        glBindTexture(GL_TEXTURE_2D,colour); glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D,depthTex); glTexImage2D(GL_TEXTURE_2D,0,GL_R32F,W,H,0,GL_RED,GL_FLOAT,(ByteBuffer) null);
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,colour,0);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,depthTex,0);
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1});
        int program=program("""
            #version 330 core
            const vec2 p[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
            void main(){ gl_Position=vec4(p[gl_VertexID],0,1); }
            ""","""
            #version 330 core
            uniform sampler2D remoteColour; uniform sampler2D remoteDepth;
            layout(location=0) out vec4 outColour; layout(location=1) out vec4 outDepth;
            void main(){ ivec2 c=ivec2(gl_FragCoord.xy); outColour=texelFetch(remoteColour,c,0); outDepth=vec4(texelFetch(remoteDepth,c,0).r); }
            """);
        int vao=glGenVertexArrays();
        glUseProgram(program); glUniform1i(glGetUniformLocation(program,"remoteColour"),0); glUniform1i(glGetUniformLocation(program,"remoteDepth"),1);
        glViewport(0,0,W,H);
        var frames=(SynchronousQueue<long[]>) link.get("frames"); var acks=(SynchronousQueue<long[]>) link.get("acks");
        ByteBuffer pixel=BufferUtils.createByteBuffer(4); FloatBuffer d=BufferUtils.createFloatBuffer(1);
        int correct=0, wrong=0; List<Long> nanos=new ArrayList<>(); String firstWrong=null;
        for(int i=0;i<FRAMES;i++) {
            long[] f=frames.poll(10,TimeUnit.SECONDS);
            if(f==null) return "near: no frame "+i;
            long started=System.nanoTime();
            long fence=f[3];
            glWaitSync(fence,0,GL_TIMEOUT_IGNORED);   // GPU-side wait: the near CPU never blocks
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,(int) f[1]);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D,(int) f[2]);
            glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES,0,3);
            glReadBuffer(GL_COLOR_ATTACHMENT0); glReadPixels(W/2,H/2,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            glReadBuffer(GL_COLOR_ATTACHMENT1); glReadPixels(W/2,H/2,1,1,GL_RED,GL_FLOAT,d);
            nanos.add(System.nanoTime()-started);
            float[] want=colour(f[0]);
            boolean ok=true;
            for(int c=0;c<3;c++) ok&=Math.abs((pixel.get(c)&0xff)-Math.round(want[c]*255))<=1;
            ok&=Math.abs(d.get(0)-depth(f[0]))<1e-6;
            if(ok) correct++; else { wrong++; if(firstWrong==null) firstWrong="frame "+f[0]+" got "+(pixel.get(0)&0xff)+","+(pixel.get(1)&0xff)+","+(pixel.get(2)&0xff)+" depth "+d.get(0); }
            acks.put(new long[]{f[0]});
        }
        int error=glGetError();
        GLFW.glfwDestroyWindow(w);
        Collections.sort(nanos);
        return "near: renderer="+renderer+" frames="+FRAMES+" correct="+correct+" wrong="+wrong+(firstWrong==null?"":" first="+firstWrong)
            +" glError="+error+" consume p50="+nanos.get(nanos.size()/2)/1e6+"ms p95="+nanos.get(nanos.size()*95/100)/1e6+"ms";
    }

    @SuppressWarnings("unchecked")
    public static String far(Map<String,Object> link,String extract) throws Exception {
        long nearContext=((CompletableFuture<Long>) link.get("nearContext")).get(30,TimeUnit.SECONDS);
        long w=window(extract);
        long farContext=GLFWNativeWGL.glfwGetWGLContext(w);
        // As MMLF's InJvmLwjgl: LWJGL's JVM-wide missing-function slot is filled by whichever copy initialises its GL
        // class first. Touching WGL already runs GL.<clinit> (WGL.<clinit> -> GL.create -> setFunctionMissingAddresses),
        // so the slot is yielded before the first WGL call, not only around createCapabilities. Whatever it held is put
        // back afterwards, empty included, so in "idle" mode the near copy can still fill it later.
        // (First run, 2026-11-05: yielding only around createCapabilities failed with "setFunctionMissingAddresses has
        // been called already" in WGL.<clinit>.)
        var field=org.lwjgl.system.ThreadLocalUtil.class.getDeclaredField("JNI_NATIVE_INTERFACE"); field.setAccessible(true);
        long env=field.getLong(null), slot=env+3L*org.lwjgl.system.Pointer.POINTER_SIZE;
        long reserved=org.lwjgl.system.MemoryUtil.memGetAddress(env), held=org.lwjgl.system.MemoryUtil.memGetAddress(slot);
        boolean shared;
        org.lwjgl.system.MemoryUtil.memPutAddress(slot,reserved);
        try {
            // Join before this context has made any object: wglShareLists requires an empty destination.
            shared=WGL.wglShareLists(nearContext,farContext);
            if(shared) { GLFW.glfwMakeContextCurrent(w); GL.createCapabilities(); }
        } finally { org.lwjgl.system.MemoryUtil.memPutAddress(slot,held); }
        ((CompletableFuture<Boolean>) link.get("shared")).complete(shared);
        if(!shared) return "far: wglShareLists failed (GetLastError via WGL not available here)";
        int[] colour=new int[2], depthTex=new int[2], fbo=new int[2];
        for(int s=0;s<2;s++) {
            colour[s]=glGenTextures(); glBindTexture(GL_TEXTURE_2D,colour[s]); glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            // Depth as a colour attachment (R32F), as MMLF would blit the remote depth into a texture it can sample.
            depthTex[s]=glGenTextures(); glBindTexture(GL_TEXTURE_2D,depthTex[s]); glTexImage2D(GL_TEXTURE_2D,0,GL_R32F,W,H,0,GL_RED,GL_FLOAT,(ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            fbo[s]=glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER,fbo[s]);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,colour[s],0);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,depthTex[s],0);
            glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1});
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) return "far: incomplete framebuffer";
        }
        glFinish();
        var frames=(SynchronousQueue<long[]>) link.get("frames"); var acks=(SynchronousQueue<long[]>) link.get("acks");
        List<Long> nanos=new ArrayList<>();
        for(int i=0;i<FRAMES;i++) {
            int s=i%2;
            long started=System.nanoTime();
            glBindFramebuffer(GL_FRAMEBUFFER,fbo[s]); glViewport(0,0,W,H);
            float[] c=colour(i);
            glClearBufferfv(GL_COLOR,0,c);
            glClearBufferfv(GL_COLOR,1,new float[]{depth(i),0,0,0});
            long fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            glFlush();   // the fence must reach the GPU before another context waits on it
            nanos.add(System.nanoTime()-started);
            frames.put(new long[]{i,colour[s],depthTex[s],fence});
            long[] ack=acks.poll(10,TimeUnit.SECONDS);
            if(ack==null) return "far: no acknowledgement for frame "+i;
            glDeleteSync(fence);
        }
        int error=glGetError();
        GLFW.glfwDestroyWindow(w);
        Collections.sort(nanos);
        return "far: shared=true textures="+Arrays.toString(colour)+" glError="+error+" produce p50="+nanos.get(nanos.size()/2)/1e6+"ms";
    }

    private static int program(String vertex,String fragment) {
        int v=shader(GL_VERTEX_SHADER,vertex), f=shader(GL_FRAGMENT_SHADER,fragment), p=glCreateProgram();
        glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
        if(glGetProgrami(p,GL_LINK_STATUS)==0) throw new IllegalStateException(glGetProgramInfoLog(p));
        return p;
    }
    private static int shader(int type,String source) {
        int s=glCreateShader(type); glShaderSource(s,source); glCompileShader(s);
        if(glGetShaderi(s,GL_COMPILE_STATUS)==0) throw new IllegalStateException(glGetShaderInfoLog(s));
        return s;
    }
}
