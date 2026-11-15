package dev.mmlf.bridge.platform;

import dev.mmlf.common.PlayerShare.Own;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * A slot for showing, on the primary's client, an effect only a background knows ({@link OwnEffects}). The primary
 * cannot hold such an effect (it is not in its registry), so a slot stands for it: its instance is put into the
 * client player's effects only, never the server's, and vanilla's effect HUD and inventory list (and any mod drawing
 * effects) show it with the background's name, category, color and icon. It does nothing itself.
 */
public final class RemoteEffect extends MobEffect {
    public static final int SLOTS=16;
    public static ResourceLocation key(int slot) { return ResourceLocation.fromNamespaceAndPath("mmlf_bridge","remote_effect_"+slot); }

    public final int slot;
    /** The background's effect this slot stands for, or null (render thread reads, client thread writes). */
    public volatile Own bound;

    public RemoteEffect(int slot) { super(MobEffectCategory.NEUTRAL,0xFFFFFF); this.slot=slot; }

    @Override public Component getDisplayName() { Own own=bound; return Component.literal(own==null ? "?" : own.name()); }
    @Override public MobEffectCategory getCategory() {
        Own own=bound; if(own==null) return MobEffectCategory.NEUTRAL;
        return switch(own.category()) { case "beneficial" -> MobEffectCategory.BENEFICIAL; case "harmful" -> MobEffectCategory.HARMFUL; default -> MobEffectCategory.NEUTRAL; };
    }
    @Override public boolean isBeneficial() { return getCategory()==MobEffectCategory.BENEFICIAL; }
    @Override public int getColor() { Own own=bound; return own==null ? 0xFFFFFF : own.color(); }
}
