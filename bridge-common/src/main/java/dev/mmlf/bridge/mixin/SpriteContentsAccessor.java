package dev.mmlf.bridge.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A sprite's pixels as loaded (a background reads its own effects' icons from them, {@link dev.mmlf.bridge.collab.OwnEffects}). */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage") NativeImage mmlf$originalImage();
}
