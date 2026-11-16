package dev.mmlf.bridge.transport;


import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import dev.mmlf.common.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;
import java.util.Map;
import static dev.mmlf.common.MessageRouter.Scope.*;

/**
 * Transport layer, session settings: a background follows the primary's language, switching before it opens a world
 * (moved from BridgeCore, design S7).
 */
public final class LanguageSync {
    /** After this runtime switched to the primary's language (the entry answers the item catalog again, in it). */
    private static volatile java.util.function.Consumer<String> languageChanged=primary->{};
    public static void onLanguageChanged(java.util.function.Consumer<String> hook) { languageChanged=hook; }
    private LanguageSync() {}
    public static boolean initialLanguageReceived, initialLanguageWaitReported;
    static long initialLanguageWaitStarted, nextLanguageAnnouncement;
    /** Do not enter the far world in the middle of the first language reload; old peers get a bounded fallback. */
    /**
     * Primary, any thread: the host language every two seconds until a background has a world. Also from a generation
     * worker waiting for the background (CoGeneration), while the client thread waits for the server without ticking.
     */
    public static synchronized void announceLanguage() {
        Minecraft mc=Minecraft.getInstance();
        if(!SessionLink.isPrimary() || !SessionLink.connected() || SessionLink.peers().anyWorldReady() || mc.options==null || System.nanoTime()<nextLanguageAnnouncement) return;
        nextLanguageAnnouncement=System.nanoTime()+2_000_000_000L;
        SessionLink.sendReliable("event","*",new Event("mmlf:language",Map.of("code",mc.options.languageCode)));
    }
    public static boolean initialLanguageReady() {
        if(SessionLink.isPrimary() || !SessionLink.joined()) return true;
        if(languageBusy()) return false;
        if(initialLanguageReceived) return true;
        long now=System.nanoTime();
        if(initialLanguageWaitStarted==0) initialLanguageWaitStarted=now;
        if(now-initialLanguageWaitStarted<30_000_000_000L) return false;
        if(!initialLanguageWaitReported) {
            initialLanguageWaitReported=true;
            MmlfLog.warn("connection","initial_language_timeout",Map.of("received",initialLanguageReceived,"busy",languageBusy()));
        }
        return true;
    }
    /** A language that arrived while a resource reload was running; applied once it is done (see {@link #followLanguage}). */
    public static String pendingLanguage;
    /** Background: a language switch is waiting, so it opens no world until it is done. */
    public static boolean languageBusy() { return pendingLanguage!=null; }
    /**
     * Owner, client thread: switches to the primary's language, then sends a fresh catalogue so names and tabs follow.
     * Only the language is reloaded (what vanilla's own reload does for it), synchronously: the full resource reload
     * used before also rebuilt fonts and atlases, and in a joined world the NeoForge background then crashed seconds
     * later drawing text with a font image the reload had freed ("Image is not allocated", 2026-11-09 and 11-16). It
     * also could not run while a world loaded. Text in another language needs no other resources: a font follows the
     * font options, not the language.
     */
    public static void followLanguage(Minecraft mc,String code,String primaryId) {
        if(code==null || code.equals(mc.options.languageCode) || mc.getLanguageManager().getLanguage(code)==null) { pendingLanguage=null; return; }
        // A running resource reload (startup, a pack change) loads the language itself as it ends: wait for it.
        if(mc.getOverlay()!=null) { pendingLanguage=code; return; }
        pendingLanguage=null;
        MmlfLog.info("connection","language_follow",Map.of("from",mc.options.languageCode,"to",code));
        mc.getLanguageManager().setSelected(code); mc.options.languageCode=code;
        try { mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager()); }
        catch(RuntimeException error) { MmlfLog.error("connection","language_reload_failed",Map.of("exception",error.toString())); return; }
        languageChanged.accept(primaryId);
    }
}
