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
        dev.mmlf.bridge.ItemAccess.install(new NeoForgeItems());
        for(int i=0;i<RemoteCatalog.POOL;i++) { int index=i; TABS.register(RemoteCatalog.poolKey(i).getPath(),()->RemoteCatalog.buildPool(index,CreativeModeTab.builder())); }
        TABS.register(modBus);
        NeoForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void onTick(ClientTickEvent.Post event) { BridgeCore.tick(Minecraft.getInstance()); }
    /**
     * A background runtime has no visible window, so a loading screen that waits for a click would hold it forever.
     * When loading reported only warnings, they go to the log and the game continues to its title screen, as the
     * screen's own button would; with any error the screen stays and the session reports the runtime as not ready.
     */
    @SubscribeEvent public void onScreen(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        if(!(event.getNewScreen() instanceof net.neoforged.neoforge.client.gui.LoadingErrorScreen) || !dev.mmlf.bridge.RuntimeContext.current().background()) return;
        var issues=net.neoforged.fml.ModLoader.getLoadingIssues();
        if(issues.stream().anyMatch(i->i.severity()==net.neoforged.fml.ModLoadingIssue.Severity.ERROR)) return;
        for(var issue:issues) dev.mmlf.common.MmlfLog.warn("loader","loading_warning",java.util.Map.of("key",issue.translationKey(),"args",String.valueOf(issue.translationArgs()),
            "file",String.valueOf(issue.affectedPath())));
        dev.mmlf.common.MmlfLog.info("loader","warnings_screen_skipped",java.util.Map.of("warnings",issues.size()));
        event.setNewScreen(new net.minecraft.client.gui.screens.TitleScreen());
    }
}
