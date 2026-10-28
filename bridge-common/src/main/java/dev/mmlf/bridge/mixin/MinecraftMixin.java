package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
 @org.spongepowered.asm.mixin.Shadow private int rightClickDelay;
 @org.spongepowered.asm.mixin.Unique private boolean mmlf$useHeld;
 @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
 private void mmlf$use(CallbackInfo ci) {
  if(mmlf$useHeld) {rightClickDelay=4;ci.cancel();return;}
  if(BridgeCore.requestInteraction()) {mmlf$useHeld=true;rightClickDelay=4;ci.cancel();}
 }
 @Inject(method="tick",at=@At("TAIL"))
 private void mmlf$release(CallbackInfo ci) { if(!((Minecraft)(Object)this).options.keyUse.isDown()) mmlf$useHeld=false; }
 @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
 private void mmlf$attack(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) { if(BridgeCore.requestInteraction("attack")) ci.setReturnValue(true); }
 @Inject(method="continueAttack",at=@At("HEAD"),cancellable=true)
 private void mmlf$continue(boolean attacking,CallbackInfo ci) {
  // A background's own attack key is never down; its tick would stop the mining the primary is driving.
  if(BridgeCore.miningForPrimary()) { ci.cancel(); return; }
  if(!attacking) BridgeCore.attackReleased();
  if(attacking && BridgeCore.requestInteraction("attack")) ci.cancel();
 }
 @Inject(method="stop",at=@At("HEAD"))
 private void mmlf$closing(CallbackInfo ci) { BridgeCore.closing(); }
 /** A hosted screen reports its opening, switching and closing from the place every screen change goes through. */
 @Inject(method="setScreen",at=@At("TAIL"))
 private void mmlf$screen(net.minecraft.client.gui.screens.Screen screen,CallbackInfo ci) { dev.mmlf.bridge.GuiHost.screenChanged(((Minecraft)(Object)this).screen); }
 /** Only the primary pauses: a background showing a pausing screen would otherwise stop its server, and the machines in it. */
 @org.spongepowered.asm.mixin.injection.Redirect(method="runTick",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;isPauseScreen()Z"))
 private boolean mmlf$pause(net.minecraft.client.gui.screens.Screen screen) { return screen.isPauseScreen() && (BridgeCore.isPrimary() || !BridgeCore.connected()); }
 @Inject(method="pickBlock",at=@At("HEAD"),cancellable=true)
 private void mmlf$pick(CallbackInfo ci) {if(BridgeCore.requestInteraction("pick")) ci.cancel();}
}
