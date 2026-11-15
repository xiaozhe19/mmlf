package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.OwnEffects;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.MobEffectTextureManager;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A slot standing for a background's own effect is drawn with that effect's icon, as the background has it. */
@Mixin(MobEffectTextureManager.class)
public abstract class EffectTextureMixin {
    @Inject(method="get",at=@At("HEAD"),cancellable=true)
    private void mmlf$remote(Holder<MobEffect> effect,CallbackInfoReturnable<TextureAtlasSprite> cir) {
        TextureAtlasSprite sprite=OwnEffects.sprite(effect);
        if(sprite!=null) cir.setReturnValue(sprite);
    }
}
