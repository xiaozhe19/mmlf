package dev.mmlf.bridge.collab;
import dev.mmlf.bridge.transport.PoseChannel;

import dev.mmlf.bridge.transport.SessionLink;

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
 * Collaboration layer: the player's uses, attacks and mining of what another runtime owns, and the ray tests and native
 * actions behind them (moved from BridgeCore, design S7).
 */
public final class InteractionService {
    private InteractionService() {}
    static long lastAttack;
    record PendingUse(long created,Runnable fallback) {}
    static final Map<String,PendingUse> pendingUses=new java.util.HashMap<>();

    public static boolean requestInteraction() { return requestInteraction("use"); }
    public static boolean requestUse(Runnable local) { return requestInteraction("use",local); }
    public static boolean requestInteraction(String action) { return requestInteraction(action,null); }

    static boolean requestInteraction(String action,Runnable local) {
        if(!SessionLink.isPrimary() || !SessionLink.connected() || SessionLink.peers().online().size()<2 || PoseChannel.primaryPose==null) return false;
        Minecraft mc=Minecraft.getInstance(); if(mc.player==null) return false;
        // "attack" is a click, "attack_hold" the button kept down: both keep a block breaking, only a click hits an entity.
        boolean attack=action.equals("attack") || action.equals("attack_hold");
        if(attack && System.nanoTime()-lastAttack<150_000_000L) return true;
        if(attack) lastAttack=System.nanoTime();
        Pose pose=PoseChannel.primaryPose;
        MmlfLog.debug("input","request",Map.of("action",action,"cameraId",pose.cameraId(),"dimension",pose.dimension()));
        boolean offhand="use".equals(action) && mc.player.getMainHandItem().isEmpty() && !mc.player.getOffhandItem().isEmpty();
        var item=RemoteItems.held(offhand ? mc.player.getOffhandItem() : mc.player.getMainHandItem());
        String actionId=java.util.UUID.randomUUID().toString();
        if("use".equals(action)) {
            GuiClient.requested(actionId);
            long now=System.nanoTime();
            pendingUses.values().removeIf(use->now-use.created()>2_000_000_000L);
            if(local!=null) pendingUses.put(actionId,new PendingUse(now,local));
        }
        RayRequest request=new RayRequest(actionId,pose,5,action,item,mc.player.isShiftKeyDown(),
            offhand ? Messages.OFF_HAND : Messages.MAIN_HAND,mc.player.getInventory().selected);
        if(SERVER_RAYS) {
            if(ownAnswers.size()>64) ownAnswers.clear(); // answers whose request never came back
            ownAnswers.put(actionId,clientHit(request));
        }
        SessionLink.send("ray_query","coordinator",request);
        return true;
    }
    public static boolean localUse(String actionId) {
        PendingUse pending=pendingUses.remove(actionId);
        if(pending==null) return false;
        if(System.nanoTime()-pending.created()<=2_000_000_000L) pending.fallback().run();
        return true;
    }

    /** Render thread: the ray answered from the client world, as the player sees it. */
    public static void raycast(RayRequest request) { sendHit(clientHit(request)); }
    public static void sendHit(RayHit hit) { if(SessionLink.joined()) SessionLink.send("ray_hit","coordinator",hit); }
    static RayHit miss(RayRequest request) { return new RayHit(request.actionId(),null,0,0,0,0,0,"UP",0,0,0,true); }
    /**
     * Primary: its own answer to a ray it asked for, computed on the render thread as it asks ({@link #requestInteraction})
     * and sent the moment the Coordinator's request comes back, on the link thread: no frame of its own passes.
     */
    public static final Map<String,RayHit> ownAnswers=new java.util.concurrent.ConcurrentHashMap<>();
    static RayHit clientHit(RayRequest request) {
        Minecraft mc=Minecraft.getInstance(); Pose p=request.pose();
        if(mc.level==null || mc.player==null || !mc.level.dimension().location().toString().equals(p.dimension())) return miss(request);
        Vec3 start=new Vec3(p.x(),p.y(),p.z());
        Vec3 end=start.add(Vec3.directionFromRotation(p.pitch(),p.yaw()).scale(request.reach()));
        var hit=mc.level.clip(new net.minecraft.world.level.ClipContext(start,end,net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player));
        // An entity of this runtime in front of the nearest block is what the ray hits here.
        var entityHit=RemoteEntities.pick(mc,start,Vec3.directionFromRotation(p.pitch(),p.yaw()),request.reach(),SessionLink.isPrimary());
        if(entityHit!=null && (hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS || start.distanceTo(entityHit.getLocation())<start.distanceTo(hit.getLocation()))) {
            var entity=entityHit.getEntity(); Vec3 at=entityHit.getLocation(); var block=entity.blockPosition();
            return new RayHit(request.actionId(),null,0,start.distanceTo(at),block.getX(),block.getY(),block.getZ(),"UP",at.x,at.y,at.z,false,entity.getUUID().toString());
        }
        return blockHit(request,mc.level,start,hit);
    }
    /** The block a ray hit in {@code level}, or a miss, as this runtime answers it. */
    static RayHit blockHit(RayRequest request,Level level,Vec3 start,net.minecraft.world.phys.BlockHitResult hit) {
        Pose p=request.pose();
        if(hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS) return miss(request);
        ObjectState o=Boundary.local(p.dimension(),hit.getBlockPos());
        // This runtime's plain blocks (ores, plants) are published per chunk, not as objects.
        boolean plain=o==null && !SessionLink.isPrimary() && Boundary.localPlain(p.dimension(),hit.getBlockPos());
        if(o==null && !SessionLink.isPrimary() && !plain) {
            var hitType=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock());
            if(hitType.getNamespace().equals("minecraft") && !Ownership.ownsVanilla(level,hit.getBlockPos())) return miss(request);
            // Rendered but not yet indexed objects block the ray; never act through them.
        }
        Vec3 point=hit.getLocation(); var pos=hit.getBlockPos();
        return new RayHit(request.actionId(),o!=null?o.id():plain?dev.mmlf.common.Messages.bulkId(SessionLink.runtimeId()):null,o==null?0:o.revision(),start.distanceTo(point),pos.getX(),pos.getY(),pos.getZ(),hit.getDirection().name(),point.x,point.y,point.z,false);
    }
    /** {@code -Dmmlf.serverRays=false}: rays are answered on render threads, from the client world, as before. */
    public static final boolean SERVER_RAYS=!"false".equalsIgnoreCase(System.getProperty("mmlf.serverRays","true"));
    /**
     * Background, server thread: {@link #clientHit} on the server's world, which holds the same blocks (its own, and the
     * primary's through the shared world), with the entities as its client last saw them ({@link RemoteEntities#pickSnapshot}).
     */
    public static void raycastOnServer(net.minecraft.server.MinecraftServer server,RayRequest request) { sendHit(serverHit(server,request)); }
    static RayHit serverHit(net.minecraft.server.MinecraftServer server,RayRequest request) {
        Pose p=request.pose();
        var players=server.getPlayerList().getPlayers();
        var player=players.isEmpty() ? null : players.getFirst();
        if(player==null || !player.level().dimension().location().toString().equals(p.dimension())) return miss(request);
        var level=player.serverLevel();
        Vec3 start=new Vec3(p.x(),p.y(),p.z()), direction=Vec3.directionFromRotation(p.pitch(),p.yaw());
        Vec3 end=start.add(direction.scale(request.reach()));
        var hit=level.clip(new net.minecraft.world.level.ClipContext(start,end,net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,player));
        var entity=RemoteEntities.pickSnapshot(start,direction,Math.min(request.reach(),player.entityInteractionRange()));
        if(entity!=null && (hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS || start.distanceTo(entity.at())<start.distanceTo(hit.getLocation()))) {
            Vec3 at=entity.at(); var block=entity.block();
            return new RayHit(request.actionId(),null,0,start.distanceTo(at),block.getX(),block.getY(),block.getZ(),"UP",at.x,at.y,at.z,false,entity.id().toString());
        }
        return blockHit(request,level,start,hit);
    }
    /** Actions already handled here; a repeated routing never uses a block twice. */
    static final RecentIds handledActions=new RecentIds(512);
    record PlacementCheck(net.minecraft.core.BlockPos pos,net.minecraft.world.level.block.state.BlockState before,String actionId,long due) {}
    static final java.util.List<PlacementCheck> placementChecks=new java.util.ArrayList<>();
    public static void interact(Interaction action) {
        Minecraft mc=Minecraft.getInstance(); RayHit hit=action.hit();
        // Held still for a save of the primary: the interaction happens right after it, as it does on the primary.
        if(!SessionLink.isPrimary() && SaveSync.deferWhileFrozen(()->interact(action))) return;
        if(!handledActions.firstTime(action.actionId())) { MmlfLog.warn("interaction","duplicate_ignored",Map.of("actionId",action.actionId())); return; }
        if(SessionLink.isPrimary() && "use".equals(action.action()) && localUse(action.actionId())) return;
        if(hit.entityHit()) { interactEntity(mc,action); return; }
        if(hit.miss()) { useAir(mc,action); return; }
        var pos=new net.minecraft.core.BlockPos(hit.x(),hit.y(),hit.z()); ObjectState current=Boundary.local(action.pose().dimension(),pos);
        if(mc.getSingleplayerServer()==null || mc.player==null || mc.level==null) { result(action,false,"FAIL","runtime_not_ready"); return; }
        if(dev.mmlf.common.Messages.isBulkId(hit.objectId())) { if(current!=null || !Boundary.localPlain(action.pose().dimension(),pos)) { result(action,false,"FAIL","stale_target"); return; } }
        else if(hit.objectId()!=null && (current==null || !current.id().equals(hit.objectId()) || current.revision()!=hit.revision())) { result(action,false,"FAIL","stale_target"); return; }
        if(!mc.level.dimension().location().toString().equals(action.pose().dimension())) { result(action,false,"FAIL","dimension_mismatch"); return; }
        var itemRef=RemoteItemRef.parse(action.heldItem());
        boolean remotePlacement=itemRef.isPresent() && itemRef.get().owner().equals(SessionLink.runtimeId()) && "use".equals(action.action());
        if(hit.objectId()==null && !SessionLink.isPrimary() && !remotePlacement) { result(action,false,"FAIL","unowned_target"); return; }
        // Every use, including placing this runtime's item on a vanilla surface, goes through the native client path with
        // the mirrored stack itself: the use consumes from the real inventory, never from a stand-in copy.
        if("use".equals(action.action())) { useNative(mc,action,pos,current); return; }
        if("attack".equals(action.action()) || "attack_hold".equals(action.action())) { mine(mc,action,pos,current); return; }
        var server=mc.getSingleplayerServer();
        server.execute(()->{
            if(server.getPlayerList().getPlayers().isEmpty()) { result(action,false,"FAIL","no_player"); return; }
            var player=server.getPlayerList().getPlayers().getFirst();
            if(!player.level().dimension().location().toString().equals(action.pose().dimension())) { result(action,false,"FAIL","dimension_mismatch"); return; }
            var actual=player.level().getBlockState(pos);
            var type=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actual.getBlock());
            if(type==null || (current!=null && !type.toString().equals(current.type()))) { result(action,false,"FAIL","stale_target"); return; }
            if("pick".equals(action.action())) {RemoteItems.pick(player,pos);result(action,true,"PICK",null);return;}
            result(action,false,"FAIL","unsupported_action");
        });
    }
    /**
     * Attacks or uses an entity the way this runtime's own player would: the client game mode sends the native packet and
     * the integrated server validates and executes it, so damage, knockback, sounds, trades and mod logic are the
     * entity's own. A held attack never hits an entity again; vanilla attacks entities only on a click.
     */
    static void interactEntity(Minecraft mc,Interaction action) {
        if(mc.player==null || mc.level==null || mc.gameMode==null) { result(action,false,"FAIL","runtime_not_ready"); return; }
        if(!mc.level.dimension().location().toString().equals(action.pose().dimension())) { result(action,false,"FAIL","dimension_mismatch"); return; }
        var entity=RemoteEntities.find(mc,action.hit().entity());
        if(entity==null || !entity.isAlive()) { result(action,false,"FAIL","stale_target"); return; }
        if(!RemoteEntities.interactive(entity,SessionLink.isPrimary())) { result(action,false,"FAIL","unowned_target"); return; }
        var player=mc.player;
        if(action.selectedSlot()>=0 && action.selectedSlot()<9) player.getInventory().selected=action.selectedSlot();
        var hand=Messages.OFF_HAND.equals(action.hand()) ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        String type=String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        switch(String.valueOf(action.action())) {
            case "attack" -> {
                mc.gameMode.attack(player,entity);
                player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                MmlfLog.info("interaction","native_result",Map.of("actionId",action.actionId(),"action","attack","target","entity","entity",type));
                result(action,true,"ATTACK",null);
            }
            case "attack_hold" -> result(action,false,"HELD",null);
            case "use" -> {
                if(!SessionLink.isPrimary()) GuiHost.noteInteraction(action.actionId());
                var at=new Vec3(action.hit().hitX(),action.hit().hitY(),action.hit().hitZ());
                var outcome=mc.gameMode.interactAt(player,entity,new net.minecraft.world.phys.EntityHitResult(entity,at),hand);
                if(!outcome.consumesAction()) outcome=mc.gameMode.interact(player,entity,hand);
                if(outcome.shouldSwing()) player.swing(hand);
                MmlfLog.info("interaction","native_result",Map.of("actionId",action.actionId(),"action","use","target","entity","entity",type,"result",outcome.name(),
                    "screen",mc.screen==null?"none":mc.screen.getClass().getName()));
                result(action,outcome.consumesAction(),outcome.name(),null);
            }
            default -> result(action,false,"FAIL","unsupported_action");
        }
    }
    /**
     * Nothing was in reach: the held item is used into the air as vanilla's {@code startUseItem} does after its ray
     * misses. An item that is then held in use (food, a bow, a shield) keeps being used while the primary holds the key
     * ({@link #primaryUsing}); vanilla releases it when the key is let go.
     */
    static void useAir(Minecraft mc,Interaction action) {
        var player=mc.player;
        if(player==null || mc.level==null || mc.gameMode==null) { result(action,false,"FAIL","runtime_not_ready"); return; }
        if(!mc.level.dimension().location().toString().equals(action.pose().dimension())) { result(action,false,"FAIL","dimension_mismatch"); return; }
        var hand=Messages.OFF_HAND.equals(action.handOrMain()) ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        if(action.selectedSlot()>=0 && action.selectedSlot()<9) player.getInventory().selected=action.selectedSlot();
        String problem=InventoryBridge.heldProblem(player.getItemInHand(hand),action.heldItem());
        if(problem!=null) { result(action,false,"FAIL",problem); return; }
        // A repeated request while the key is held keeps an owner's use going.
        if(!SessionLink.isPrimary()) { primaryUsing=true; primaryUsingUntil=System.nanoTime()+USE_HOLD_NANOS; }
        if(player.getItemInHand(hand).isEmpty() || player.isUsingItem()) { result(action,false,"PASS",null); return; }
        // The air use acts along this runtime's own look, which must be the primary's at the moment of the click.
        player.setYRot(action.pose().yaw()); player.setXRot(action.pose().pitch());
        var outcome=mc.gameMode.useItem(player,hand);
        if(outcome.consumesAction()) mc.gameRenderer.itemInHandRenderer.itemUsed(hand);
        if(outcome.shouldSwing()) player.swing(hand);
        MmlfLog.info("interaction","native_result",Map.of("actionId",action.actionId(),"action","use","path","air","result",outcome.name()));
        result(action,outcome.consumesAction(),outcome.name(),null);
    }
    /**
     * Owner: the primary still holds the use key for an item this runtime uses in the air. Kept alive by the primary's
     * repeated use requests and ended at once by {@code mmlf:use_stop}; vanilla's release check reads it as the key.
     */
    public static volatile boolean primaryUsing;
    static volatile long primaryUsingUntil;
    static final long USE_HOLD_NANOS=600_000_000L;
    public static boolean primaryUsing() {
        if(SessionLink.isPrimary() || !primaryUsing) return false;
        if(System.nanoTime()-primaryUsingUntil>0) { primaryUsing=false; return false; }
        return true;
    }
    /** Primary: the use key was let go. */
    public static void useReleased() {
        if(!SessionLink.isPrimary() || !SessionLink.connected()) return;
        SessionLink.sendReliable("event","*",new Event("mmlf:use_stop",Map.of()));
    }
    /**
     * Uses a block the way this runtime's own player would: the client game mode runs the client-side logic (where some
     * mods open their screens) and sends the use packet, which the integrated server validates and executes on its own
     * thread. The held stack is the mirrored inventory's, never a stack substituted for the occasion.
     */
    static void useNative(Minecraft mc,Interaction action,net.minecraft.core.BlockPos pos,ObjectState current) {
        RayHit hit=action.hit(); var player=mc.player;
        var state=mc.level.getBlockState(pos); var type=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if(current!=null && !type.toString().equals(current.type())) { result(action,false,"FAIL","stale_target"); return; }
        var hand=Messages.OFF_HAND.equals(action.handOrMain()) ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        if(action.selectedSlot()>=0 && action.selectedSlot()<9) player.getInventory().selected=action.selectedSlot();
        String problem=InventoryBridge.heldProblem(player.getItemInHand(hand),action.heldItem());
        if(problem!=null) { MmlfLog.warn("interaction","held_item_rejected",Map.of("actionId",action.actionId(),"reason",problem,"expected",String.valueOf(action.heldItem()))); result(action,false,"FAIL",problem); return; }
        var nativeHit=new net.minecraft.world.phys.BlockHitResult(new Vec3(hit.hitX(),hit.hitY(),hit.hitZ()),net.minecraft.core.Direction.valueOf(hit.face()),pos,false);
        var adjacent=pos.relative(nativeHit.getDirection());
        var before=mc.level.getBlockState(adjacent);
        var connection=mc.getConnection();
        boolean shifted=player.isShiftKeyDown();
        if(!SessionLink.isPrimary()) GuiHost.noteInteraction(action.actionId());
        try {
            // The server reads sneaking from command packets, which this runtime never sends from its own input.
            if(connection!=null) connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player,action.secondaryUse()?net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY:net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            player.setShiftKeyDown(action.secondaryUse());
            var outcome=mc.gameMode.useItemOn(player,hand,nativeHit);
            // As vanilla's startUseItem: a block that passes leaves the click to the held item (a bucket, a spawn egg's
            // fluid check, food), which acts by the player's own look ray.
            if(outcome==net.minecraft.world.InteractionResult.PASS && !player.getItemInHand(hand).isEmpty()) {
                var used=mc.gameMode.useItem(player,hand);
                if(used.consumesAction()) { outcome=used; mc.gameRenderer.itemInHandRenderer.itemUsed(hand); }
            }
            if(outcome.shouldSwing()) player.swing(hand);
            boolean success=outcome.consumesAction();
            MmlfLog.info("interaction","native_result",Map.of("actionId",action.actionId(),"action","use","path","client","result",outcome.name(),"position",pos.toShortString(),"state",state.toString(),"screen",mc.screen==null?"none":mc.screen.getClass().getName()));
            result(action,success,outcome.name(),null);
            placementChecks.add(new PlacementCheck(adjacent.immutable(),before,action.actionId(),System.nanoTime()+150_000_000L));
            System.out.println("MMLF INTERACTION runtime="+SessionLink.runtimeId()+" action=use path=client result="+outcome+" state="+state);
        } finally {
            player.setShiftKeyDown(shifted);
            if(connection!=null && action.secondaryUse()!=shifted) connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player,shifted?net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY:net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        }
    }
    /**
     * Breaking as this runtime's own player does: the client game mode starts the block and continues it every tick
     * while the primary keeps the attack button down (it repeats the request about every 200 ms), so survival mining
     * time, tools, cracks, drops and sounds are all native. Without a repeat the mining stops, as when a player lets go.
     */
    public static net.minecraft.core.BlockPos miningPos;
    static net.minecraft.core.Direction miningFace;
    static long miningUntil;

    static void mine(Minecraft mc,Interaction action,net.minecraft.core.BlockPos pos,ObjectState current) {
        var state=mc.level.getBlockState(pos); var type=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if(state.isAir() || current!=null && !type.toString().equals(current.type())) { result(action,false,"FAIL","stale_target"); return; }
        if(action.selectedSlot()>=0 && action.selectedSlot()<9) mc.player.getInventory().selected=action.selectedSlot();
        var face=net.minecraft.core.Direction.valueOf(action.hit().face());
        boolean started=false;
        if(!pos.equals(miningPos) || !mc.gameMode.isDestroying()) {
            if(miningPos!=null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
            started=mc.gameMode.startDestroyBlock(pos,face); miningPos=pos.immutable(); miningFace=face;
        }
        miningUntil=System.nanoTime()+600_000_000L;
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if(started) MmlfLog.info("interaction","native_result",Map.of("actionId",action.actionId(),"action","attack","path","client","position",pos.toShortString(),"state",state.toString()));
        result(action,true,"MINING",null);
    }
    /** Primary: the attack button was let go; owners stop breaking now instead of waiting for the repeat to lapse. */
    /** Set by the self-test while it holds attack through requests rather than the (unfocused) mouse. */
    public static boolean selfTestAttacking;
    public static void attackReleased() {
        if(!SessionLink.isPrimary() || !SessionLink.joined() || lastAttack==0 || selfTestAttacking) return;
        lastAttack=0;
        SessionLink.sendReliable("event","*",new Event("mmlf:attack_stop",Map.of()));
        Minecraft mc=Minecraft.getInstance(); stopMining(mc);
    }
    public static void stopMining(Minecraft mc) {
        if(miningPos!=null && mc.gameMode!=null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        miningPos=null;
    }
    /** True while a background breaks a block for the primary. */
    public static boolean miningForPrimary() { return !SessionLink.isPrimary() && miningPos!=null; }
    public static void continueMining(Minecraft mc) {
        if(miningPos==null || mc.gameMode==null || mc.level==null) return;
        if(System.nanoTime()-miningUntil>0 || mc.level.getBlockState(miningPos).isAir()) { stopMining(mc); return; }
        if(mc.gameMode.continueDestroyBlock(miningPos,miningFace)) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        MmlfLog.sampled("interaction","mining",250,Map.of("position",miningPos.toShortString(),"progress",((dev.mmlf.bridge.mixin.GameModeAccessor)mc.gameMode).mmlf$destroyProgress(),
            "destroying",mc.gameMode.isDestroying(),"onGround",mc.player.onGround(),"speed",mc.level.getBlockState(miningPos).getDestroyProgress(mc.player,mc.level,miningPos)));
    }
    /** Blocks the server placed after a native use belong to this runtime, like placements through the server path. */
    public static void checkPlacements(Minecraft mc) {
        if(placementChecks.isEmpty() || mc.getSingleplayerServer()==null) return;
        long now=System.nanoTime(); var server=mc.getSingleplayerServer();
        for(var it=placementChecks.iterator();it.hasNext();) {
            PlacementCheck check=it.next(); if(now-check.due()<0) continue; it.remove();
            server.execute(()->{
                if(server.getPlayerList().getPlayers().isEmpty()) return;
                var level=server.getPlayerList().getPlayers().getFirst().serverLevel(); var after=level.getBlockState(check.pos());
                if(!after.equals(check.before())) {
                    // With the shared map a vanilla placement was forwarded and is the primary's block: claiming it too
                    // would give the same block to both worlds.
                    boolean vanilla=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(after.getBlock()).getNamespace().equals("minecraft");
                    if(!(vanilla && SharedWorld.forwarding())) Ownership.ownVanilla(level,check.pos()); MmlfLog.info("items","native_placement",Map.of("actionId",check.actionId(),"position",check.pos().toShortString(),"state",after.toString())); return; }
                // Unchanged on the server although the client may have predicted a placement: record what the server saw.
                var player=server.getPlayerList().getPlayers().getFirst();
                var chunk=level.getChunkAt(check.pos());
                var raw=chunk.getSection(chunk.getSectionIndex(check.pos().getY())).getBlockState(check.pos().getX()&15,check.pos().getY()&15,check.pos().getZ()&15);
                var box=new net.minecraft.world.phys.AABB(check.pos()).expandTowards(0,1,0);
                var details=new java.util.LinkedHashMap<String,Object>();
                details.put("actionId",check.actionId()); details.put("position",check.pos().toShortString()); details.put("read",after.toString()); details.put("raw",raw.toString());
                details.put("mirrored",NeighborMirror.mirrored(level,check.pos())); details.put("mirroredAbove",NeighborMirror.mirrored(level,check.pos().above()));
                details.put("serverPlayer",player.blockPosition().toShortString()); details.put("serverDistance",Math.sqrt(player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(check.pos()))));
                details.put("clientPlayer",mc.player==null?"none":mc.player.blockPosition().toShortString());
                details.put("awaitingTeleport",((dev.mmlf.bridge.mixin.ServerConnectionAccessor)player.connection).mmlf$awaitingPosition()!=null);
                details.put("serverHeld",player.getMainHandItem().toString()); details.put("entitiesInPlace",level.getEntities((net.minecraft.world.entity.Entity)null,box).stream().map(e->e.getType().toShortString()).toList());
                MmlfLog.info("items","native_placement_absent",details);
            });
        }
    }
    static void result(Interaction action,boolean success,String outcome,String reason) {
        if(SessionLink.isPrimary() || !SessionLink.joined()) return;
        SessionLink.sendReliable("interaction_result",SessionLink.session().primary(),new InteractionResult(action.actionId(),SessionLink.runtimeId(),success,outcome,GuiHost.instanceFor(action.actionId()),reason));
    }
    /** Primary: an owner's answer. Success gives the usual arm swing; a failure is shown instead of silently ignored. */
    public static void interactionResult(String source,InteractionResult result) {
        Minecraft mc=Minecraft.getInstance();
        pendingUses.remove(result.actionId());
        MmlfLog.debug("interaction","result",Map.of("actionId",result.actionId(),"owner",source,"success",result.success(),"result",String.valueOf(result.result()),"reason",String.valueOf(result.reason())));
        if(mc.player==null) return;
        if(result.success()) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        // An owner attacked an entity for this player: the cooldown starts over as after a native attack (crosshair indicator).
        if(result.success() && "ATTACK".equals(result.result())) mc.player.resetAttackStrengthTicker();
        else if(result.reason()!=null) mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("MMLF: "+source+" "+result.reason()),false);
    }
}
