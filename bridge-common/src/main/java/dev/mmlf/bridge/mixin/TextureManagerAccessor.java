package dev.mmlf.bridge.mixin;

import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Registered textures by name, to name the texture a recorded render type binds. */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
    @Accessor("byPath") Map<ResourceLocation,AbstractTexture> mmlf$byPath();
}
