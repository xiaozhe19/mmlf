package dev.mmlf.bridge.render;

import org.lwjgl.opengl.*;

/**
 * Known pixel transfer state for client-memory reads and uploads, restored afterwards: tightly packed rows and no
 * pixel buffer bound. With a pack or unpack buffer left bound by other code, a client pointer would be taken as an
 * offset into that buffer, and texture data would silently come from (or go to) the wrong place.
 */
final class GlPixelState implements AutoCloseable {
    private static final int[] NAMES={GL11.GL_PACK_ALIGNMENT,GL11.GL_UNPACK_ALIGNMENT,GL12.GL_PACK_ROW_LENGTH,GL12.GL_UNPACK_ROW_LENGTH,GL12.GL_PACK_SKIP_ROWS,GL12.GL_UNPACK_SKIP_ROWS,
        GL12.GL_PACK_SKIP_PIXELS,GL12.GL_UNPACK_SKIP_PIXELS,GL12.GL_PACK_IMAGE_HEIGHT,GL12.GL_UNPACK_IMAGE_HEIGHT,GL11.GL_PACK_SWAP_BYTES,GL11.GL_UNPACK_SWAP_BYTES};
    private final int[] values=new int[NAMES.length];
    private final int pack=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING),unpack=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
    GlPixelState() {
        for(int i=0;i<NAMES.length;i++) { values[i]=GL11.glGetInteger(NAMES[i]); GL11.glPixelStorei(NAMES[i],i<2?4:0); }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0); GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
    }
    @Override public void close() {
        for(int i=0;i<NAMES.length;i++) GL11.glPixelStorei(NAMES[i],values[i]);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,pack); GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpack);
    }
}
