package dev.mmlf.bridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.*;

/**
 * Primary side of the composited view: one full-screen pass draws the remote world into the main framebuffer. The
 * fragment writes the remote surface's depth as seen from the current camera, so the hardware depth test against the
 * primary's own depth buffer decides visibility; the primary depth buffer is never read back.
 *
 * When the remote frame was rendered from a different pose, each pixel's current view ray is marched (logarithmic
 * steps, then bisection) until it passes behind the remote depth surface, after the GTA V passthrough example in
 * rehan-remade/universal-modder (MIT). A crossing whose depth disagrees with the surface is a disocclusion: the pixel
 * is left to the primary instead of smearing the remote edge.
 */
final class RemoteCompositor {
    private static final String VERTEX="""
        #version 150
        out vec2 uv;
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            uv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;
    private static final String FRAGMENT="""
        #version 150
        uniform sampler2D remoteColor;
        uniform sampler2D remoteDepth;
        uniform mat4 currentInverse;   // current clip -> camera-relative world
        uniform mat4 currentViewProj;  // camera-relative world -> current clip
        uniform mat4 remoteViewProj;   // camera-relative world -> remote clip
        uniform mat4 remoteInverse;    // remote clip -> camera-relative world
        uniform int identity;
        uniform float nearPlane;
        uniform float farPlane;
        in vec2 uv;
        out vec4 color;

        bool remoteBehind(vec3 p, out vec2 at) {
            vec4 c = remoteViewProj * vec4(p, 1.0);
            if (c.w <= 1e-4) { at = vec2(-1.0); return false; }
            vec3 n = c.xyz / c.w;
            at = n.xy * 0.5 + 0.5;
            if (any(lessThan(at, vec2(0.0))) || any(greaterThan(at, vec2(1.0)))) return false;
            float d = texture(remoteDepth, at).r;
            return d < 1.0 && n.z * 0.5 + 0.5 >= d;
        }
        void main() {
            if (identity == 1) {
                float d = texture(remoteDepth, uv).r;
                if (d >= 1.0) discard;
                color = vec4(texture(remoteColor, uv).rgb, 1.0);
                gl_FragDepth = d;
                return;
            }
            vec4 a = currentInverse * vec4(uv * 2.0 - 1.0, -1.0, 1.0);
            vec4 b = currentInverse * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
            vec3 origin = a.xyz / a.w;
            vec3 dir = normalize(b.xyz / b.w - origin);
            float lo = 0.0, hi = -1.0;
            vec2 at;
            for (int i = 0; i < 40; i++) {
                float t = nearPlane * pow(farPlane / nearPlane, float(i) / 39.0);
                if (remoteBehind(origin + dir * t, at)) { hi = t; break; }
                lo = t;
            }
            if (hi < 0.0) discard;
            for (int k = 0; k < 8; k++) {
                float mid = 0.5 * (lo + hi);
                if (remoteBehind(origin + dir * mid, at)) hi = mid; else lo = mid;
            }
            vec3 p = origin + dir * hi;
            remoteBehind(p, at);
            float d = texture(remoteDepth, at).r;
            vec4 q = remoteInverse * vec4(at * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
            if (distance(q.xyz / q.w, p) > 0.08 + 0.02 * hi) discard;
            vec4 c = currentViewProj * vec4(p, 1.0);
            color = vec4(texture(remoteColor, at).rgb, 1.0);
            gl_FragDepth = clamp(c.z / c.w * 0.5 + 0.5, 0.0, 1.0);
        }
        """;
    private static int program, vao, colorTexture, depthTexture, textureWidth, textureHeight;
    private static int uCurrentInverse, uCurrentViewProj, uRemoteViewProj, uRemoteInverse, uIdentity, uNear, uFar;
    private static boolean failed, readyLogged;
    private static long uploadedFrame=-1, directFrames, reprojectedFrames, countedSince;
    private static final float[] matrix=new float[16];

    private RemoteCompositor() {}

    /** Uploads the frame if it is new, then draws it. Must run with the main target bound, after the world pass. */
    static void draw(Frame frame,FrameFile.Pixels pixels,Camera camera,int targetWidth,int targetHeight) {
        if(failed || !ensureProgram()) return;
        upload(frame,pixels);
        Pose source=frame.pose();
        Vec3 cp=camera.getPosition();
        Matrix4f projection=new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f currentViewProj=new Matrix4f(projection).rotate(new Quaternionf(camera.rotation()).conjugate());
        Matrix4f remoteViewProj=new Matrix4f().set(source.projection()).mul(new Matrix4f().set(source.view()))
            .translate((float)(cp.x-source.x()),(float)(cp.y-source.y()),(float)(cp.z-source.z()));
        boolean identity=Math.abs(cp.x-source.x())<1e-4 && Math.abs(cp.y-source.y())<1e-4 && Math.abs(cp.z-source.z())<1e-4
            && remoteViewProj.equals(currentViewProj,1e-5f);
        int[] viewport=new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        boolean depthTest=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int previousProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),previousVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActive=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0); int previous0=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._activeTexture(GL13.GL_TEXTURE1); int previous1=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._viewport(0,0,targetWidth,targetHeight);
            GlStateManager._enableDepthTest(); GlStateManager._depthFunc(GL11.GL_LESS); GlStateManager._depthMask(true);
            GlStateManager._disableBlend(); GlStateManager._disableCull(); GlStateManager._disableScissorTest();
            GlStateManager._colorMask(true,true,true,true);
            GlStateManager._glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0); GlStateManager._bindTexture(colorTexture);
            GlStateManager._activeTexture(GL13.GL_TEXTURE1); GlStateManager._bindTexture(depthTexture);
            GL20.glUniformMatrix4fv(uCurrentInverse,false,new Matrix4f(currentViewProj).invert().get(matrix));
            GL20.glUniformMatrix4fv(uCurrentViewProj,false,currentViewProj.get(matrix));
            GL20.glUniformMatrix4fv(uRemoteViewProj,false,remoteViewProj.get(matrix));
            GL20.glUniformMatrix4fv(uRemoteInverse,false,new Matrix4f(remoteViewProj).invert().get(matrix));
            GL20.glUniform1i(uIdentity,identity?1:0);
            GL20.glUniform1f(uNear,0.05f);
            GL20.glUniform1f(uFar,Math.max(16f,farPlane(projection)));
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
        } finally {
            GL30.glBindVertexArray(previousVao);
            GlStateManager._glUseProgram(previousProgram);
            GlStateManager._activeTexture(GL13.GL_TEXTURE1); GlStateManager._bindTexture(previous1);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0); GlStateManager._bindTexture(previous0);
            GlStateManager._activeTexture(previousActive);
            GlStateManager._viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GlStateManager._depthFunc(depthFunc); GlStateManager._depthMask(depthMask);
            if(depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            if(blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
            if(cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if(scissor) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
        }
        if(!readyLogged) { readyLogged=true; MmlfLog.info("render.composite","gpu_ready",Map.of("remote",frame.runtime(),"width",frame.width(),"height",frame.height())); }
        if(identity) directFrames++; else reprojectedFrames++;
        long now=System.nanoTime();
        if(now-countedSince>=1_000_000_000L) {
            MmlfLog.info("render.composite","gpu_frames",Map.of("frameId",frame.frameId(),"remote",frame.runtime(),"direct",directFrames,"reprojected",reprojectedFrames,
                "frameAgeMs",Math.max(0,(now-frame.capturedNanos())/1e6)));
            directFrames=reprojectedFrames=0; countedSince=now;
        }
    }

    /** The far plane of a standard OpenGL perspective matrix, which is where the march gives up. */
    private static float farPlane(Matrix4f p) {
        float a=p.m22(),b=p.m32();
        return Math.abs(a-1f)<1e-6f ? 1024f : b/(a-1f);
    }

    private static void upload(Frame frame,FrameFile.Pixels pixels) {
        if(uploadedFrame==frame.frameId()) return;
        int w=frame.width(),h=frame.height();
        int previousActive=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try(PixelTransfer transfer=new PixelTransfer()) {
            boolean resize=w!=textureWidth || h!=textureHeight;
            GlStateManager._bindTexture(colorTexture);
            if(resize) GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,w,h,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels.color());
            GlStateManager._bindTexture(depthTexture);
            if(resize) GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_R32F,w,h,0,GL11.GL_RED,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,w,h,GL11.GL_RED,GL11.GL_FLOAT,pixels.depth());
            textureWidth=w; textureHeight=h; uploadedFrame=frame.frameId();
        } finally {
            GlStateManager._bindTexture(previous);
            GlStateManager._activeTexture(previousActive);
        }
    }

    private static boolean ensureProgram() {
        if(program!=0) return true;
        try {
            int vs=compile(GL20.GL_VERTEX_SHADER,VERTEX),fs=compile(GL20.GL_FRAGMENT_SHADER,FRAGMENT);
            int p=GL20.glCreateProgram(); GL20.glAttachShader(p,vs); GL20.glAttachShader(p,fs);
            GL30.glBindFragDataLocation(p,0,"color");
            GL20.glLinkProgram(p); GL20.glDeleteShader(vs); GL20.glDeleteShader(fs);
            if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0) throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
            int previousProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GlStateManager._glUseProgram(p);
            GL20.glUniform1i(GL20.glGetUniformLocation(p,"remoteColor"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(p,"remoteDepth"),1);
            GlStateManager._glUseProgram(previousProgram);
            uCurrentInverse=GL20.glGetUniformLocation(p,"currentInverse"); uCurrentViewProj=GL20.glGetUniformLocation(p,"currentViewProj");
            uRemoteViewProj=GL20.glGetUniformLocation(p,"remoteViewProj"); uRemoteInverse=GL20.glGetUniformLocation(p,"remoteInverse");
            uIdentity=GL20.glGetUniformLocation(p,"identity"); uNear=GL20.glGetUniformLocation(p,"nearPlane"); uFar=GL20.glGetUniformLocation(p,"farPlane");
            vao=GL30.glGenVertexArrays();
            colorTexture=texture(); depthTexture=texture();
            program=p;
            return true;
        } catch(RuntimeException e) {
            failed=true;
            MmlfLog.error("render.composite","gpu_unavailable",Map.of("exception",e.getClass().getName(),"message",String.valueOf(e.getMessage())));
            return false;
        }
    }
    private static int texture() {
        int previousActive=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int id=GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._bindTexture(previous);
        GlStateManager._activeTexture(previousActive);
        return id;
    }
    private static int compile(int type,String source) {
        int shader=GL20.glCreateShader(type);
        GL20.glShaderSource(shader,source); GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0) { String log=GL20.glGetShaderInfoLog(shader); GL20.glDeleteShader(shader); throw new IllegalStateException(log); }
        return shader;
    }
}
