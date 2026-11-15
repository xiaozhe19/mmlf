package dev.mmlf.bridge.mixin;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
 @org.spongepowered.asm.mixin.Shadow private int rightClickDelay;
 @org.spongepowered.asm.mixin.Unique private boolean mmlf$useHeld;
 /**
  * Every use goes through the Coordinator. Holding the key repeats it every 4 ticks as vanilla's own repeat does
  * (placing a row of blocks); the repeat also keeps an owner's held use of an item going (dev.mmlf.bridge.collab.InteractionService.useAir).
  */
 @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
 private void mmlf$use(CallbackInfo ci) {
  if(mmlf$localUse) return;
  Minecraft mc=(Minecraft)(Object)this;
  var player=mc.player;
  if(player==null || !dev.mmlf.bridge.transport.SessionLink.peers().anyWorldReady()) return;
  var level=mc.level;
  var target=mc.hitResult;
  int slot=player.getInventory().selected;
  var main=player.getMainHandItem().copy();
  var off=player.getOffhandItem().copy();
  var stood=player.position(); float yaw=player.getYRot(), pitch=player.getXRot();
  if(dev.mmlf.bridge.collab.InteractionService.requestUse(()->{
   // A late answer (a ray query that timed out) must not act where the player no longer looks or stands.
   if(player.position().distanceToSqr(stood)>0.25 || Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot()-yaw))>5 || Math.abs(player.getXRot()-pitch)>5) return;
   if(mc.player!=player || mc.level!=level || mc.screen!=null || player.getInventory().selected!=slot
       || !net.minecraft.world.item.ItemStack.matches(main,player.getMainHandItem())
       || !net.minecraft.world.item.ItemStack.matches(off,player.getOffhandItem())) return;
   var previous=mc.hitResult;
   mmlf$localUse=true;
   try { mc.hitResult=target; startUseItem(); }
   finally { mc.hitResult=previous; mmlf$localUse=false; }
  })) { mmlf$useHeld=true; rightClickDelay=4; ci.cancel(); }
 }

 @org.spongepowered.asm.mixin.Unique private boolean mmlf$localUse;
 @org.spongepowered.asm.mixin.Shadow private void startUseItem() { throw new AssertionError(); }
 @Inject(method="tick",at=@At("TAIL"))
 private void mmlf$release(CallbackInfo ci) {
  if(mmlf$useHeld && !((Minecraft)(Object)this).options.keyUse.isDown()) { mmlf$useHeld=false; dev.mmlf.bridge.collab.InteractionService.useReleased(); }
 }
 /** An owner using an item for the primary keeps using it while the primary holds the key; its own key is never down. */
 @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="handleKeybinds",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"))
 private void mmlf$keepUsing(net.minecraft.client.multiplayer.MultiPlayerGameMode mode,net.minecraft.world.entity.player.Player player,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
  if(dev.mmlf.bridge.collab.InteractionService.primaryUsing()) return;
  original.call(mode,player);
 }
 @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
 private void mmlf$attack(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) { if(dev.mmlf.bridge.collab.InteractionService.requestInteraction("attack")) ci.setReturnValue(true); }
 @Inject(method="continueAttack",at=@At("HEAD"),cancellable=true)
 private void mmlf$continue(boolean attacking,CallbackInfo ci) {
  // A background's own attack key is never down; its tick would stop the mining the primary is driving.
  if(dev.mmlf.bridge.collab.InteractionService.miningForPrimary()) { ci.cancel(); return; }
  if(!attacking) dev.mmlf.bridge.collab.InteractionService.attackReleased();
  if(attacking && dev.mmlf.bridge.collab.InteractionService.requestInteraction("attack_hold")) ci.cancel();
 }
 /** Hosted in-JVM runtimes end before this game releases its window and GL resources. */
 @Inject(method="close",at=@At("HEAD"))
 private void mmlf$destroying(CallbackInfo ci) { dev.mmlf.bridge.BridgeEntry.destroying(); }
 /** Leaving a world: every way out (the pause menu, a kick, a lost connection) goes through this one. */
 @Inject(method="disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V",at=@At("HEAD"))
 private void mmlf$leaving(net.minecraft.client.gui.screens.Screen screen,boolean keepResourcePacks,CallbackInfo ci) { dev.mmlf.bridge.BridgeEntry.primaryLeavingWorld((Minecraft)(Object)this); }
 @Inject(method="stop",at=@At("HEAD"))
 private void mmlf$closing(CallbackInfo ci) { dev.mmlf.bridge.BridgeEntry.closing(); }
 /** A hosted screen reports its opening, switching and closing from the place every screen change goes through. */
 @Inject(method="setScreen",at=@At("TAIL"))
 private void mmlf$screen(net.minecraft.client.gui.screens.Screen screen,CallbackInfo ci) { dev.mmlf.bridge.collab.GuiHost.screenChanged(((Minecraft)(Object)this).screen); }
 /** Only the primary pauses: a background showing a pausing screen would otherwise stop its server, and the machines in it. */
 @org.spongepowered.asm.mixin.injection.Redirect(method="runTick",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;isPauseScreen()Z"))
 private boolean mmlf$pause(net.minecraft.client.gui.screens.Screen screen) { return screen.isPauseScreen() && (dev.mmlf.bridge.transport.SessionLink.isPrimary() || !dev.mmlf.bridge.transport.SessionLink.connected()); }
 /**
  * Unified render layer, submitting runtime: records its dynamic content before each frame, and skips its own world
  * pass while nothing of it is needed (see {@code NativeWorld}). Skipping goes through the flag vanilla itself uses
  * for frames without a world, so every mod's hooks around the world pass stay paired.
  */
 @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="runTick",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
 private void mmlf$frame(net.minecraft.client.renderer.GameRenderer renderer,net.minecraft.client.DeltaTracker delta,boolean renderLevel,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
  Minecraft mc=(Minecraft)(Object)this;
  if(dev.mmlf.bridge.transport.SessionLink.isPrimary() || !dev.mmlf.bridge.platform.Experiments.GEOMETRY_REPLAY) { original.call(renderer,delta,renderLevel); return; }
  dev.mmlf.bridge.render.DynamicCapture.frame(mc,delta);
  boolean world=dev.mmlf.bridge.render.NativeWorld.renderWorld(renderLevel);
  if(!world) { dev.mmlf.bridge.render.FrameCompositor.drainExportedFrames(); dev.mmlf.bridge.render.NativeWorld.keepClientWorld(mc); }
  original.call(renderer,delta,world);
 }
 /** A background pauses with the primary: its integrated server reads this to stop ticking (and saves as it does). */
 @Inject(method="isPaused",at=@At("HEAD"),cancellable=true)
 private void mmlf$followPause(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) { if(dev.mmlf.bridge.collab.PlayerFollower.primaryPaused() || dev.mmlf.bridge.collab.SaveSync.frozen()) ci.setReturnValue(true); }
 @Inject(method="pickBlock",at=@At("HEAD"),cancellable=true)
 private void mmlf$pick(CallbackInfo ci) {if(dev.mmlf.bridge.collab.InteractionService.requestInteraction("pick")) ci.cancel();}
}
