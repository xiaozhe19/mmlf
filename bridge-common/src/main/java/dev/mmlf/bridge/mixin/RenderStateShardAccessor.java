package dev.mmlf.bridge.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A render type's own name (such as {@code entity_cutout_no_cull}), which identifies its vanilla factory. */
@Mixin(RenderStateShard.class)
public interface RenderStateShardAccessor {
    @Accessor("name") String mmlf$name();
}
