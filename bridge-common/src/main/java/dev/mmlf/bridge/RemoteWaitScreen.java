package dev.mmlf.bridge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.BooleanSupplier;

/**
 * Primary, just entered a world: holds the game paused until the remote has opened the same save, so remote content
 * is there from the first moment. The player may go on without it.
 */
final class RemoteWaitScreen extends Screen {
    private final BooleanSupplier waiting;

    RemoteWaitScreen(BooleanSupplier waiting) { super(Component.literal("MMLF")); this.waiting=waiting; }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("直接进入（不等远端）"),button->onClose()).bounds(width/2-100,height/2+34,200,20).build());
    }
    @Override public void tick() { if(!waiting.getAsBoolean()) onClose(); }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial) {
        super.render(graphics,mouseX,mouseY,partial);
        var view=SessionStatus.view(minecraft);
        if(view==null) return;
        int barWidth=Math.min(240,width-40), x=width/2-barWidth/2, y=height/2-30;
        graphics.drawCenteredString(font,view.title(),width/2,y,0xFFFFFFFF);
        y+=14;
        for(var line:font.split(Component.literal(view.text()),Math.min(width-40,420))) { graphics.drawCenteredString(font,line,width/2,y,0xFFB0B0B0); y+=font.lineHeight; }
        y+=6;
        graphics.fill(x,y,x+barWidth,y+4,0xFF3A3A3A);
        graphics.fill(x,y,x+Math.round(barWidth*Math.clamp(view.progress(),0f,1f)),y+4,view.color());
    }
    /** Pausing: the primary's world stands still, mobs included, while the player waits. */
    @Override public boolean isPauseScreen() { return true; }
}
