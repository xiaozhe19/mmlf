package dev.mmlf.common;
import java.nio.*;
/** Composites equal-projection, bottom-up RGBA and native-endian OpenGL float depth planes. */
public final class PixelCompositor {
 private PixelCompositor() {}
 public static int composite(ByteBuffer local,ByteBuffer localDepth,ByteBuffer remote,ByteBuffer remoteDepth,int pixelCount) {
  int length=Math.multiplyExact(pixelCount,4);
  if(pixelCount<0 || local.remaining()!=length || localDepth.remaining()!=length || remote.remaining()!=length || remoteDepth.remaining()!=length)
   throw new IllegalArgumentException("plane length mismatch");
  ByteBuffer ld=localDepth.slice().order(ByteOrder.nativeOrder()),rd=remoteDepth.slice().order(ByteOrder.nativeOrder());
  int lp=local.position(),rp=remote.position(), changed=0;
  for(int i=0;i<length;i+=4) if(Depth.remoteWins(ld.getFloat(i),rd.getFloat(i))) {
   for(int c=0;c<4;c++) local.put(lp+i+c,remote.get(rp+i+c));
   changed++;
  }
  return changed;
 }
 public static void overlay(ByteBuffer base,ByteBuffer premultiplied) {
  if(base.remaining()!=premultiplied.remaining() || base.remaining()%4!=0) throw new IllegalArgumentException("overlay plane length mismatch");
  int offset=base.position(),source=premultiplied.position();
  for(int i=0;i<base.remaining();i+=4) {
   int alpha=premultiplied.get(source+i+3)&255;if(alpha==0) continue;
   for(int channel=0;channel<3;channel++) {
    int value=(premultiplied.get(source+i+channel)&255)+((base.get(offset+i+channel)&255)*(255-alpha)+127)/255;
    base.put(offset+i+channel,(byte)Math.min(255,value));
   }
   base.put(offset+i+3,(byte)255);
  }
 }
}
