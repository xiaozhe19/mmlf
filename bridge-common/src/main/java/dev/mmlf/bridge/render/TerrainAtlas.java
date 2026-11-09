package dev.mmlf.bridge.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.mmlf.bridge.mixin.TextureAtlasAccessor;
import dev.mmlf.common.MmlfLog;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Copies a received block atlas into the reserved area of the local block atlas, on the render thread. */
public final class TerrainAtlas {
    private static final ResourceLocation RESERVE=ResourceLocation.fromNamespaceAndPath("mmlf_bridge","remote_reserve");
    static final boolean ENABLED="on".equalsIgnoreCase(System.getProperty("mmlf.terrain",System.getenv().getOrDefault("MMLF_TERRAIN","off")));
    private static int framebuffer;
    static volatile boolean ready;
    private static volatile int reserveSize=1024;
    public static int reserveSize() { return reserveSize; }
    static void ensureCapacity(Minecraft mc,int width,int height) {
        if(!ENABLED || width<=reserveSize && height<=reserveSize) return;
        int need=Math.max(width,height), size=reserveSize;
        int max=GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        while(size<need && size<=max/4) size*=2;
        if(size<need || size>max/2) {
            MmlfLog.sampled("render.submission","terrain_atlas_limit",5000,Map.of("width",width,"height",height,"maxTextureSize",max));
            return;
        }
        reserveSize=size; ready=false;
        MmlfLog.info("render.submission","terrain_atlas_resize",Map.of("size",size));
        mc.reloadResourcePacks();
    }
    private TerrainAtlas() {}

    static boolean copy(RemoteTexture source,int width,int height) {
        ready=false;
        if(!ENABLED) return false;
        var texture=Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (!(texture instanceof TextureAtlas target)) return false;
        var reserve=((TextureAtlasAccessor)target).mmlf$sprites().get(RESERVE);
        if (reserve==null || width>reserve.contents().width() || height>reserve.contents().height()) {
            MmlfLog.sampled("render.submission","terrain_atlas_reserve_too_small",5000,
                    Map.of("width",width,"height",height,"reserve",reserve==null?"missing":reserve.contents().width()+"x"+reserve.contents().height()));
            return false;
        }
        if(framebuffer==0) framebuffer=GL30.glGenFramebuffers();
        int previousRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,framebuffer);
            RenderSystem.bindTexture(target.getId());
            int levels=Math.max(0,Math.min(4,((TextureAtlasAccessor)target).mmlf$mipLevel()));
            for(int level=0;level<=levels;level++) {
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,source.getId(),level);
                if(GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) return false;
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,level,reserve.getX()>>level,reserve.getY()>>level,0,0,
                        Math.max(1,width>>level),Math.max(1,height>>level));
            }
            ready=true;
            return true;
        } finally {
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,0,0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,previousRead);
            RenderSystem.bindTexture(previousTexture);
        }
    }
}
