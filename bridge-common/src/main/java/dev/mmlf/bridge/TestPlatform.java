package dev.mmlf.bridge;

import dev.mmlf.common.MmlfLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;

/**
 * Release builds: the title screen's "MMLF 测试平台" button. It turns the player's session into a demo-scene session by
 * writing the scene bundled in the jar as the session's {@code scenario.json}; from there both runtimes run the same
 * {@link DemoScene} flow as a development session. Scene blocks of mods that are not installed are skipped.
 */
public final class TestPlatform {
    static final String LEVEL_NAME="MMLF 测试平台";
    private static final String SAVE_FOLDER="mmlf-test-platform";
    private TestPlatform() {}

    /** The button belongs only on a release primary's title screen, or a game still starting its release session. */
    static boolean shown() { return SessionBootstrap.release() && (BridgeCore.session()==null || BridgeCore.isPrimary()); }

    /** Null when the button can be pressed, else why not. */
    private static String unavailable() {
        var session=BridgeCore.session();
        if(SessionStatus.failed()) return "后台运行时启动失败，测试平台不可用";
        if(session==null) return "正在准备后台运行时…";
        if(Files.isRegularFile(session.root().resolve("scenario.json"))) return "本次启动已进入过测试平台；重启游戏可重新生成";
        if(WorldFollow.announcedAny()) return "本次启动已打开过普通存档；重启游戏后才能进入测试平台";
        return null;
    }

    private static void start() {
        var session=BridgeCore.session();
        if(session==null || unavailable()!=null) return;
        try(InputStream in=TestPlatform.class.getResourceAsStream("test-platform.json")) {
            if(in==null) throw new IOException("test-platform.json missing from the jar");
            Path target=session.root().resolve("scenario.json");
            // Written whole and then moved, so the background never reads half a scene.
            Path temp=session.root().resolve("scenario.json.tmp");
            Files.writeString(temp,new String(in.readAllBytes(),StandardCharsets.UTF_8));
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            MmlfLog.info("scene","test_platform_requested",Map.of("session",session.root().toString()));
        } catch(IOException e) {
            MmlfLog.error("scene","test_platform_failed",Map.of("exception",e.toString()));
        }
    }

    /**
     * Client thread, as a runtime creates the platform's world: the folder of a fresh save, removing the one an earlier
     * platform left in this runtime's saves. Only that fixed folder is ever removed.
     */
    static String freshSave(Minecraft mc) {
        Path dir=mc.getLevelSource().getBaseDir().resolve(SAVE_FOLDER);
        if(Files.exists(dir)) try(var paths=Files.walk(dir)) {
            for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch(IOException e) {
            MmlfLog.warn("scene","test_platform_cleanup_failed",Map.of("path",dir.toString(),"exception",e.toString()));
            return SAVE_FOLDER+"-"+System.currentTimeMillis();
        }
        return SAVE_FOLDER;
    }

    /** The title screen button; it follows the session's state while the screen is open. */
    static Button button(int x,int y,int width) {
        return new Button(x,y,width,20,Component.literal("MMLF 测试平台"),b->start(),narration->narration.get()) {
            private String reason="";
            @Override protected void renderWidget(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
                String now=unavailable();
                active=now==null;
                if(!java.util.Objects.equals(now,reason)) {
                    reason=now;
                    setTooltip(Tooltip.create(Component.literal(now==null?"进入一个平地创造世界，两个加载器的方块、Mod 和生物都摆在一起":now)));
                }
                super.renderWidget(graphics,mouseX,mouseY,delta);
            }
        };
    }

    public static void addTo(net.minecraft.client.gui.screens.Screen screen,java.util.function.Consumer<Button> add) {
        if(!shown()) return;
        // One row above the title screen's first button row (height/4+48).
        add.accept(button(screen.width/2-100,screen.height/4+24,200));
    }
}
