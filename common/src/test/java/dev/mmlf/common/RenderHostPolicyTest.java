package dev.mmlf.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RenderHostPolicyTest {
    @Test void otherRuntime() {
        assertEquals("neoforge",RenderHostPolicy.other("fabric"));
        assertEquals("fabric",RenderHostPolicy.other("neoforge"));
        assertThrows(IllegalArgumentException.class,()->RenderHostPolicy.other("forge"));
    }
}
