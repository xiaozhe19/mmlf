package dev.mmlf.bridge.mixin;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Forgetting the cached parameters makes the creative screen rebuild every tab and its search index natively. */
@Mixin(CreativeModeTabs.class)
public interface CreativeTabsAccessor {
 @Accessor("CACHED_PARAMETERS") static void mmlf$setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) { throw new AssertionError(); }
}
