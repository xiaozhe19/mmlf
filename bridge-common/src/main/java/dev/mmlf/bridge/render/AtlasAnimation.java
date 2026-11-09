package dev.mmlf.bridge.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.bridge.mixin.TextureAtlasAccessor;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.RenderAtlasPatchCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Animated block textures (water, lava, fire, animated mod textures) for the unified render layer. The block atlas is
 * sent once per scene; afterwards the submitter reads only the rectangles of its animated sprites, at every mip level,
 * straight from its own atlas texture once per client tick (which is when vanilla advances animations), and the
 * render host writes them into its copy. Whatever drives an animation (vanilla mcmeta, a mod's own ticker), the
 * atlas texture holds the current frame, so this needs no knowledge of how sprites animate.
 */
final class AtlasAnimation {
    private AtlasAnimation() {}
    private record Rect(int x,int y,int width,int height) {}
    // Submitter.
    private static List<Rect> animated;
    private static long spritesGeneration=-1;
    private static int mipLevels, framebuffer, atlasWidth, atlasHeight;
    private static int lastHash;
    private static boolean reported;
    // Render host.
    private static final RenderChannel.Cursor cursor=new RenderChannel.Cursor();

    static void reset() { animated=null; spritesGeneration=-1; lastHash=0; cursor.reset(); }

    /** Submitter, client tick, after its atlas was published for {@code generation}. */
    static void publish(Minecraft mc,long generation) {
        var texture=mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if(!(texture instanceof TextureAtlas atlas)) return;
        if(animated==null || spritesGeneration!=generation) {
            spritesGeneration=generation;
            var accessor=(TextureAtlasAccessor)atlas;
            mipLevels=Math.max(0,Math.min(4,accessor.mmlf$mipLevel()));
            animated=new ArrayList<>();
            for(var sprite:accessor.mmlf$sprites().values())
                if(sprite.contents().getUniqueFrames().limit(2).count()>1) animated.add(new Rect(sprite.getX(),sprite.getY(),sprite.contents().width(),sprite.contents().height()));
            RenderSystem.bindTexture(atlas.getId());
            atlasWidth=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH); atlasHeight=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
            MmlfLog.info("render.submission","atlas_animation",Map.of("sprites",animated.size(),"mipLevels",mipLevels));
        }
        if(animated.isEmpty() || atlasWidth<1) return;
        if(framebuffer==0) framebuffer=GL30.glGenFramebuffers();
        var patches=new ArrayList<RenderAtlasPatchCodec.Patch>();
        int previousRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int largest=0; for(Rect r:animated) largest=Math.max(largest,r.width()*r.height()*4);
        ByteBuffer pixels=MemoryUtil.memAlloc(largest);
        int hash=1;
        var transfer=new GlPixelState();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,framebuffer);
            for(int level=0;level<=mipLevels;level++) {
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,atlas.getId(),level);
                if(GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) break;
                for(Rect r:animated) {
                    int x=r.x()>>level,y=r.y()>>level,w=r.width()>>level,h=r.height()>>level;
                    if(w<1 || h<1) continue;
                    pixels.clear().limit(w*h*4);
                    GL11.glReadPixels(x,y,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                    byte[] data=new byte[w*h*4]; pixels.get(0,data);
                    hash=31*hash+Arrays.hashCode(data);
                    patches.add(new RenderAtlasPatchCodec.Patch(level,x,y,w,h,data));
                }
            }
        } finally {
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,0,0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,previousRead);
            transfer.close();
            MemoryUtil.memFree(pixels);
        }
        if(hash==lastHash) return; // nothing advanced this tick
        lastHash=hash;
        try {
            RenderChannel.publish("atlas_patch",RenderAtlasPatchCodec.encode(new RenderAtlasPatchCodec.Batch(BridgeCore.runtimeId(),generation,atlasWidth,atlasHeight,patches)));
            if(!reported) { reported=true; MmlfLog.info("render.submission","atlas_animation_published",Map.of("patches",patches.size())); }
        } catch(java.io.IOException e) { MmlfLog.sampled("render.submission","atlas_animation_failed",5000,Map.of("exception",e.toString())); }
    }

    /** Render host, each frame: writes the newest frames into its copy of the submitter's atlas. */
    static void apply(String runtime,RemoteTexture atlas,long generation,int width,int height) {
        if(atlas==null) return;
        try {
            byte[] bytes=RenderChannel.poll(runtime,"atlas_patch",cursor,RenderAtlasPatchCodec.MAX_BYTES+65536);
            if(bytes==null) return;
            var batch=RenderAtlasPatchCodec.decode(bytes);
            if(!batch.runtime().equals(runtime) || batch.generation()!=generation || batch.atlasWidth()!=width || batch.atlasHeight()!=height) return;
            RenderSystem.bindTexture(atlas.getId());
            ByteBuffer pixels=null;
            var transfer=new GlPixelState();
            try {
                for(var patch:batch.patches()) {
                    byte[] data=patch.rgba();
                    if(pixels==null || pixels.capacity()<data.length) { if(pixels!=null) MemoryUtil.memFree(pixels); pixels=MemoryUtil.memAlloc(Math.max(data.length,4096)); }
                    pixels.clear(); pixels.put(data).flip();
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,patch.level(),patch.x(),patch.y(),patch.width(),patch.height(),GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                }
            } finally { transfer.close(); if(pixels!=null) MemoryUtil.memFree(pixels); }
            if(!TerrainAtlas.copy(atlas,width,height)) TerrainMeshes.invalidate();
        } catch(java.io.IOException | RuntimeException e) { MmlfLog.sampled("render.submission","atlas_animation_apply_failed",5000,Map.of("exception",e.toString())); }
    }
}
