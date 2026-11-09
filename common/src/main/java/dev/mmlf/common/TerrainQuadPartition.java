package dev.mmlf.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Assigns captured section quads to occupied cells. Coordinates and vertices are section-relative. */
public final class TerrainQuadPartition {
    private TerrainQuadPartition() {}
    public record Cell(int x,int y,int z) {
        public Cell { if(x<0||x>15||y<0||y>15||z<0||z>15) throw new IllegalArgumentException("cell outside section"); }
    }
    /** Every quad is retained exactly once; an empty cell set leaves the entire layer for proxy rendering. */
    public static Map<Cell,List<byte[]>> partition(byte[] vertices,Collection<Cell> occupied) {
        Objects.requireNonNull(vertices); Objects.requireNonNull(occupied);
        if(vertices.length%128!=0) throw new IllegalArgumentException("incomplete block quad");
        if(occupied.isEmpty()) return Map.of();
        var cells=new HashSet<>(occupied);
        var ordered=new ArrayList<>(cells);
        ordered.sort(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z));
        var result=new HashMap<Cell,List<byte[]>>();
        ByteBuffer data=ByteBuffer.wrap(vertices).order(ByteOrder.LITTLE_ENDIAN);
        for(int offset=0;offset<vertices.length;offset+=128) {
            double x=0,y=0,z=0,nx=0,ny=0,nz=0;
            for(int i=0;i<4;i++) {
                int at=offset+i*32;
                float vx=data.getFloat(at),vy=data.getFloat(at+4),vz=data.getFloat(at+8);
                if(!Float.isFinite(vx)||!Float.isFinite(vy)||!Float.isFinite(vz)) throw new IllegalArgumentException("non-finite vertex");
                x+=vx/4.0; y+=vy/4.0; z+=vz/4.0;
                nx+=data.get(at+28)/127.0; ny+=data.get(at+29)/127.0; nz+=data.get(at+30)/127.0;
            }
            double length=Math.sqrt(nx*nx+ny*ny+nz*nz);
            if(length>1e-9) { x-=nx/length*0.001; y-=ny/length*0.001; z-=nz/length*0.001; }
            int cx=(int)Math.floor(x),cy=(int)Math.floor(y),cz=(int)Math.floor(z);
            Cell chosen=null;
            if(cx>=0&&cx<16&&cy>=0&&cy<16&&cz>=0&&cz<16) {
                Cell candidate=new Cell(cx,cy,cz);
                if(cells.contains(candidate)) chosen=candidate;
            }
            if(chosen==null) {
                double best=Double.POSITIVE_INFINITY;
                for(Cell c:ordered) {
                    double dx=x-(c.x()+.5),dy=y-(c.y()+.5),dz=z-(c.z()+.5),distance=dx*dx+dy*dy+dz*dz;
                    if(distance<best) { best=distance; chosen=c; }
                }
            }
            result.computeIfAbsent(chosen,ignored->new ArrayList<>()).add(Arrays.copyOfRange(vertices,offset,offset+128));
        }
        result.replaceAll((cell,quads)->List.copyOf(quads));
        return Map.copyOf(result);
    }
}
