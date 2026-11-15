package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.bridge.mixin.MobEffectDurationAccessor;
import dev.mmlf.common.InJvm;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.PlayerShare;
import dev.mmlf.common.PlayerShare.Change;
import dev.mmlf.common.PlayerShare.Effect;
import dev.mmlf.common.PlayerShare.Vitals;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The one player, shared ({@link PlayerShare}). The primary's player is the player: at the end of each primary server
 * tick its state is sent to the backgrounds. A background sets it on its copy of the player at the start of each of its
 * server ticks; its copy does not evolve on its own (no hunger, regeneration, ticking of the primary's effects, death,
 * movement statistics or jumps: see the follower mixins), and what the background's logic does to it during the tick is
 * sent to the primary at the tick's end and applied to the real player.
 *
 * <p>Effects the primary does not know (registered by a background's mod only) are the background's own: they stay on
 * its copy and tick there; the primary's state never removes them.
 */
public final class SharedPlayer {
    private SharedPlayer() {}
    /** This world's part of SharedPlayer: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        Vitals lastSent;
        long sentAt;
        volatile Vitals latest;
        volatile Baseline baseline;
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("shared_player",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }


    // ---- primary ----
    private static long version;

    /** Primary, at joining its session: lets backgrounds ask which effects it holds. */
    public static void publish(String identity) {
        if(identity==null) return;
        InJvm.registry().put(key(identity),Map.of("effect",(Predicate<String>)id->{ var at=ResourceLocation.tryParse(id); return at!=null && BuiltInRegistries.MOB_EFFECT.containsKey(at); }));
    }
    public static void withdraw(String identity) { if(identity!=null) InJvm.registry().remove(key(identity)); }
    private static String key(String identity) { return "mmlf:player:"+identity; }

    /** Server thread, end of every server tick (both roles). */
    public static void endOfTick(MinecraftServer server) {
        if(!SessionLink.connected()) {
            // The link is gone and with it the backgrounds' effects: their modifiers come off the player.
            if(OwnEffects.modifiersApplied()) { OwnEffects.forget(null); ServerPlayer player=first(server); if(player!=null) OwnEffects.primaryTick(player); }
            return;
        }
        if(SessionLink.isPrimary()) { primaryEndOfTick(server); return; }
        backgroundEndOfTick(server);
    }

    private static void primaryEndOfTick(MinecraftServer server) {
        ServerPlayer player=first(server); if(player==null) return;
        OwnEffects.primaryTick(player);
        // The player moves on the client: its velocity is the client player's (the server's copy only has positions).
        var client=Minecraft.getInstance().player;
        Vec3 velocity=client!=null ? client.getDeltaMovement() : player.getDeltaMovement();
        Vitals now=capture(player,velocity,0);
        long at=System.nanoTime();
        // On every change (moving changes it every tick), else once a second: a lost one is replaced by the next.
        if(now.equals(state().lastSent) && at-state().sentAt<1_000_000_000L) return;
        state().lastSent=now; state().sentAt=at;
        SessionLink.send("player_vitals","*",withVersion(now,++version));
    }
    private static Vitals withVersion(Vitals v,long version) {
        return new Vitals(version,v.dimension(),v.health(),v.absorption(),v.food(),v.saturation(),v.exhaustion(),v.air(),v.fire(),v.frozen(),
            v.effects(),v.vx(),v.vy(),v.vz(),v.fallDistance(),v.sneaking(),v.sprinting(),v.swimming());
    }

    /** Primary: a background's logic changed its copy of the player; the same happens to the player here. */
    public static void applyChange(Minecraft mc,String source,Change change) {
        var server=mc.getSingleplayerServer(); if(server==null || change==null) return;
        server.execute(()->{
            ServerPlayer player=first(server); if(player==null) return;
            if(change.health()>0) player.heal(change.health());
            else if(change.health()<0 && !player.getAbilities().invulnerable) {
                float health=player.getHealth()+change.health();
                if(health>0) player.setHealth(health); else player.hurt(player.damageSources().generic(),Float.MAX_VALUE);
            }
            if(change.absorption()!=0) player.setAbsorptionAmount(Math.max(0,player.getAbsorptionAmount()+change.absorption()));
            var food=player.getFoodData();
            if(change.food()!=0) food.setFoodLevel(Mth.clamp(food.getFoodLevel()+change.food(),0,20));
            if(change.saturation()!=0) food.setSaturation(Mth.clamp(food.getSaturationLevel()+change.saturation(),0,food.getFoodLevel()));
            if(change.exhaustion()>0) player.causeFoodExhaustion(change.exhaustion());
            if(change.fire()>player.getRemainingFireTicks() && !player.fireImmune()) player.setRemainingFireTicks(change.fire());
            if(change.frozen()!=0) player.setTicksFrozen(Math.max(0,player.getTicksFrozen()+change.frozen()));
            for(Effect e:change.added()) { var holder=effect(e.id()); if(holder!=null) player.addEffect(instance(holder,e)); }
            for(String id:change.removed()) { var holder=effect(id); if(holder!=null) player.removeEffect(holder); }
            MmlfLog.sampled("player","change_applied",1000,Map.of("owner",source,"health",change.health(),"food",change.food(),
                "added",change.added().size(),"removed",change.removed().size(),"fire",change.fire()));
        });
        if(change.ix()!=0 || change.iy()!=0 || change.iz()!=0) mc.execute(()->{
            if(mc.player!=null) mc.player.setDeltaMovement(mc.player.getDeltaMovement().add(change.ix(),change.iy(),change.iz()));
        });
    }

    // ---- background ----
    /** The newest state the primary sent (network thread writes, server thread reads). */
    /** The last state already mirrored or reported, for the current copy of the player. */
    private record Baseline(WeakReference<ServerPlayer> player, Vitals vitals) {}

    /** Background, network thread: the primary's newest state. */
    public static void receive(Vitals vitals) {
        if(vitals==null) return;
        synchronized(SharedPlayer.class) { var known=state().latest; if(known==null || vitals.version()>known.version()) state().latest=vitals; }
    }
    /** The primary left: the copy is the background's own again until a primary sends its state. */
    public static synchronized void forget() { state().latest=null; state().baseline=null; OwnEffects.forgetPrimary(); }

    /** Whether {@code entity} is a background's copy of the player that reads the primary's state instead of evolving its own. */
    public static boolean shared(Entity entity) {
        return entity instanceof ServerPlayer && state().latest!=null && !SessionLink.isPrimary() && SessionLink.connected();
    }

    /** Server thread, start of every server tick. */
    public static void startOfTick(MinecraftServer server) {
        Baseline previous=state().baseline; state().baseline=null;
        if(SessionLink.isPrimary() || !SessionLink.connected()) return;
        Vitals vitals=state().latest; ServerPlayer player=first(server);
        // The server also runs queued play packets between ticks. Report those changes before the next mirror overwrites them.
        reportChanges(player,previous);
        if(vitals!=null && player!=null) OwnEffects.applyCures(player);
        if(vitals==null || player==null || !player.level().dimension().location().toString().equals(vitals.dimension())) return;
        try { apply(player,vitals); }
        catch(RuntimeException e) { MmlfLog.sampled("player","apply_failed",5000,Map.of("reason",e.toString())); return; }
        player.hurtMarked=false;
        state().baseline=new Baseline(new WeakReference<>(player),capture(player,player.getDeltaMovement(),vitals.version()));
    }

    private static void backgroundEndOfTick(MinecraftServer server) {
        ServerPlayer player=first(server);
        if(player!=null && shared(player)) OwnEffects.backgroundTick(player);
        state().baseline=reportChanges(player,state().baseline);
        if(state().baseline!=null) player.hurtMarked=false;
    }

    private static Baseline reportChanges(ServerPlayer player,Baseline previous) {
        if(previous==null || previous.player().get()!=player || !shared(player)) return null;
        Vitals start=previous.vitals();
        if(!player.level().dimension().location().toString().equals(start.dimension())) return null;
        Vitals end=capture(player,player.getDeltaMovement(),start.version());
        Change change=PlayerShare.diff(start,end,player.level().getDifficulty()==Difficulty.PEACEFUL,
            player.isInPowderSnow || player.wasInPowderSnow,player.hurtMarked,SharedPlayer::sharedEffect);
        if(change!=null) {
            SessionLink.sendToPrimary("player_change",change);
            MmlfLog.sampled("player","change_sent",1000,Map.of("health",change.health(),"food",change.food(),"exhaustion",change.exhaustion(),
                "added",change.added().size(),"removed",change.removed().size(),"fire",change.fire(),"push",change.ix()!=0 || change.iy()!=0 || change.iz()!=0));
        }
        return new Baseline(previous.player(),end);
    }

    private static void apply(ServerPlayer player,Vitals v) {
        // Never dead here: death is the primary's (its respawn sets the health again).
        player.setHealth(Math.max(v.health(),0.5f));
        player.setAbsorptionAmount(v.absorption());
        var food=player.getFoodData();
        food.setFoodLevel(v.food()); food.setSaturation(v.saturation()); food.setExhaustion(v.exhaustion());
        player.setAirSupply(v.air());
        player.setRemainingFireTicks(v.fire());
        player.setTicksFrozen(v.frozen());
        Map<Holder<MobEffect>,MobEffectInstance> active=player.getActiveEffectsMap();
        Set<Holder<MobEffect>> held=new HashSet<>();
        for(Effect e:v.effects()) {
            var holder=effect(e.id()); if(holder==null) continue;
            held.add(holder);
            var current=active.get(holder);
            // Mirror the primary's countdown without reapplying attributes or firing an effect update every tick.
            if(current==null || current.getAmplifier()!=e.amplifier() || current.isAmbient()!=e.ambient() || current.isVisible()!=e.visible() || current.showIcon()!=e.icon())
                player.forceAddEffect(instance(holder,e),null);
            else if(current.getDuration()!=e.duration()) ((MobEffectDurationAccessor)current).mmlf$setDuration(e.duration());
        }
        for(var holder:new ArrayList<>(active.keySet())) {
            String id=id(holder);
            if(!held.contains(holder) && id!=null && sharedEffect(id)) player.removeEffect(holder);
        }
        player.setDeltaMovement(v.vx(),v.vy(),v.vz());
        player.fallDistance=v.fallDistance();
        // Sneaking is not set: an interaction sets the copy's own for its use (secondary use), as the primary's input had it.
        player.setSprinting(v.sprinting());
        player.setSwimming(v.swimming());
    }

    /** Background: whether the primary holds effect {@code id} (else it is this runtime's own). */
    static boolean sharedEffect(String id) {
        var session=SessionLink.session();
        if(session!=null && InJvm.registry().get(key(session.identity())) instanceof Map<?,?> api && api.get("effect") instanceof Predicate<?> known) {
            @SuppressWarnings("unchecked") var test=(Predicate<String>)known;
            return test.test(id);
        }
        return true;
    }

    /** Server thread: the effects that tick on this runtime's copy (its own); the primary's are taken out meanwhile. */
    public static Map<Holder<MobEffect>,MobEffectInstance> holdShared(Entity entity) {
        if(!shared(entity)) return null;
        var active=((ServerPlayer)entity).getActiveEffectsMap();
        Map<Holder<MobEffect>,MobEffectInstance> taken=null;
        for(var it=active.entrySet().iterator();it.hasNext();) {
            var entry=it.next(); String id=id(entry.getKey());
            if(id==null || !sharedEffect(id)) continue;
            if(taken==null) taken=new HashMap<>();
            taken.put(entry.getKey(),entry.getValue()); it.remove();
        }
        return taken;
    }
    public static void restoreShared(Entity entity,Map<Holder<MobEffect>,MobEffectInstance> taken) {
        if(taken!=null && entity instanceof ServerPlayer player) player.getActiveEffectsMap().putAll(taken);
    }

    // ---- both ----
    private static Vitals capture(ServerPlayer player,Vec3 velocity,long version) {
        List<Effect> effects=new ArrayList<>();
        for(var e:player.getActiveEffects()) {
            String id=id(e.getEffect()); if(id==null) continue;
            effects.add(new Effect(id,e.getAmplifier(),e.getDuration(),e.isAmbient(),e.isVisible(),e.showIcon()));
        }
        var food=player.getFoodData();
        return new Vitals(version,player.level().dimension().location().toString(),player.getHealth(),player.getAbsorptionAmount(),
            food.getFoodLevel(),food.getSaturationLevel(),food.getExhaustionLevel(),player.getAirSupply(),player.getRemainingFireTicks(),
            player.getTicksFrozen(),effects,velocity.x,velocity.y,velocity.z,player.fallDistance,player.isShiftKeyDown(),player.isSprinting(),player.isSwimming());
    }
    private static String id(Holder<MobEffect> holder) { return holder.unwrapKey().map(k->k.location().toString()).orElse(null); }
    private static Holder<MobEffect> effect(String id) {
        var at=ResourceLocation.tryParse(id); if(at==null) return null;
        return BuiltInRegistries.MOB_EFFECT.getHolder(at).<Holder<MobEffect>>map(h->h).orElse(null);
    }
    private static MobEffectInstance instance(Holder<MobEffect> holder,Effect e) {
        return new MobEffectInstance(holder,e.duration(),e.amplifier(),e.ambient(),e.visible(),e.icon());
    }
    private static ServerPlayer first(MinecraftServer server) {
        var players=server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.getFirst();
    }
}
