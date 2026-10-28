package dev.mmlf.neoforgetest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
/** A plain screen with a text field, buttons and scrolling; every action is logged so a test can check it ran here. */
final class TestScreens {
 static void open(BlockPos pos) { Minecraft.getInstance().setScreen(new TestScreen(pos)); }
 static final class TestScreen extends Screen {
  private final BlockPos pos; private EditBox field; private int applied, scroll;
  TestScreen(BlockPos pos) { super(Component.literal("MMLF NeoForge Test Screen")); this.pos=pos; }
  @Override protected void init() {
   field=new EditBox(font,width/2-80,height/2-40,160,20,Component.literal("text"));
   addRenderableWidget(field); setInitialFocus(field);
   addRenderableWidget(Button.builder(Component.literal("Apply"),b->{applied++;System.out.println("MMLF TEST SCREEN loader=neoforge action=apply text="+field.getValue()+" applied="+applied+" scroll="+scroll+" pos="+pos.toShortString());}).bounds(width/2-80,height/2-10,160,20).build());
   addRenderableWidget(Button.builder(Component.literal("Close"),b->onClose()).bounds(width/2-80,height/2+20,160,20).build());
  }
  @Override public boolean mouseScrolled(double x,double y,double sx,double sy) {
   scroll+=(int)Math.signum(sy); System.out.println("MMLF TEST SCREEN loader=neoforge action=scroll scroll="+scroll); return true;
  }
  @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
   super.render(graphics,mouseX,mouseY,delta);
   graphics.drawCenteredString(font,title,width/2,height/2-70,0xFFFFFF);
   graphics.drawCenteredString(font,Component.literal("applied "+applied+" / scroll "+scroll),width/2,height/2-56,0xA0E0FF);
  }
  @Override public void removed() { System.out.println("MMLF TEST SCREEN loader=neoforge action=closed applied="+applied+" scroll="+scroll); }
 }
}
