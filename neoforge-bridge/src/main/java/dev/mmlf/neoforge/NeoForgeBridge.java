package dev.mmlf.neoforge;
import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.bridge.RemoteCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(value = "mmlf_bridge", dist = Dist.CLIENT)
public final class NeoForgeBridge {
    private static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,"mmlf_bridge");
    public NeoForgeBridge(IEventBus modBus) {
        BridgeCore.initialize("neoforge");
        for(int i=0;i<RemoteCatalog.POOL;i++) { int index=i; TABS.register(RemoteCatalog.poolKey(i).getPath(),()->RemoteCatalog.buildPool(index,CreativeModeTab.builder())); }
        TABS.register(modBus);
        NeoForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void onTick(ClientTickEvent.Post event) { BridgeCore.tick(Minecraft.getInstance()); }
}
