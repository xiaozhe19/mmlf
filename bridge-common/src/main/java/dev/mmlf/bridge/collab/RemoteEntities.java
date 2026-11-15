package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.Messages.EntityState;
import dev.mmlf.common.Messages.Entities;
import dev.mmlf.common.Messages.PlayerDamage;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.PlayerShare;
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
 * blocks, {@link #owned}, plus entities a scene or mod {@link #claim}s); the
 * primary owns the one player and its own world's entities.
 * <ul>
 * <li>Rays: every runtime answers a ray with the nearest of its own blocks or entities; the Coordinator routes the
 * interaction to the nearest owner, which attacks or uses the entity natively ({@code InteractionService.interactEntity}).</li>
 * <li>Collision: owners publish their entities' boxes ({@link Entities}); the primary's player is pushed by them as by its
 * own entities.</li>
 * <li>Damage: an owner's copy of the player never takes damage; what its entities do to it goes to the primary
 * ({@link PlayerDamage}), which applies it to the real player.</li>
 * </ul>
 */
public final class RemoteEntities {
    private RemoteEntities() {}
    /** This world's part of RemoteEntities: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        final Set<UUID> claimed=ConcurrentHashMap.newKeySet();
        final Map<String,Received> received=new ConcurrentHashMap<>();
        List<AABB> ownedBoxes=List.of();
        volatile List<Pickable> pickable=List.of();
        boolean lastEmpty=true;
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("remote_entities",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    private record Received(Entities snapshot,long atNanos) {}
    private static long sequence;
    private static int publishTick;
    /** Owner: boxes of its entities this tick, inflated a little, for attributing the particles they cause. */
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
        if(item!=null && owner() && interactive(source,false)) { claim(item); MmlfLog.info("entity","drop_claimed",Map.of("source",typeOf(source),"drop",typeOf(item))); }
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
    public static boolean beginBlockDrops(net.minecraft.world.level.LevelAccessor level,net.minecraft.world.level.block.state.BlockState state,net.minecraft.core.BlockPos pos) {
        // Its own blocks, and any block its own things break (a farmer's crop, a remote drill's stone).
        if(!owner() || droppingSource()!=null || state==null || !(SharedWorld.ownBlock(level instanceof net.minecraft.world.level.Level world ? world : null,state,pos) || SharedWorld.authored())) return false;
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
        // Whatever this runtime's own things create while they act (an explosion's drops, an arrow) is theirs too.
        if(source==null && owner() && SharedWorld.authored()) source="authored";
        if(source==null) return;
        claim(entity);
        MmlfLog.info("entity","drop_claimed",Map.of("source",source,"drop",typeOf(entity)));
    }
    private static boolean owner() { return !SessionLink.isPrimary() && SessionLink.connected(); }

    /** Owner, server thread: experience its copy of the player collects goes to the primary. Returns whether to cancel. */
    public static boolean followerExperience(Player player,int amount) {
        if(!(player instanceof ServerPlayer) || !owner()) return false;
        if(amount>0) Minecraft.getInstance().execute(()->SessionLink.sendToPrimary("player_xp",new dev.mmlf.common.Messages.PlayerExperience(amount)));
        return true;
    }
    /** Primary: experience an owner's copy of the player collected. */
    public static void applyExperience(Minecraft mc,int amount) {
        var server=mc.getSingleplayerServer(); if(server==null || amount<=0) return;
        server.execute(()->{ if(!server.getPlayerList().getPlayers().isEmpty()) server.getPlayerList().getPlayers().getFirst().giveExperiencePoints(amount); });
    }

    /** This runtime owns {@code entity} although its type is vanilla (scenes, mods that spawn vanilla mobs as theirs). */
    public static void claim(UUID entity) { state().claimed.add(entity); }
    /** Entity tag saved with a claimed entity, so it is still this runtime's after the world is opened again. */
    public static final String OWNED_TAG="mmlf_owned";
    /** Server thread: claims {@code entity} for good; the tag is restored into {@link #claimed} as it ticks after a load. */
    public static void claim(Entity entity) { state().claimed.add(entity.getUUID()); if(!entity.level().isClientSide()) entity.addTag(OWNED_TAG); }
    public static boolean claimed(UUID entity) { return state().claimed.contains(entity); }

    /** Whether this runtime acts for {@code entity}: the primary for its world's own, an owner for those it owns. */
    static boolean interactive(Entity entity,boolean primary) {
        if(entity instanceof Player || entity instanceof dev.mmlf.bridge.platform.LocalOnlyEntity) return false;
        return primary || owned(entity);
    }
    /**
     * Whether this runtime owns {@code entity}: entities it claimed (a scene's, or a mod's through {@link #claim}),
     * mod entities, and vanilla carriers of its content (items of its mods, its falling blocks). Players belong to their
     * own runtime; local proxies to nobody.
     */
    public static boolean owned(Entity entity) {
        if(entity instanceof dev.mmlf.bridge.platform.LocalOnlyEntity || entity instanceof Player) return false;
        if(claimed(entity.getUUID())) return true;
        // Claimed before the world was saved: its server copy carries the tag (the client copy learns it from the set).
        if(!entity.level().isClientSide() && entity.getTags().contains(OWNED_TAG)) { claim(entity.getUUID()); return true; }
        if(!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals("minecraft")) return true;
        if(entity instanceof net.minecraft.world.entity.item.ItemEntity item) return foreign(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getNamespace());
        if(entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame) return foreign(BuiltInRegistries.ITEM.getKey(frame.getItem().getItem()).getNamespace());
        if(entity instanceof net.minecraft.world.entity.item.FallingBlockEntity falling) return Ownership.renderOwned(falling.level(),falling.getBlockState(),falling.blockPosition());
        return false;
    }
    private static boolean foreign(String namespace) { return !namespace.equals("minecraft"); }

    /** The nearest entity this runtime acts for along the ray, within the player's entity reach. */
    static EntityHitResult pick(Minecraft mc,Vec3 start,Vec3 direction,double reach,boolean primary) {
        var player=mc.player; if(player==null || mc.level==null) return null;
        double range=Math.min(reach,player.entityInteractionRange());
        Vec3 end=start.add(direction.scale(range));
        AABB box=new AABB(start,end).inflate(1.0);
        return ProjectileUtil.getEntityHitResult(player,start,end,box,e->e!=player && !e.isSpectator() && e.isPickable() && interactive(e,primary),range*range);
    }
    /** An entity a ray may hit, as the client saw it this tick: what {@link #pick} tests, readable from any thread. */
    record Pickable(UUID id,AABB box,net.minecraft.core.BlockPos block) {}
    /** A hit on one of {@link #pickable}: the entity, where the ray meets it, and its block. */
    record Pick(UUID id,Vec3 at,net.minecraft.core.BlockPos block) {}
    /**
     * Owner, any thread: {@link #pick} on the entities as of the last client tick, so a ray is answered without waiting
     * for the render thread. The nearest box the ray enters within {@code range}; a box around the start is hit there.
     */
    static Pick pickSnapshot(Vec3 start,Vec3 direction,double range) {
        Vec3 end=start.add(direction.scale(range));
        Pick nearest=null; double best=range*range;
        for(Pickable p:state().pickable) {
            Vec3 at=p.box().contains(start) ? start : p.box().clip(start,end).orElse(null);
            if(at==null) continue;
            double d=start.distanceToSqr(at);
            if(d<best || nearest==null && d<=best) { best=d; nearest=new Pick(p.id(),at,p.block()); }
        }
        return nearest;
    }
    static Entity find(Minecraft mc,String id) {
        if(mc.level==null || id==null) return null;
        UUID uuid; try { uuid=UUID.fromString(id); } catch(IllegalArgumentException e) { return null; }
        for(Entity entity:mc.level.entitiesForRendering()) if(entity.getUUID().equals(uuid)) return entity;
        return null;
    }

    /** Owner, client tick: its entities near the player, for the Coordinator's index and the primary's collision. */
    public static void publish(Minecraft mc,String runtime) {
        if(SessionLink.isPrimary() || mc.level==null || mc.player==null || Worlds.backgroundLeaving()) return;
        var list=new ArrayList<EntityState>(); var boxes=new ArrayList<AABB>(); var picks=new ArrayList<Pickable>();
        for(Entity e:mc.level.entitiesForRendering()) {
            // Dying entities still show their death and the particles of it; only living ones are reported for rays.
            if(e instanceof Player || !owned(e) || e.distanceToSqr(mc.player)>PUBLISH_RANGE*PUBLISH_RANGE) continue;
            boxes.add(e.getBoundingBox().inflate(0.75));
            if(!e.isSpectator() && e.isPickable() && interactive(e,false)) picks.add(new Pickable(e.getUUID(),e.getBoundingBox().inflate(e.getPickRadius()),e.blockPosition()));
            if(!e.isAlive()) continue;
            list.add(new EntityState(e.getUUID().toString(),String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())),e.getX(),e.getY(),e.getZ(),e.getBbWidth(),e.getBbHeight(),e.isPushable()));
        }
        state().ownedBoxes=List.copyOf(boxes);
        state().pickable=List.copyOf(picks);
        var chunks=new HashSet<String>(); String dimension=mc.level.dimension().location().toString();
        // With the chunks around each, so an entity walking out of its chunk finds the primary's ground there too.
        for(EntityState e:list) {
            int cx=((int)Math.floor(e.x()))>>4,cz=((int)Math.floor(e.z()))>>4;
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) chunks.add(dimension+"@"+(cx+dx)+","+(cz+dz));
        }
        SharedWorld.entityChunks(chunks);
        if(++publishTick%2!=0) return;
        if(list.isEmpty() && state().lastEmpty) return;
        state().lastEmpty=list.isEmpty();
        SessionLink.send("entities","*",new Entities(runtime,mc.level.dimension().location().toString(),++sequence,list));
    }
    /**
     * Owner, as a particle is added: vanilla particles that appear on this runtime's entities (hurt, angry villager,
     * hearts, critical hits, death smoke) are theirs and are submitted with them, although their classes are vanilla.
     */
    public static void particleAdded(net.minecraft.client.particle.Particle particle) {
        List<AABB> boxes=state().ownedBoxes;
        if(boxes.isEmpty() || SessionLink.isPrimary()) return;
        Vec3 at=particle.getBoundingBox().getCenter();
        for(AABB box:boxes) if(box.contains(at)) { entityParticles.add(particle); return; }
    }
    public static boolean entityParticle(net.minecraft.client.particle.Particle particle) { return !entityParticles.isEmpty() && entityParticles.contains(particle); }
    /** Primary: an owner's newest snapshot; an older one than the last is ignored. */
    public static void receive(String source,Entities snapshot) {
        if(snapshot==null || !source.equals(snapshot.owner())) return;
        state().received.compute(source,(k,old)->old!=null && old.snapshot().sequence()>=snapshot.sequence() ? old : new Received(snapshot,System.nanoTime()));
    }
    public static void forget(String runtime) { if(runtime==null) state().received.clear(); else state().received.remove(runtime); }

    /** Primary, client tick: other runtimes' entities push the player apart as vanilla's own entities do. */
    public static void push(Minecraft mc) {
        var player=mc.player; if(player==null || mc.level==null || player.isSpectator() || player.noPhysics || state().received.isEmpty()) return;
        String dimension=mc.level.dimension().location().toString(); long now=System.nanoTime();
        AABB own=player.getBoundingBox();
        for(Received r:state().received.values()) {
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
     * player's health. Damage done by this runtime's own entities is sent to the primary; so is damage with no entity
     * behind it that the primary does not work out from its own world ({@link PlayerShare#forwardsDamage}: this runtime's
     * mods' damage, its own effects'), once the copy reads the primary's state. Returns whether to cancel.
     */
    public static boolean followerHurt(ServerPlayer player,DamageSource source,float amount) {
        if(SessionLink.isPrimary() || !SessionLink.connected()) return false;
        if(amount<=0 || player.isInvulnerableTo(source) || player.getAbilities().invulnerable) return true;
        Entity attacker=source.getEntity(), direct=source.getDirectEntity();
        Entity responsible=attacker!=null && interactive(attacker,false) ? attacker : direct!=null && interactive(direct,false) ? direct : null;
        String type=source.typeHolder().unwrapKey().map(k->k.location().toString()).orElse(null);
        PlayerDamage damage=null;
        if(responsible!=null) damage=new PlayerDamage(responsible.getUUID().toString(),String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(responsible.getType())),
            type,amount,responsible.getX(),responsible.getZ());
        else if(attacker==null && direct==null && SharedPlayer.shared(player) && PlayerShare.forwardsDamage(type))
            damage=new PlayerDamage(null,null,type,amount,player.getX(),player.getZ());
        if(damage!=null) {
            var sent=damage;
            Minecraft.getInstance().execute(()->SessionLink.sendToPrimary("player_damage",sent));
            MmlfLog.info("entity","player_damage_sent",Map.of("attacker",String.valueOf(damage.attackerType()),"type",String.valueOf(type),"amount",amount));
        }
        return true;
    }
    /**
     * Primary: applies an owner's damage to the real player, with the damage type it had there where this runtime knows
     * it (death messages, armour and protections as vanilla's). An entity's attack is scaled by this world's difficulty as
     * vanilla does; the entity is not here, so vanilla's own scaling would not.
     */
    public static void applyDamage(Minecraft mc,String source,PlayerDamage damage) {
        var server=mc.getSingleplayerServer(); if(server==null || damage==null) return;
        server.execute(()->{
            if(server.getPlayerList().getPlayers().isEmpty()) return;
            ServerPlayer player=server.getPlayerList().getPlayers().getFirst();
            DamageSource cause=damageSource(player,damage.damageType());
            boolean byEntity=damage.attacker()!=null;
            float amount=!byEntity || cause.scalesWithDifficulty() ? damage.amount() : switch(player.level().getDifficulty()) {
                case PEACEFUL -> 0f;
                case EASY -> Math.min(damage.amount()/2f+1f,damage.amount());
                case NORMAL -> damage.amount();
                case HARD -> damage.amount()*1.5f;
            };
            if(amount<=0) return;
            boolean hurt=player.hurt(cause,amount);
            if(hurt && byEntity) { player.knockback(0.4,damage.fromX()-player.getX(),damage.fromZ()-player.getZ()); player.hurtMarked=true; }
            MmlfLog.info("entity","player_damage_applied",Map.of("owner",source,"attacker",String.valueOf(damage.attackerType()),"type",String.valueOf(damage.damageType()),"amount",amount,"hurt",hurt));
        });
    }
    private static DamageSource damageSource(ServerPlayer player,String type) {
        var at=type==null ? null : net.minecraft.resources.ResourceLocation.tryParse(type);
        if(at!=null) {
            var holder=player.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getHolder(at);
            if(holder.isPresent()) return new DamageSource(holder.get());
        }
        return player.damageSources().generic();
    }
}
