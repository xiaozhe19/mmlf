package dev.mmlf.bridge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown on the primary before it opens a world, until a background runtime reports its own world is loaded. */
final class WaitingScreen extends Screen {
    private final long started=System.nanoTime();
    private final Runnable skip;

    WaitingScreen(Runnable skip) { super(Component.literal("MMLF")); this.skip=skip; }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("直接进入（不等待）"),button->skip.run()).bounds(width/2-100,height/2+30,200,20).build());
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial) {
        super.render(graphics,mouseX,mouseY,partial);
        long seconds=(System.nanoTime()-started)/1_000_000_000L;
        String peers=BridgeCore.peerCount()>=2?"后台 Runtime 已连接，正在加载世界":"正在启动后台 Runtime";
        graphics.drawCenteredString(font,"MMLF：等待其他 Runtime",width/2,height/2-30,0xFFFFFF);
        graphics.drawCenteredString(font,peers+"… "+seconds+" 秒",width/2,height/2-10,0xA0A0A0);
    }
    @Override public boolean shouldCloseOnEsc() { return false; }
}
