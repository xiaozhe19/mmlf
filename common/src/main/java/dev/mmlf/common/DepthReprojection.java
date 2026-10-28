package dev.mmlf.common;
import java.nio.*;
/** Forward-projects a native depth image into a current view; the local depth plane is the z-buffer. */
public final class DepthReprojection {
 private DepthReprojection() {}
 public static int composite(ByteBuffer base,ByteBuffer targetDepth,ByteBuffer source,ByteBuffer sourceDepth,int width,int height,float[] transform) {
  int n=Math.multiplyExact(Math.multiplyExact(width,height),4);
  if(width<1 || height<1 || transform.length!=16 || base.remaining()!=n || targetDepth.remaining()!=n || source.remaining()!=n || sourceDepth.remaining()!=n) throw new IllegalArgumentException("invalid reprojection planes");
  var depth=targetDepth.slice().order(ByteOrder.nativeOrder());var remote=sourceDepth.slice().order(ByteOrder.nativeOrder());
  int output=base.position(),input=source.position(),changed=0;
  for(int y=0;y<height;y++) {float ny=(2f*(y+0.5f)/height)-1;
   for(int x=0;x<width;x++) {
    int i=(y*width+x)*4;float d=remote.getFloat(i);if(!(d>=0 && d<1)) continue;
    float nx=(2f*(x+0.5f)/width)-1,nz=d*2-1;
    float w=transform[3]*nx+transform[7]*ny+transform[11]*nz+transform[15];if(w<=0) continue;
    float tx=(transform[0]*nx+transform[4]*ny+transform[8]*nz+transform[12])/w;
    float ty=(transform[1]*nx+transform[5]*ny+transform[9]*nz+transform[13])/w;
    float z=((transform[2]*nx+transform[6]*ny+transform[10]*nz+transform[14])/w+1)*0.5f;
    if(z<0 || z>=1) continue;
    int px=(int)Math.floor((tx+1)*0.5f*width),py=(int)Math.floor((ty+1)*0.5f*height);
    if(px<0 || px>=width || py<0 || py>=height) continue;
    int destination=(py*width+px)*4;if(z>=depth.getFloat(destination)) continue;
    for(int channel=0;channel<4;channel++) base.put(output+destination+channel,source.get(input+i+channel));
    depth.putFloat(destination,z);changed++;
   }
  }
  return changed;
 }
}
