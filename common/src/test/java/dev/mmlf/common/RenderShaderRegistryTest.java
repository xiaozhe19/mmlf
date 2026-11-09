package dev.mmlf.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderShaderRegistryTest {
    @Test void unknownAndMultipleProvidersDoNotMasqueradeAsInactive() {
        var registry=new RenderShaderRegistry();
        assertEquals(RenderShaderRegistry.State.UNKNOWN,registry.probe());
        registry.register("one",()->RenderShaderRegistry.State.INACTIVE);
        registry.register("unknown",()->RenderShaderRegistry.State.UNKNOWN);
        assertEquals(RenderShaderRegistry.State.UNKNOWN,registry.probe());
        registry.register("active",()->RenderShaderRegistry.State.ACTIVE);
        assertEquals(RenderShaderRegistry.State.ACTIVE,registry.probe());
    }
}
