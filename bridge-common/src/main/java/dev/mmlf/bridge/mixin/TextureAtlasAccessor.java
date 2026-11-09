package dev.mmlf.bridge.mixin;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sprites of an atlas and its mip count, to find which rectangles animate. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
    @Accessor("texturesByName") Map<ResourceLocation,TextureAtlasSprite> mmlf$sprites();
    @Accessor("mipLevel") int mmlf$mipLevel();
}
