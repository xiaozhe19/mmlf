package dev.mmlf.bridge.platform;

import dev.mmlf.common.PlayerShare;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Whether a cure the primary's player received removes an effect of this runtime. NeoForge effects carry their own
 * cures (milk, honey, totem, mods' own); its bridge installs that. Without it (vanilla, Fabric) the cures that remove
 * every effect do ({@link PlayerShare#curesAll}).
 */
public interface EffectCures {
    boolean cures(MobEffectInstance effect,String cure);

    final class Loader { static volatile EffectCures current; private Loader() {} }
    static void install(EffectCures cures) { Loader.current=cures; }

    static boolean removes(MobEffectInstance effect,String cure) {
        if(PlayerShare.CURE_ALL.equals(cure) || PlayerShare.CURE_DEATH.equals(cure)) return true;
        EffectCures loader=Loader.current;
        return loader!=null ? loader.cures(effect,cure) : PlayerShare.curesAll(cure);
    }
}
