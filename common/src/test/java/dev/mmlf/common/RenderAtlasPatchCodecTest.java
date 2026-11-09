package dev.mmlf.common;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderAtlasPatchCodecTest {
    @Test void roundTripsPatchesPerLevel() throws Exception {
        var batch=new RenderAtlasPatchCodec.Batch("neoforge",4,1024,512,List.of(
            new RenderAtlasPatchCodec.Patch(0,16,32,16,16,new byte[16*16*4]),
            new RenderAtlasPatchCodec.Patch(1,8,16,8,8,new byte[8*8*4])));
        var decoded=RenderAtlasPatchCodec.decode(RenderAtlasPatchCodec.encode(batch));
        assertEquals(4,decoded.generation()); assertEquals(1024,decoded.atlasWidth());
        assertEquals(2,decoded.patches().size());
        assertEquals(1,decoded.patches().get(1).level()); assertEquals(8*8*4,decoded.patches().get(1).rgba().length);
    }
    @Test void rejectsPatchOutsideAtlasAndTruncation() throws Exception {
        byte[] outside=RenderAtlasPatchCodec.encode(new RenderAtlasPatchCodec.Batch("fabric",1,16,16,List.of(new RenderAtlasPatchCodec.Patch(0,8,8,16,16,new byte[16*16*4]))));
        assertThrows(IOException.class,()->RenderAtlasPatchCodec.decode(outside));
        byte[] ok=RenderAtlasPatchCodec.encode(new RenderAtlasPatchCodec.Batch("fabric",1,16,16,List.of(new RenderAtlasPatchCodec.Patch(0,0,0,16,16,new byte[16*16*4]))));
        assertThrows(IOException.class,()->RenderAtlasPatchCodec.decode(Arrays.copyOf(ok,ok.length-1)));
        assertThrows(IllegalArgumentException.class,()->new RenderAtlasPatchCodec.Patch(0,0,0,2,2,new byte[3]));
    }
}
