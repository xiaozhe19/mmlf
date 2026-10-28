package dev.mmlf.neoforge;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@Mod(value = "mmlf_bridge", dist = Dist.CLIENT)
public final class NeoForgeBridge {
    public NeoForgeBridge() {
        BridgeCore.initialize("neoforge");
        NeoForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void onTick(ClientTickEvent.Post event) { BridgeCore.tick(Minecraft.getInstance()); }
}
