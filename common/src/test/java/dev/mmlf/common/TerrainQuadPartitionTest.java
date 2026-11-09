package dev.mmlf.common;

import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TerrainQuadPartitionTest {
    private static byte[] face(float x,float y,float z,byte nx,byte ny,byte nz) {
        var b=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<4;i++) { int o=i*32; b.putFloat(o,x); b.putFloat(o+4,y); b.putFloat(o+8,z); b.put(o+28,nx); b.put(o+29,ny); b.put(o+30,nz); }
        return b.array();
    }
    @Test void faceOnBoundaryBelongsToCellBehindNormal() {
        var a=new TerrainQuadPartition.Cell(0,0,0); var b=new TerrainQuadPartition.Cell(1,0,0);
        var map=TerrainQuadPartition.partition(face(1,.5f,.5f,(byte)127,(byte)0,(byte)0),List.of(a,b));
        assertEquals(Set.of(a),map.keySet());
    }
    @Test void fallsBackToNearestCellAndKeepsBytes() {
        var near=new TerrainQuadPartition.Cell(2,0,0); var far=new TerrainQuadPartition.Cell(15,0,0);
        byte[] quad=face(3.5f,.5f,.5f,(byte)0,(byte)0,(byte)0);
        var map=TerrainQuadPartition.partition(quad,List.of(far,near));
        assertArrayEquals(quad,map.get(near).get(0));
        assertTrue(TerrainQuadPartition.partition(quad,List.of()).isEmpty());
    }
    @Test void rejectsMalformedAndNonFiniteVertices() {
        assertThrows(IllegalArgumentException.class,()->TerrainQuadPartition.partition(new byte[1],List.of(new TerrainQuadPartition.Cell(0,0,0))));
        assertThrows(IllegalArgumentException.class,()->TerrainQuadPartition.partition(face(Float.NaN,0,0,(byte)0,(byte)0,(byte)0),List.of(new TerrainQuadPartition.Cell(0,0,0))));
    }
}
