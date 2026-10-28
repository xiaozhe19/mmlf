package dev.mmlf.fabric;
import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.bridge.RemoteCatalog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
public final class FabricBridge implements ClientModInitializer {
    public void onInitializeClient() {
        BridgeCore.initialize("fabric");
        for(int i=0;i<RemoteCatalog.POOL;i++) Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,RemoteCatalog.poolKey(i),RemoteCatalog.buildPool(i,FabricItemGroup.builder()));
        ClientTickEvents.END_CLIENT_TICK.register(BridgeCore::tick);
    }
}
