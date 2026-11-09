package dev.mmlf.bridge.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

/**
 * A texture received from the submitting runtime, registered in this runtime's texture manager under an
 * {@code mmlf:remote/...} name so ordinary render types (and any shader mod) can bind it by resource location.
 */
final class RemoteTexture extends AbstractTexture {
    private final boolean mipmapped;
    RemoteTexture(boolean mipmapped) { this.mipmapped=mipmapped; }
    @Override public void load(ResourceManager manager) {}

    /** Render thread. Replaces the pixels (RGBA8, mip level 0); atlases get a mip chain for the mipped layers. */
    void upload(int width,int height,byte[] rgba) {
        ByteBuffer pixels=MemoryUtil.memAlloc(rgba.length).put(rgba).flip();
        var transfer=new GlPixelState();
        try {
            RenderSystem.bindTexture(getId());
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            if(mipmapped) {
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,4);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST_MIPMAP_LINEAR);
            } else {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,0);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            // As vanilla's own textures: some renderers (entity blob shadows) rely on UVs outside [0,1] being clamped.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        } finally { transfer.close(); MemoryUtil.memFree(pixels); }
    }
}
