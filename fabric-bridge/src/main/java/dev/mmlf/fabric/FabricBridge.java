package dev.mmlf.fabric;
import dev.mmlf.bridge.BridgeCore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
public final class FabricBridge implements ClientModInitializer {
    public void onInitializeClient() {
        BridgeCore.initialize("fabric");
        ClientTickEvents.END_CLIENT_TICK.register(BridgeCore::tick);
    }
}
