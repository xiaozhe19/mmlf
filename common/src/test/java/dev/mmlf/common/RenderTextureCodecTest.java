package dev.mmlf.common;

import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderTextureCodecTest {
    @Test void carriesResourceAndSceneIdentityWithoutSharingBuffers() throws Exception {
        byte[] pixels={1,2,3,4};
        var texture=new RenderTextureCodec.Texture("fabric","world",2,"block-atlas","rgba8",1,1,pixels);
        pixels[0]=9;
        byte[] encoded=RenderTextureCodec.encode(texture);
        var decoded=RenderTextureCodec.decode(encoded);
        assertEquals(2,decoded.generation());
        assertEquals("block-atlas",decoded.resource());
        assertArrayEquals(new byte[]{1,2,3,4},decoded.data());
        assertThrows(IOException.class,()->RenderTextureCodec.decode(Arrays.copyOf(encoded,encoded.length-1)));
    }
}
