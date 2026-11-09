package dev.mmlf.bridge;

import dev.mmlf.common.Messages.EntityState;
import dev.mmlf.common.Messages.Entities;
import dev.mmlf.common.Messages.PlayerDamage;
import dev.mmlf.common.MmlfLog;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Entities across runtimes. Each entity belongs to the runtime whose world simulates it (the same namespace rule as
 * blocks, {@link dev.mmlf.bridge.render.DynamicCapture#owned}, plus entities a scene or mod {@link #claim}s); the
 * primary owns the one player and its own world's entities.
 * <ul>
 * <li>Rays: every runtime answers a ray with the nearest of its own blocks or entities; the Coordinator routes the
 * interaction to the nearest owner, which attacks or uses the entity natively ({@code BridgeCore.interactEntity}).</li>
 * <li>Collision: owners publish their entities' boxes ({@link Entities}); the primary's player is pushed by them as by its
 * own entities.</li>
 * <li>Damage: an owner's copy of the player never takes damage; what its entities do to it goes to the primary
 * ({@link PlayerDamage}), which applies it to the real player.</li>
 * </ul>
 */
public final class RemoteEntities {
    private RemoteEntities() {}
    private static final Set<UUID> claimed=ConcurrentHashMap.newKeySet();
    private record Received(Entities snapshot,long atNanos) {}
    private static final Map<String,Received> received=new ConcurrentHashMap<>();
    private static long sequence;
    private static int publishTick;
    private static boolean lastEmpty=true;
    /** Owner: boxes of its entities this tick, inflated a little, for attributing the particles they cause. */
    private static List<AABB> ownedBoxes=List.of();
    private static final Set<net.minecraft.client.particle.Particle> entityParticles=Collections.newSetFromMap(new WeakHashMap<>());
    private static final double PUBLISH_RANGE=64;
    private static final long STALE_NANOS=1_000_000_000L;

    /** What of this runtime is dropping its loot on this server thread (a dying entity, a broken block), if anything. */
    private record Scope(String source,long atNanos) {}
    private static final ThreadLocal<Scope> dropping=new ThreadLocal<>();
    /** The open drop scope; one an exception left open expires instead of claiming everything added after it. */
    private static String droppingSource() {
        Scope scope=dropping.get();
        if(scope==null) return null;
        if(System.nanoTime()-scope.atNanos()>1_000_000_000L) { dropping.remove(); return null; }
        return scope.source();
    }

    /** Owner, server thread: an item an owned entity dropped is owned too. */
    public static void dropped(Entity source,Entity item) {
        if(item!=null && owner() && interactive(source,false)) { claim(item.getUUID()); MmlfLog.info("entity","drop_claimed",Map.of("source",typeOf(source),"drop",typeOf(item))); }
    }
    public static void beginDrops(Entity source) {
        if(!owner() || !interactive(source,false)) return;
        dropping.set(new Scope(typeOf(source),System.nanoTime()));
        MmlfLog.info("entity","owned_entity_died",Map.of("entity",typeOf(source),"uuid",source.getUUID().toString()));
    }
    private static String typeOf(Entity entity) { return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())); }
    public static void endDrops() { dropping.remove(); }
    /**
     * Owner, server thread: a block of this runtime drops its resources (items, experience) as it breaks. Vanilla items a
     * mod block drops (a mod ore's diamonds) are this runtime's like the block. Returns whether a scope was opened.
     */
    public static boolean beginBlockDrops(net.minecraft.world.level.block.state.BlockState state,net.minecraft.core.BlockPos pos) {
        if(!owner() || droppingSource()!=null || state==null || !BridgeCore.renderOwned(state,pos)) return false;
        dropping.set(new Scope(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())),System.nanoTime()));
        return true;
    }
    /** Owner, server thread: an entity added while an owned entity drops its death loot (loot, experience orbs). */
    public static void added(Entity entity) {
        String source=droppingSource();
        // Loaders may add the collected drops later (NeoForge after its drops event): an item or orb appearing on a dying
        // entity of this runtime is still that entity's.
        if(source==null && owner() && (entity instanceof net.minecraft.world.entity.item.ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb))
            source=entity.level().getEntities(entity,entity.getBoundingBox().inflate(1.0),
                e->e instanceof net.minecraft.world.entity.LivingEntity living && living.isDeadOrDying() && interactive(e,false)).stream().findFirst().map(RemoteEntities::typeOf).orElse(null);
        if(source==null) return;
        claim(entity.getUUID());
        MmlfLog.info("entity","drop_claimed",Map.of("source",source,"drop",typeOf(entity)));
    }
    private static boolean owner() { return !BridgeCore.isPrimary() && BridgeCore.connected(); }

    /** Owner, server thread: experience its copy of the player collects goes to the primary. Returns whether to cancel. */
    public static boolean followerExperience(Player player,int amount) {
        if(!(player instanceof ServerPlayer) || !owner()) return false;
        if(amount>0) Minecraft.getInstance().execute(()->BridgeCore.sendToPrimary("player_xp",new dev.mmlf.common.Messages.PlayerExperience(amount)));
        return true;
    }
    /** Primary: experience an owner's copy of the player collected. */
    static void applyExperience(Minecraft mc,int amount) {
        var server=mc.getSingleplayerServer(); if(server==null || amount<=0) return;
        server.execute(()->{ if(!server.getPlayerList().getPlayers().isEmpty()) server.getPlayerList().getPlayers().getFirst().giveExperiencePoints(amount); });
    }

    /** This runtime owns {@code entity} although its type is vanilla (scenes, mods that spawn vanilla mobs as theirs). */
    public static void claim(UUID entity) { claimed.add(entity); }
    public static boolean claimed(UUID entity) { return claimed.contains(entity); }

    /** Whether this runtime acts for {@code entity}: the primary for its world's own, an owner for those it owns. */
    static boolean interactive(Entity entity,boolean primary) {
        if(entity instanceof Player || entity instanceof dev.mmlf.bridge.render.RemoteProxy) return false;
        return primary || dev.mmlf.bridge.render.DynamicCapture.owned(entity);
    }

    /** The nearest entity this runtime acts for along the ray, within the player's entity reach. */
    static EntityHitResult pick(Minecraft mc,Vec3 start,Vec3 direction,double reach,boolean primary) {
        var player=mc.player; if(player==null || mc.level==null) return null;
        double range=Math.min(reach,player.entityInteractionRange());
        Vec3 end=start.add(direction.scale(range));
        AABB box=new AABB(start,end).inflate(1.0);
        return ProjectileUtil.getEntityHitResult(player,start,end,box,e->e!=player && !e.isSpectator() && e.isPickable() && interactive(e,primary),range*range);
    }
    static Entity find(Minecraft mc,String id) {
        if(mc.level==null || id==null) return null;
        UUID uuid; try { uuid=UUID.fromString(id); } catch(IllegalArgumentException e) { return null; }
        for(Entity entity:mc.level.entitiesForRendering()) if(entity.getUUID().equals(uuid)) return entity;
        return null;
    }

    /** Owner, client tick: its entities near the player, for the Coordinator's index and the primary's collision. */
    static void publish(Minecraft mc,String runtime) {
        if(BridgeCore.isPrimary() || mc.level==null || mc.player==null) return;
        var list=new ArrayList<EntityState>(); var boxes=new ArrayList<AABB>();
        for(Entity e:mc.level.entitiesForRendering()) {
            // Dying entities still show their death and the particles of it; only living ones are reported for rays.
            if(e instanceof Player || !dev.mmlf.bridge.render.DynamicCapture.owned(e) || e.distanceToSqr(mc.player)>PUBLISH_RANGE*PUBLISH_RANGE) continue;
            boxes.add(e.getBoundingBox().inflate(0.75));
            if(!e.isAlive()) continue;
            list.add(new EntityState(e.getUUID().toString(),String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())),e.getX(),e.getY(),e.getZ(),e.getBbWidth(),e.getBbHeight(),e.isPushable()));
        }
        ownedBoxes=List.copyOf(boxes);
        if(++publishTick%2!=0) return;
        if(list.isEmpty() && lastEmpty) return;
        lastEmpty=list.isEmpty();
        BridgeCore.send("entities","*",new Entities(runtime,mc.level.dimension().location().toString(),++sequence,list));
    }
    /**
     * Owner, as a particle is added: vanilla particles that appear on this runtime's entities (hurt, angry villager,
     * hearts, critical hits, death smoke) are theirs and are submitted with them, although their classes are vanilla.
     */
    public static void particleAdded(net.minecraft.client.particle.Particle particle) {
        List<AABB> boxes=ownedBoxes;
        if(boxes.isEmpty() || BridgeCore.isPrimary()) return;
        Vec3 at=particle.getBoundingBox().getCenter();
        for(AABB box:boxes) if(box.contains(at)) { entityParticles.add(particle); return; }
    }
    public static boolean entityParticle(net.minecraft.client.particle.Particle particle) { return !entityParticles.isEmpty() && entityParticles.contains(particle); }
    /** Primary: an owner's newest snapshot; an older one than the last is ignored. */
    static void receive(String source,Entities snapshot) {
        if(snapshot==null || !source.equals(snapshot.owner())) return;
        received.compute(source,(k,old)->old!=null && old.snapshot().sequence()>=snapshot.sequence() ? old : new Received(snapshot,System.nanoTime()));
    }
    static void forget(String runtime) { if(runtime==null) received.clear(); else received.remove(runtime); }

    /** Primary, client tick: other runtimes' entities push the player apart as vanilla's own entities do. */
    static void push(Minecraft mc) {
        var player=mc.player; if(player==null || mc.level==null || player.isSpectator() || player.noPhysics || received.isEmpty()) return;
        String dimension=mc.level.dimension().location().toString(); long now=System.nanoTime();
        AABB own=player.getBoundingBox();
        for(Received r:received.values()) {
            if(now-r.atNanos()>STALE_NANOS || !dimension.equals(r.snapshot().dimension())) continue;
            for(EntityState e:r.snapshot().entities()) {
                if(!e.pushable()) continue;
                double half=e.width()/2;
                if(!own.intersects(e.x()-half,e.y(),e.z()-half,e.x()+half,e.y()+e.height(),e.z()+half)) continue;
                double dx=player.getX()-e.x(), dz=player.getZ()-e.z();
                double max=Math.max(Math.abs(dx),Math.abs(dz));
                if(max<0.01) continue;
                max=Math.sqrt(max); dx/=max; dz/=max;
                double scale=Math.min(1,1/max)*0.05;
                player.push(dx*scale,0,dz*scale);
            }
        }
    }

    /**
     * Owner, server thread, before its copy of the player is hurt: always cancelled, because the primary owns the
     * player's health. Damage done by this runtime's own entities is sent to the primary. Returns whether to cancel.
     */
    public static boolean followerHurt(ServerPlayer player,DamageSource source,float amount) {
        if(BridgeCore.isPrimary() || !BridgeCore.connected()) return false;
        Entity attacker=source.getEntity(), direct=source.getDirectEntity();
        Entity responsible=attacker!=null && interactive(attacker,false) ? attacker : direct!=null && interactive(direct,false) ? direct : null;
        if(responsible!=null && amount>0 && !player.isInvulnerableTo(source) && !player.getAbilities().invulnerable) {
            var damage=new PlayerDamage(responsible.getUUID().toString(),String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(responsible.getType())),
                source.type().msgId(),amount,responsible.getX(),responsible.getZ());
            Minecraft.getInstance().execute(()->BridgeCore.sendToPrimary("player_damage",damage));
            MmlfLog.info("entity","player_damage_sent",Map.of("attacker",damage.attackerType(),"type",damage.damageType(),"amount",amount));
        }
        return true;
    }
    /** Primary: applies an owner's entity damage to the real player, scaled by this world's difficulty as vanilla does. */
    static void applyDamage(Minecraft mc,String source,PlayerDamage damage) {
        var server=mc.getSingleplayerServer(); if(server==null || damage==null) return;
        server.execute(()->{
            if(server.getPlayerList().getPlayers().isEmpty()) return;
            ServerPlayer player=server.getPlayerList().getPlayers().getFirst();
            float amount=switch(player.level().getDifficulty()) {
                case PEACEFUL -> 0f;
                case EASY -> Math.min(damage.amount()/2f+1f,damage.amount());
                case NORMAL -> damage.amount();
                case HARD -> damage.amount()*1.5f;
            };
            if(amount<=0) return;
            boolean hurt=player.hurt(player.damageSources().generic(),amount);
            if(hurt) { player.knockback(0.4,damage.fromX()-player.getX(),damage.fromZ()-player.getZ()); player.hurtMarked=true; }
            MmlfLog.info("entity","player_damage_applied",Map.of("owner",source,"attacker",String.valueOf(damage.attackerType()),"amount",amount,"hurt",hurt));
        });
    }
}
