package dev.mmlf.common;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderHostPolicyTest {
    @Test void shaderOwnerAndWindowOwnerAreIndependent() {
        assertEquals("fabric",RenderHostPolicy.shaderCandidate("neoforge",Set.of("fabric")));
        assertEquals("neoforge",RenderHostPolicy.shaderCandidate("neoforge",Set.of("fabric","neoforge")));
        assertEquals("fabric",RenderHostPolicy.configured("neoforge","remote"));
        assertEquals("neoforge",RenderHostPolicy.configured("fabric","remote"));
        assertEquals("fabric",RenderHostPolicy.configured("fabric",null));
        assertThrows(IllegalArgumentException.class,()->RenderHostPolicy.configured("fabric","unknown"));
    }
}
