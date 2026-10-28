package dev.mmlf.bridge;

import dev.mmlf.common.GuiCoordinates;
import dev.mmlf.common.GuiInputSequencer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The primary's stand-in for a screen running in another runtime. Being an ordinary screen, it releases the mouse and
 * keeps clicks away from the world; it draws the owner's frames and forwards every pointer and key event, ESC included,
 * so the owner's native screen decides what they mean and when it closes.
 */
final class RemoteGuiScreen extends net.minecraft.client.gui.screens.Screen {
    private boolean closedByOwner;
    RemoteGuiScreen() { super(Component.literal("MMLF remote screen")); }

    void closedByOwner() { closedByOwner=true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    /** The owner's frame carries its own background. */
    @Override public void renderBackground(GuiGraphics graphics,int mouseX,int mouseY,float delta) { }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        GuiClient.flushMovement();
        GuiClient.draw(graphics,width,height);
        if(GuiClient.stalled()) graphics.drawCenteredString(font,Component.literal("MMLF: 等待 "+GuiClient.runtime()+" 的界面…"),width/2,8,0xFFFFFF);
    }
    @Override public void removed() {
        // Replaced by another local screen or closed from here: the owner closes its screen too.
        if(!closedByOwner) GuiClient.requestClose("primary_closed");
    }
    @Override public void onClose() { GuiClient.requestClose("primary_closed"); }

    private double nx(double x) { return GuiCoordinates.normalize(x,width); }
    private double ny(double y) { return GuiCoordinates.normalize(y,height); }
    private static int modifiers() {
        int m=0;
        if(hasShiftDown()) m|=org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
        if(hasControlDown()) m|=org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
        if(hasAltDown()) m|=org.lwjgl.glfw.GLFW.GLFW_MOD_ALT;
        return m;
    }
    @Override public void mouseMoved(double x,double y) { GuiClient.move(nx(x),ny(y)); }
    @Override public boolean mouseClicked(double x,double y,int button) {
        GuiClient.edge(GuiInputSequencer.PRESS,nx(x),ny(y),button,0,0,-1,-1,modifiers(),null); return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        GuiClient.edge(GuiInputSequencer.RELEASE,nx(x),ny(y),button,0,0,-1,-1,modifiers(),null); return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) { GuiClient.move(nx(x),ny(y)); return true; }
    @Override public boolean mouseScrolled(double x,double y,double sx,double sy) {
        GuiClient.edge(GuiInputSequencer.SCROLL,nx(x),ny(y),-1,sx,sy,-1,-1,modifiers(),null); return true;
    }
    @Override public boolean keyPressed(int key,int scancode,int modifiers) {
        // The keyboard handler calls this for presses and repeats alike; a key already down is a repeat.
        String kind=GuiClient.holdingKey(key)?GuiInputSequencer.KEY_REPEAT:GuiInputSequencer.KEY_DOWN;
        GuiClient.edge(kind,0,0,-1,0,0,key,scancode,modifiers,null); return true;
    }
    @Override public boolean keyReleased(int key,int scancode,int modifiers) {
        GuiClient.edge(GuiInputSequencer.KEY_UP,0,0,-1,0,0,key,scancode,modifiers,null); return true;
    }
    @Override public boolean charTyped(char c,int modifiers) {
        GuiClient.edge(GuiInputSequencer.CHAR,0,0,-1,0,0,-1,-1,modifiers,String.valueOf(c)); return true;
    }
}
