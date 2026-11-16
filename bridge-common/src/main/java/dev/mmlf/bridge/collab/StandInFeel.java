package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.MmlfLog;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a stand-in ({@link StandInBlock}) feels to what moves on it: the owner's block, computed by the primary itself
 * without a round trip. The owner describes its block once with its report ({@link #describe}); the primary keeps that
 * per position ({@link Occupancy}) and uses it where vanilla asks the block under or around an entity.
 * <ul>
 * <li>Numbers: slipperiness, speed and jump factors, read straight from the owner's block.</li>
 * <li>Contact: falling on, bouncing after a fall, stepping on, being inside. Where the owner's block uses a vanilla
 * implementation (its own class or a superclass is one of {@link #VANILLA}), the primary runs that same vanilla code
 * on the stand-in's position: a mod's slime-like block bounces, its cobweb slows, its magma burns, with no delay.
 * The owner's copy of the player then leaves such contact alone ({@link #followerContact}), so nothing happens twice.
 * A block with its own code for it is {@code custom}: the owner runs that code on its copy of the player instead
 * ({@link #followerTick}), a tick or so later; what it does to the player comes back through {@link SharedPlayer}
 * (damage, effects, pushes) or {@link #receiveStuck} (slowing, as in a cobweb).</li>
 * <li>Sounds: footsteps and the landing sound use the owner's block's sounds; the primary plays them itself when it
 * knows them (most mod blocks use vanilla sound types), otherwise the owner plays them at the position.</li>
 * </ul>
 */
public final class StandInFeel {
    private StandInFeel() {}

    /** Contact a vanilla block's code handles, by the method's parameter types (names differ between mappings). */
    public enum Contact {
        FALL("_fall",Block.class,Level.class,BlockState.class,BlockPos.class,Entity.class,float.class),
        BOUNCE("_bounce",Block.class,BlockGetter.class,Entity.class),
        STEP("_step",Block.class,Level.class,BlockPos.class,BlockState.class,Entity.class),
        INSIDE("_inside",BlockBehaviour.class,BlockState.class,Level.class,BlockPos.class,Entity.class);
        final String key; final Class<?> base; final Class<?>[] parameters;
        Contact(String key,Class<?> base,Class<?>... parameters) { this.key=key; this.base=base; this.parameters=parameters; }
    }
    static final String CUSTOM="custom";
    /**
     * Vanilla implementations the primary may run on a stand-in's position: each needs nothing of the block state beyond
     * its block, and changes nothing in the world (a farmland or turtle egg trampled, powder snow melted are left out).
     */
    private static final Map<Class<?>,String> VANILLA=Map.of(
        SlimeBlock.class,"minecraft:slime_block", HoneyBlock.class,"minecraft:honey_block", HayBlock.class,"minecraft:hay_block",
        BedBlock.class,"minecraft:white_bed", WebBlock.class,"minecraft:cobweb", MagmaBlock.class,"minecraft:magma_block",
        WitherRoseBlock.class,"minecraft:wither_rose", CactusBlock.class,"minecraft:cactus", BaseFireBlock.class,"minecraft:fire");

    /** What the primary keeps of the owner's block; {@code null} archetypes behave as a plain block. */
    record Feel(float friction,float speed,float jump,BlockState fall,BlockState bounce,BlockState step,BlockState inside,
                boolean fallCustom,String stepSound,String fallSound,float volume,float pitch) {}

    // ---- owner: describing its block ----

    private static final Map<Class<?>,String[]> archetypes=new ConcurrentHashMap<>();
    private static volatile boolean signaturesChecked, signaturesUnique=true;

    /** Owner: adds what the stand-in needs of this block's feel to its reported properties (only what differs from a plain block). */
    static void describe(BlockState state,Map<String,String> properties) {
        Block block=state.getBlock();
        if(block.getFriction()!=0.6f) properties.put("_friction",Float.toString(block.getFriction()));
        if(block.getSpeedFactor()!=1.0f) properties.put("_speed",Float.toString(block.getSpeedFactor()));
        if(block.getJumpFactor()!=1.0f) properties.put("_jump",Float.toString(block.getJumpFactor()));
        String[] found=archetypes.computeIfAbsent(block.getClass(),StandInFeel::archetypes);
        for(Contact contact:Contact.values()) if(found[contact.ordinal()]!=null) properties.put(contact.key,found[contact.ordinal()]);
        try {
            var sound=state.getSoundType();
            var step=BuiltInRegistries.SOUND_EVENT.getKey(sound.getStepSound()); var fall=BuiltInRegistries.SOUND_EVENT.getKey(sound.getFallSound());
            if(step!=null && fall!=null) properties.put("_sound",step+";"+fall+";"+sound.getVolume()+";"+sound.getPitch());
        } catch(RuntimeException ignored) { }
    }
    /** Per contact: the vanilla block whose code the owner's block runs, {@link #CUSTOM}, or null for a plain block's. */
    private static String[] archetypes(Class<?> type) {
        String[] result=new String[Contact.values().length];
        if(!signaturesUnique()) return result;
        for(Contact contact:Contact.values()) {
            for(Class<?> c=type;c!=null && c!=Block.class && c!=BlockBehaviour.class;c=c.getSuperclass()) {
                if(!declares(c,contact)) continue;
                result[contact.ordinal()]=VANILLA.getOrDefault(c,CUSTOM);
                break;
            }
        }
        return result;
    }
    private static boolean declares(Class<?> c,Contact contact) {
        try {
            for(Method m:c.getDeclaredMethods())
                if(!Modifier.isStatic(m.getModifiers()) && !m.isBridge() && !m.isSynthetic() && m.getReturnType()==void.class
                    && java.util.Arrays.equals(m.getParameterTypes(),contact.parameters)) return true;
        } catch(Throwable e) { return false; }
        return false;
    }
    /** Methods are found by parameter types only: each must be the one such method of its base class. */
    private static boolean signaturesUnique() {
        if(signaturesChecked) return signaturesUnique;
        boolean unique=true;
        for(Contact contact:Contact.values()) {
            int n=0;
            for(Method m:contact.base.getDeclaredMethods())
                if(!Modifier.isStatic(m.getModifiers()) && m.getReturnType()==void.class && java.util.Arrays.equals(m.getParameterTypes(),contact.parameters)) n++;
            if(n!=1) { unique=false; MmlfLog.warn("occupancy","stand_in_feel_signature",Map.of("contact",contact.name(),"matches",n)); }
        }
        signaturesUnique=unique; signaturesChecked=true;
        return unique;
    }

    // ---- primary: reading a report ----

    /** The feel a reported block's properties describe, or null when it feels as a plain block. */
    static Feel parse(Map<String,String> properties) {
        if(properties==null) return null;
        float friction=parseFloat(properties.get("_friction"),0.6f), speed=parseFloat(properties.get("_speed"),1f), jump=parseFloat(properties.get("_jump"),1f);
        BlockState fall=archetype(properties.get("_fall")), bounce=archetype(properties.get("_bounce")),
            step=archetype(properties.get("_step")), inside=archetype(properties.get("_inside"));
        String stepSound=null, fallSound=null; float volume=1f, pitch=1f;
        String sound=properties.get("_sound");
        if(sound!=null) {
            String[] parts=sound.split(";");
            if(parts.length==4) { stepSound=parts[0]; fallSound=parts[1]; volume=parseFloat(parts[2],1f); pitch=parseFloat(parts[3],1f); }
        }
        boolean fallCustom=CUSTOM.equals(properties.get("_fall"));
        if(friction==0.6f && speed==1f && jump==1f && fall==null && bounce==null && step==null && inside==null && !fallCustom && stepSound==null) return null;
        return new Feel(friction,speed,jump,fall,bounce,step,inside,fallCustom,stepSound,fallSound,volume,pitch);
    }
    private static BlockState archetype(String id) {
        if(id==null || CUSTOM.equals(id)) return null;
        var at=ResourceLocation.tryParse(id);
        if(at==null || !BuiltInRegistries.BLOCK.containsKey(at) || !VANILLA.containsValue(id)) return null;
        return BuiltInRegistries.BLOCK.get(at).defaultBlockState();
    }
    private static float parseFloat(String value,float fallback) {
        if(value==null) return fallback;
        try { float v=Float.parseFloat(value); return Float.isFinite(v) ? v : fallback; } catch(NumberFormatException e) { return fallback; }
    }

    // ---- primary: using it ----

    private static Feel feel(BlockGetter level,BlockPos pos) { var entry=Occupancy.entry(level,pos); return entry==null ? null : entry.feel(); }

    public static float friction(BlockGetter level,BlockPos pos) { Feel f=feel(level,pos); return f==null ? 0.6f : f.friction(); }
    static BlockState fall(BlockGetter level,BlockPos pos) { Feel f=feel(level,pos); return f==null ? null : f.fall(); }
    /** The owner's block has its own fall code: the owner deals the fall ({@link #followerTick}), not the stand-in. */
    static boolean fallCustom(BlockGetter level,BlockPos pos) { Feel f=feel(level,pos); return f!=null && f.fallCustom(); }
    static BlockState bounce(BlockGetter level,BlockPos pos) { Feel f=feel(level,pos); return f==null ? null : f.bounce(); }
    static BlockState step(BlockGetter level,BlockPos pos) { Feel f=feel(level,pos); return f==null ? null : f.step(); }
    static BlockState inside(BlockGetter level,BlockPos pos) { Feel f=feel(level,pos); return f==null ? null : f.inside(); }

    /** {@code Entity.getBlockSpeedFactor} where a stand-in is involved, as vanilla computes it; null to leave it to vanilla. */
    public static Float speedFactor(Entity entity) {
        Level level=entity.level(); BlockPos at=entity.blockPosition(), below=entity.getBlockPosBelowThatAffectsMyMovement();
        BlockState here=level.getBlockState(at), under=level.getBlockState(below);
        if(!StandInBlock.is(here) && !StandInBlock.is(under)) return null;
        float f=speed(level,here,at);
        if(here.is(Blocks.WATER) || here.is(Blocks.BUBBLE_COLUMN)) return f;
        return f==1.0f ? speed(level,under,below) : f;
    }
    /** {@code Entity.getBlockJumpFactor} likewise. */
    public static Float jumpFactor(Entity entity) {
        Level level=entity.level(); BlockPos at=entity.blockPosition(), below=entity.getBlockPosBelowThatAffectsMyMovement();
        BlockState here=level.getBlockState(at), under=level.getBlockState(below);
        if(!StandInBlock.is(here) && !StandInBlock.is(under)) return null;
        float f=jump(level,here,at);
        return f==1.0f ? jump(level,under,below) : f;
    }
    private static float speed(Level level,BlockState state,BlockPos pos) {
        if(!StandInBlock.is(state)) return state.getBlock().getSpeedFactor();
        Feel f=feel(level,pos); return f==null ? 1f : f.speed();
    }
    private static float jump(Level level,BlockState state,BlockPos pos) {
        if(!StandInBlock.is(state)) return state.getBlock().getJumpFactor();
        Feel f=feel(level,pos); return f==null ? 1f : f.jump();
    }

    /** {@code Entity.playStepSound} on a stand-in: true when handled here (played, or passed to the owner). */
    public static boolean stepSound(Entity entity,BlockPos pos,BlockState state) {
        if(!StandInBlock.is(state)) return false;
        Feel f=feel(entity.level(),pos); if(f==null || f.stepSound()==null) return false;
        play(entity,pos,f.stepSound(),f.volume()*0.15f,f.pitch());
        return true;
    }
    /** {@code LivingEntity.playBlockFallSound} on a stand-in, at the position vanilla takes. */
    public static boolean fallSound(Entity entity) {
        if(entity.isSilent()) return false;
        BlockPos pos=new BlockPos(Mth.floor(entity.getX()),Mth.floor(entity.getY()-0.2f),Mth.floor(entity.getZ()));
        if(!StandInBlock.is(entity.level().getBlockState(pos))) return false;
        Feel f=feel(entity.level(),pos); if(f==null || f.fallSound()==null) return false;
        play(entity,pos,f.fallSound(),f.volume()*0.5f,f.pitch()*0.75f);
        return true;
    }
    private static final java.util.Set<String> forwardedLogged=ConcurrentHashMap.newKeySet();
    private static void play(Entity entity,BlockPos pos,String id,float volume,float pitch) {
        var at=ResourceLocation.tryParse(id);
        SoundEvent known=at==null ? null : BuiltInRegistries.SOUND_EVENT.get(at);
        if(known!=null) { entity.playSound(known,volume,pitch); return; }
        // A sound only the owner has: its client plays it at the position, for this runtime's own player only.
        if(!entity.level().isClientSide() || entity!=Minecraft.getInstance().player || !SessionLink.connected()) return;
        var entry=Occupancy.entry(entity.level(),pos); if(entry==null) return;
        if(forwardedLogged.add(id)) MmlfLog.info("occupancy","stand_in_sound_forwarded",Map.of("sound",id,"owner",entry.owner()));
        SessionLink.send("event",entry.owner(),new Event("mmlf:stand_in_sound",Map.of("x",Double.toString(pos.getX()+0.5),"y",Double.toString(pos.getY()+0.5),
            "z",Double.toString(pos.getZ()+0.5),"sound",id,"volume",Float.toString(volume),"pitch",Float.toString(pitch))));
    }
    /** Owner: plays a sound of its block for the primary's player ({@link #play}). */
    public static void playForPrimary(Map<String,String> values) {
        var mc=Minecraft.getInstance();
        mc.execute(()->{
            if(mc.level==null) return;
            try {
                var at=ResourceLocation.parse(values.get("sound"));
                SoundEvent sound=BuiltInRegistries.SOUND_EVENT.get(at); if(sound==null) sound=SoundEvent.createVariableRangeEvent(at);
                mc.level.playLocalSound(Double.parseDouble(values.get("x")),Double.parseDouble(values.get("y")),Double.parseDouble(values.get("z")),
                    sound,SoundSource.PLAYERS,Float.parseFloat(values.get("volume")),Float.parseFloat(values.get("pitch")),false);
            } catch(RuntimeException e) { MmlfLog.sampled("occupancy","stand_in_sound_failed",10000,Map.of("reason",e.toString())); }
        });
    }

    // ---- owner: its copy of the player ----

    /**
     * Owner: whether its copy of the player skips this contact where vanilla movement would make it: the primary runs a
     * vanilla implementation on the stand-in, and {@link #followerTick} runs a custom one, once per tick. Every contact of
     * the copy is logged (sampled) for diagnosis.
     */
    public static boolean followerContact(Entity entity,BlockState state,Contact contact) {
        if(SessionLink.isPrimary() || !SharedPlayer.shared(entity) || running.get()) return false;
        Block block=state.getBlock();
        String archetype=archetypes.computeIfAbsent(block.getClass(),StandInFeel::archetypes)[contact.ordinal()];
        boolean skip=archetype!=null;
        if(archetype!=null) MmlfLog.sampled("player","follower_contact",2000,Map.of("contact",contact.name(),"block",String.valueOf(BuiltInRegistries.BLOCK.getKey(block)),"archetype",archetype,"skipped",skip));
        return skip;
    }
    public static boolean followerFall(Entity entity,BlockState state) { return followerContact(entity,state,Contact.FALL); }
    public static boolean followerBounce(Entity entity,BlockState state) { return followerContact(entity,state,Contact.BOUNCE); }
    public static boolean followerStep(Entity entity,BlockState state) { return followerContact(entity,state,Contact.STEP); }
    public static boolean followerInside(Entity entity,BlockState state) { return followerContact(entity,state,Contact.INSIDE); }

    // ---- owner: custom contact, run on its copy of the player ----

    private static final ThreadLocal<Boolean> running=ThreadLocal.withInitial(()->false);
    private static volatile net.minecraft.world.phys.Vec3 stuck;
    private static long lastVersion=-1;
    private static dev.mmlf.common.PlayerShare.Vitals previous;
    private static boolean custom(BlockState state,Contact contact) {
        return CUSTOM.equals(archetypes.computeIfAbsent(state.getBlock().getClass(),StandInFeel::archetypes)[contact.ordinal()]);
    }
    /**
     * Owner, server thread, each tick once its copy holds the primary's state ({@code SharedPlayer.startOfTick}): the
     * contact vanilla movement would make with its own blocks whose code is custom, as the primary's player moves.
     * Inside: every own block the player's box overlaps. Stepping on: the own block under it while the primary stands.
     * Falling on and bouncing: when the primary's fall distance went back to zero over an own block (it landed), with the
     * distance and the downward speed it had. A bounce counts only upward; any change of speed is sent as a push.
     */
    public static void followerTick(net.minecraft.server.level.ServerPlayer player,dev.mmlf.common.PlayerShare.Vitals now) {
        if(!signaturesUnique()) return;
        Level level=player.level();
        boolean fresh=now.version()!=lastVersion; var before=previous;
        if(fresh) { previous=now; lastVersion=now.version(); }
        var start=player.getDeltaMovement();
        stuck=null; running.set(true);
        try {
            var box=player.getBoundingBox().deflate(1.0E-5);
            for(BlockPos pos:BlockPos.betweenClosed(Mth.floor(box.minX),Mth.floor(box.minY),Mth.floor(box.minZ),Mth.floor(box.maxX),Mth.floor(box.maxY),Mth.floor(box.maxZ))) {
                BlockState state=level.getBlockState(pos);
                if(state.isAir() || !custom(state,Contact.INSIDE) || !SharedWorld.ownBlock(level,state,pos)) continue;
                state.entityInside(level,pos.immutable(),player);
            }
            BlockPos on=player.getOnPosLegacy(); BlockState under=level.getBlockState(on);
            if(!under.isAir() && SharedWorld.ownBlock(level,under,on)) {
                if(fresh && before!=null && before.fallDistance()>0 && now.fallDistance()==0) {
                    if(custom(under,Contact.FALL)) under.getBlock().fallOn(level,under,on,player,before.fallDistance());
                    if(custom(under,Contact.BOUNCE)) {
                        player.setDeltaMovement(now.vx(),before.vy(),now.vz());
                        under.getBlock().updateEntityAfterFallOn(level,player);
                        if(player.getDeltaMovement().y<=0) player.setDeltaMovement(start);
                    }
                }
                if(PlayerFollower.primaryGrounded && custom(under,Contact.STEP)) under.getBlock().stepOn(level,on,under,player);
            }
        } catch(RuntimeException e) {
            MmlfLog.sampled("player","follower_contact_failed",10000,Map.of("reason",e.toString()));
        } finally { running.set(false); }
        if(!player.getDeltaMovement().equals(start)) player.hurtMarked=true;
        var slowed=stuck;
        if(slowed!=null) SessionLink.sendToPrimary("event",new Event("mmlf:player_stuck",Map.of("x",Double.toString(slowed.x),"y",Double.toString(slowed.y),"z",Double.toString(slowed.z))));
    }
    /** Owner, {@code Entity.makeStuckInBlock} on its copy while {@link #followerTick} runs: the slowing goes to the primary. */
    public static void stuckFollower(Entity entity,net.minecraft.world.phys.Vec3 multiplier) {
        if(running.get() && !SessionLink.isPrimary() && SharedPlayer.shared(entity)) stuck=multiplier;
    }

    // ---- primary: slowing from a custom block ----

    private static volatile net.minecraft.world.phys.Vec3 received;
    private static volatile long receivedAt;
    /** Primary, network thread: the owner's custom block slows the player (sent each tick it does). */
    public static void receiveStuck(Map<String,String> values) {
        try {
            received=new net.minecraft.world.phys.Vec3(Double.parseDouble(values.get("x")),Double.parseDouble(values.get("y")),Double.parseDouble(values.get("z")));
            receivedAt=System.nanoTime();
        } catch(RuntimeException ignored) { }
    }
    /** Primary, end of each client tick: a recent slowing applies to the player's next movement, as a cobweb's would. */
    public static void clientTick(Minecraft mc) {
        var slowed=received;
        if(slowed==null || mc.player==null || !SessionLink.isPrimary()) return;
        if(System.nanoTime()-receivedAt>150_000_000L) { received=null; return; }
        mc.player.makeStuckInBlock(Blocks.COBWEB.defaultBlockState(),slowed);
    }
}
