package dev.mmlf.bridge;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.nio.file.*;
import java.util.List;

/** Why the background runtime is unavailable; the game itself goes on without it. */
final class SessionErrorScreen extends Screen {
    private final String message;
    private final Path location;
    private final Screen parent;
    private List<FormattedCharSequence> lines=List.of();

    SessionErrorScreen(String message,Path location,Screen parent) {
        super(Component.literal("MMLF：远端未能启动"));
        this.message=message; this.location=location; this.parent=parent;
    }

    @Override protected void init() {
        lines=font.split(Component.literal(message),Math.min(width-40,420));
        int bottom=Math.min(height-28,top()+20+lines.size()*font.lineHeight+16);
        if(location!=null) addRenderableWidget(Button.builder(Component.literal("打开日志文件夹"),button->{
            Path folder=Files.isDirectory(location)?location:location.getParent();
            if(folder!=null) Util.getPlatform().openFile(folder.toFile());
        }).bounds(width/2-154,bottom,150,20).build());
        addRenderableWidget(Button.builder(Component.literal("继续（不使用远端）"),button->onClose())
            .bounds(location==null?width/2-75:width/2+4,bottom,150,20).build());
    }
    private int top() { return Math.max(20,height/2-(lines.size()*font.lineHeight)/2-40); }

    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial) {
        super.render(graphics,mouseX,mouseY,partial);
        int y=top();
        graphics.drawCenteredString(font,title,width/2,y,0xFFFF5555);
        y+=20;
        for(var line:lines) { graphics.drawCenteredString(font,line,width/2,y,0xFFE0E0E0); y+=font.lineHeight; }
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
