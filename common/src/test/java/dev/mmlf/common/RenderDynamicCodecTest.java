package dev.mmlf.common;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderDynamicCodecTest {
    @Test void roundTripsBatchesWithOriginAndIdentity() throws Exception {
        var batch=new RenderDynamicCodec.Batch("entity_cutout_no_cull","create:textures/entity/x.png","NEW_ENTITY","QUADS",4,new byte[144]);
        var frame=new RenderDynamicCodec.Frame("neoforge","minecraft:overworld",3,17,99,1024.5,-60.25,-3e6,List.of(batch));
        var decoded=RenderDynamicCodec.decode(RenderDynamicCodec.encode(frame));
        assertEquals("neoforge",decoded.runtime());
        assertEquals(3,decoded.generation()); assertEquals(17,decoded.frame()); assertEquals(99,decoded.cameraSequence());
        assertEquals(-3e6,decoded.originZ());
        assertEquals(1,decoded.batches().size());
        var b=decoded.batches().get(0);
        assertEquals("entity_cutout_no_cull",b.renderType()); assertEquals("NEW_ENTITY",b.format()); assertEquals(4,b.vertexCount());
        assertEquals(144,b.vertices().length);
    }
    @Test void rejectsTruncatedAndTrailingData() throws Exception {
        var frame=new RenderDynamicCodec.Frame("fabric","minecraft:overworld",0,1,0,0,0,0,
            List.of(new RenderDynamicCodec.Batch("solid","","BLOCK","QUADS",4,new byte[128])));
        byte[] encoded=RenderDynamicCodec.encode(frame);
        assertThrows(IOException.class,()->RenderDynamicCodec.decode(Arrays.copyOf(encoded,encoded.length-1)));
        assertThrows(IOException.class,()->RenderDynamicCodec.decode(Arrays.copyOf(encoded,encoded.length+1)));
    }
    @Test void emptyFrameIsValid() throws Exception {
        var frame=new RenderDynamicCodec.Frame("fabric","minecraft:the_nether",2,9,0,1,2,3,List.of());
        assertTrue(RenderDynamicCodec.decode(RenderDynamicCodec.encode(frame)).batches().isEmpty());
    }
}
