package dev.mmlf.bridge;
import org.lwjgl.opengl.*;
/** Restores host pixel transfer state, including row strides and buffer bindings. */
final class PixelTransfer implements AutoCloseable {
 private final int[] names={GL11.GL_PACK_ALIGNMENT,GL11.GL_UNPACK_ALIGNMENT,GL12.GL_PACK_ROW_LENGTH,GL12.GL_UNPACK_ROW_LENGTH,GL12.GL_PACK_SKIP_ROWS,GL12.GL_UNPACK_SKIP_ROWS,GL12.GL_PACK_SKIP_PIXELS,GL12.GL_UNPACK_SKIP_PIXELS,GL11.GL_PACK_SWAP_BYTES,GL11.GL_UNPACK_SWAP_BYTES};
 private final int[] values=new int[names.length];
 private final int pack=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING),unpack=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
 PixelTransfer() {
  for(int i=0;i<names.length;i++) { values[i]=GL11.glGetInteger(names[i]); GL11.glPixelStorei(names[i],i<2?1:0); }
  GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
 }
 public void close() {
  for(int i=0;i<names.length;i++) GL11.glPixelStorei(names[i],values[i]);
  GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,pack);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpack);
 }
}
