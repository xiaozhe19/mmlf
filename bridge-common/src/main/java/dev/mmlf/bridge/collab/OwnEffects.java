package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.platform.EffectCures;
import dev.mmlf.bridge.platform.RemoteEffect;
import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mmlf.bridge.mixin.SpriteContentsAccessor;
import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.PlayerShare;
import dev.mmlf.common.PlayerShare.Modifier;
import dev.mmlf.common.PlayerShare.Own;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Effects only a background knows (its own mod registers them; the primary's registry has no such effect). They are
 * held by the background's copy of the player and tick there ({@link SharedPlayer}); the rest of the one player's
 * state goes with them:
 * <ul>
 *   <li>The primary's player losing its effects (death, vanilla's removal of all, milk, totem, a NeoForge cure) is sent
 *       to the backgrounds ({@code mmlf:player_cure}), which remove their own effects the cure removes.</li>
 *   <li>Each background sends its own effects to the primary ({@code player_own}) when they change, with their
 *       attribute modifiers; the primary puts the modifiers of attributes it knows on its player as transient
 *       modifiers (vanilla syncs them to the client, where movement is worked out).</li>
 *   <li>The primary's client shows them in vanilla's effect HUD and inventory list through {@link RemoteEffect} slots,
 *       with the name in the player's language and the icon the background read from its own effect atlas.</li>
 * </ul>
 */
public final class OwnEffects {
    private OwnEffects() {}
    /** This world's part of OwnEffects: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        boolean deathSent;
        final ConcurrentLinkedQueue<String> cures=new ConcurrentLinkedQueue<>();
        List<Own> lastSent;
        long sentAt;
        final Map<String,Received> bySource=new ConcurrentHashMap<>();
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("own_effects",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }


    private static String id(Holder<MobEffect> holder) { return holder.unwrapKey().map(k->k.location().toString()).orElse(null); }

    // ---- primary: cures ----

    /** Primary, server thread: its player lost effects to {@code cure} (vanilla's removal of all, a NeoForge cure). */
    public static void primaryCured(LivingEntity entity,String cure) {
        if(!(entity instanceof ServerPlayer) || entity.level().isClientSide || !SessionLink.connected() || !SessionLink.isPrimary() || cure==null) return;
        SessionLink.sendReliable("event","*",new Event("mmlf:player_cure",Map.of("cure",cure)));
        MmlfLog.info("player","cure_sent",Map.of("cure",cure));
    }

    // ---- background: cures and own effects ----
    /** Icons read from this runtime's effect atlas, by effect id: {width, height, base64 RGBA}. */
    private record Icon(int width,int height,String pixels) {}
    private static final Map<String,Icon> icons=new ConcurrentHashMap<>();
    private static final Set<String> iconsAsked=ConcurrentHashMap.newKeySet();

    /** Background, network thread: the primary's player received {@code cure}. */
    public static void cured(String cure) { if(cure!=null) state().cures.add(cure); }

    /** Background: the primary left; the next one hears of the own effects afresh. */
    static void forgetPrimary() { state().lastSent=null; state().cures.clear(); }

    /** Background, server thread, before the primary's state is set on the copy: its own effects the cures remove go. */
    static void applyCures(ServerPlayer copy) {
        for(String cure;(cure=state().cures.poll())!=null;) {
            int removed=0;
            for(var instance:new ArrayList<>(copy.getActiveEffects())) {
                String id=id(instance.getEffect());
                if(id==null || SharedPlayer.sharedEffect(id) || !EffectCures.removes(instance,cure)) continue;
                copy.removeEffect(instance.getEffect()); removed++;
            }
            MmlfLog.info("player","cure_applied",Map.of("cure",cure,"removed",removed));
        }
    }

    /** Background, server thread, end of tick: its own effects to the primary, when they changed (and every 5 s). */
    static void backgroundTick(ServerPlayer copy) {
        List<Own> own=new ArrayList<>();
        for(var instance:copy.getActiveEffects()) {
            var holder=instance.getEffect(); String id=id(holder);
            if(id==null || SharedPlayer.sharedEffect(id)) continue;
            own.add(describe(holder,id,instance));
        }
        long now=System.nanoTime();
        boolean changed=state().lastSent==null ? !own.isEmpty() : !timeless(own).equals(timeless(state().lastSent));
        boolean refresh=!own.isEmpty() && now-state().sentAt>5_000_000_000L;
        if(!changed && !refresh) return;
        state().lastSent=own; state().sentAt=now;
        SessionLink.sendToPrimary("player_own",new PlayerShare.OwnEffects(own));
        if(changed) MmlfLog.info("player","own_effects_sent",Map.of("effects",own.stream().map(Own::id).toList()));
    }
    private static List<Own> timeless(List<Own> list) { return list.stream().map(Own::timeless).toList(); }

    private static Own describe(Holder<MobEffect> holder,String id,MobEffectInstance instance) {
        MobEffect effect=holder.value();
        List<Modifier> modifiers=new ArrayList<>();
        effect.createModifiers(instance.getAmplifier(),(attribute,modifier)->{
            String at=attribute.unwrapKey().map(k->k.location().toString()).orElse(null);
            if(at!=null) modifiers.add(new Modifier(at,modifier.id().toString(),modifier.amount(),modifier.operation().name()));
        });
        Icon icon=icons.get(id);
        if(icon==null && iconsAsked.add(id)) Minecraft.getInstance().execute(()->readIcon(holder,id));
        String category=effect.getCategory().name().toLowerCase(java.util.Locale.ROOT);
        return new Own(id,effect.getDisplayName().getString(),instance.getAmplifier(),instance.getDuration(),instance.isAmbient(),
            instance.isVisible(),instance.showIcon(),category,effect.getColor(),icon==null?0:icon.width(),icon==null?0:icon.height(),
            icon==null?null:icon.pixels(),modifiers);
    }

    /** Background, client thread: the effect's icon from this runtime's effect atlas (its first frame). */
    private static void readIcon(Holder<MobEffect> holder,String id) {
        try {
            var contents=Minecraft.getInstance().getMobEffectTextures().get(holder).contents();
            NativeImage image=((SpriteContentsAccessor)contents).mmlf$originalImage();
            int width=contents.width(),height=contents.height();
            byte[] rgba=new byte[width*height*4];
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                int abgr=image.getPixelRGBA(x,y),i=(y*width+x)*4;
                rgba[i]=(byte)abgr; rgba[i+1]=(byte)(abgr>>8); rgba[i+2]=(byte)(abgr>>16); rgba[i+3]=(byte)(abgr>>>24);
            }
            icons.put(id,new Icon(width,height,Base64.getEncoder().encodeToString(rgba)));
        } catch(RuntimeException e) {
            MmlfLog.warn("player","own_icon_failed",Map.of("effect",id,"reason",e.toString()));
        }
    }

    // ---- primary: what the backgrounds sent ----
    private record Received(List<Own> effects,long at,long sequence,long tick) {}
    private static final AtomicLong sequence=new AtomicLong();
    /** The primary's server ticks (not time: a paused game does not age what was received). */
    private static volatile long serverTicks;
    /** A background repeats its effects every 5 s while it has any; three missed repeats and they are gone with it. */
    private static final long STALE_TICKS=300;

    /** Primary, network thread. */
    public static void receive(String source,PlayerShare.OwnEffects effects) {
        if(source==null || effects==null) return;
        if(effects.effects().isEmpty()) state().bySource.remove(source);
        else state().bySource.put(source,new Received(effects.effects(),System.nanoTime(),sequence.incrementAndGet(),serverTicks));
    }
    /** Primary, server thread: modifiers are on a player (a link lost with them still on takes them off). */
    static boolean modifiersApplied() { return !applied.isEmpty(); }
    /** Primary: a background left (null: all of them); its effects go with it. */
    public static void forget(String source) { if(source==null) state().bySource.clear(); else state().bySource.remove(source); }

    // ---- primary, server thread: attribute modifiers and death ----
    private record Applied(Holder<Attribute> attribute,ResourceLocation id) {}
    private static final Map<ResourceLocation,Applied> applied=new HashMap<>();
    private static WeakReference<ServerPlayer> appliedTo=new WeakReference<>(null);

    /** Primary, server thread, end of every tick. */
    static void primaryTick(ServerPlayer player) {
        boolean dead=player.isDeadOrDying();
        if(dead && !state().deathSent) primaryCured(player,PlayerShare.CURE_DEATH);
        state().deathSent=dead;
        long tick=++serverTicks;
        state().bySource.values().removeIf(received->tick-received.tick()>STALE_TICKS);
        // A respawned player is a new one, without the modifiers.
        if(appliedTo.get()!=player) { applied.clear(); appliedTo=new WeakReference<>(player); }
        Map<ResourceLocation,AttributeModifier> wanted=new LinkedHashMap<>();
        Map<ResourceLocation,Holder<Attribute>> attributes=new HashMap<>();
        for(var entry:state().bySource.entrySet()) for(Own own:entry.getValue().effects()) for(Modifier m:own.modifiers()) {
            var at=ResourceLocation.tryParse(m.attribute()); if(at==null) continue;
            var attribute=BuiltInRegistries.ATTRIBUTE.getHolder(at); if(attribute.isEmpty()) continue;
            AttributeModifier.Operation operation;
            try { operation=AttributeModifier.Operation.valueOf(m.operation()); } catch(IllegalArgumentException e) { continue; }
            var id=ResourceLocation.fromNamespaceAndPath("mmlf_bridge","own/"+path(entry.getKey())+"/"+path(own.id())+"/"+path(m.id()));
            wanted.put(id,new AttributeModifier(id,m.amount(),operation));
            attributes.put(id,attribute.get());
        }
        for(var it=applied.values().iterator();it.hasNext();) {
            Applied a=it.next(); if(wanted.containsKey(a.id())) continue;
            var instance=player.getAttribute(a.attribute()); if(instance!=null) instance.removeModifier(a.id());
            it.remove();
        }
        for(var entry:wanted.entrySet()) {
            var attribute=attributes.get(entry.getKey()); var instance=player.getAttribute(attribute); if(instance==null) continue;
            AttributeModifier modifier=entry.getValue(), current=instance.getModifier(entry.getKey());
            if(current==null || current.amount()!=modifier.amount() || current.operation()!=modifier.operation()) {
                instance.removeModifier(entry.getKey()); instance.addTransientModifier(modifier);
                MmlfLog.sampled("player","own_modifier_applied",1000,Map.of("attribute",attribute.unwrapKey().map(k->k.location().toString()).orElse("?"),
                    "id",entry.getKey().toString(),"amount",modifier.amount()));
            }
            applied.put(entry.getKey(),new Applied(attribute,entry.getKey()));
        }
    }
    /** A resource path from any id: lower case, {@code :} as {@code /}, anything else not allowed as {@code _}. */
    private static String path(String s) {
        StringBuilder b=new StringBuilder();
        for(char c:s.toLowerCase(java.util.Locale.ROOT).toCharArray()) b.append(c==':' ? '/' : ResourceLocation.validPathChar(c) ? c : '_');
        return b.toString();
    }

    // ---- primary, client thread: showing them ----
    private static final Map<String,Integer> slotOf=new HashMap<>();
    private static final long[] shown=new long[RemoteEffect.SLOTS];
    private static Object shownOn;

    /** Primary, client thread, every client tick: the slots stand for the backgrounds' effects in the client player's effects. */
    public static void clientTick(Minecraft mc) {
        var player=mc.player;
        Map<String,Own> wanted=new LinkedHashMap<>(); Map<String,Received> from=new HashMap<>();
        if(player!=null && SessionLink.isPrimary()) for(var entry:state().bySource.entrySet()) for(Own own:entry.getValue().effects()) {
            String key=entry.getKey()+"|"+own.id(); wanted.put(key,own); from.put(key,entry.getValue());
        }
        if(wanted.isEmpty() && slotOf.isEmpty()) return;
        slotOf.keySet().removeIf(key->!wanted.containsKey(key));
        for(String key:wanted.keySet()) {
            if(slotOf.containsKey(key)) continue;
            for(int slot=0;slot<RemoteEffect.SLOTS;slot++) if(!slotOf.containsValue(slot)) { slotOf.put(key,slot); break; }
        }
        if(player!=shownOn) { Arrays.fill(shown,0); shownOn=player; }
        Map<Integer,String> keyOf=new HashMap<>(); slotOf.forEach((k,s)->keyOf.put(s,k));
        for(int slot=0;slot<RemoteEffect.SLOTS;slot++) {
            var holder=BuiltInRegistries.MOB_EFFECT.getHolder(RemoteEffect.key(slot)).orElse(null);
            if(holder==null || !(holder.value() instanceof RemoteEffect effect)) continue;
            String key=keyOf.get(slot);
            if(key==null || player==null) {
                effect.bound=null; shown[slot]=0;
                if(player!=null) player.getActiveEffectsMap().remove(holder);
                continue;
            }
            Own own=wanted.get(key); Received received=from.get(key);
            effect.bound=own;
            var active=player.getActiveEffectsMap();
            if(shown[slot]==received.sequence() && active.containsKey(holder)) continue;
            int elapsed=(int)((System.nanoTime()-received.at())/50_000_000L);
            int left=own.duration()<0 ? MobEffectInstance.INFINITE_DURATION : Math.max(1,own.duration()-elapsed);
            active.put(holder,new MobEffectInstance(holder,left,own.amplifier(),own.ambient(),own.visible(),own.icon()));
            shown[slot]=received.sequence();
        }
    }

    // ---- primary, render thread: the slots' icons ----
    private static final class IconSprite extends TextureAtlasSprite {
        // The texture is the icon alone: an "atlas" of the icon's size with the sprite at its origin (u, v from 0 to 1).
        IconSprite(ResourceLocation texture,SpriteContents contents,int width,int height) { super(texture,contents,width,height,0,0); }
    }
    private record SlotIcon(String pixels,ResourceLocation texture,IconSprite sprite) {}
    private static final SlotIcon[] slotIcons=new SlotIcon[RemoteEffect.SLOTS];

    /** The icon vanilla draws for {@code holder}: a slot's is the background's icon (transparent until it arrived); null for any other effect. */
    public static TextureAtlasSprite sprite(Holder<MobEffect> holder) {
        if(!(holder.value() instanceof RemoteEffect effect)) return null;
        Own own=effect.bound;
        String pixels=own==null || own.iconPixels()==null || own.iconWidth()<=0 || own.iconHeight()<=0 ? null : own.iconPixels();
        SlotIcon current=slotIcons[effect.slot];
        if(current!=null && Objects.equals(current.pixels(),pixels)) return current.sprite();
        try {
            int width=pixels==null?1:own.iconWidth(), height=pixels==null?1:own.iconHeight();
            byte[] rgba=pixels==null ? new byte[4] : Base64.getDecoder().decode(pixels);
            if(rgba.length!=width*height*4) { width=height=1; rgba=new byte[4]; }
            NativeImage shown=image(rgba,width,height), kept=image(rgba,width,height);
            var manager=Minecraft.getInstance().getTextureManager();
            var texture=manager.register("mmlf-remote-effect",new DynamicTexture(shown));
            var contents=new SpriteContents(ResourceLocation.fromNamespaceAndPath("mmlf_bridge","remote_effect_icon_"+effect.slot),new FrameSize(width,height),kept,ResourceMetadata.EMPTY);
            var sprite=new IconSprite(texture,contents,width,height);
            if(current!=null) { manager.release(current.texture()); current.sprite().contents().close(); }
            slotIcons[effect.slot]=new SlotIcon(pixels,texture,sprite);
            return sprite;
        } catch(RuntimeException e) {
            MmlfLog.sampled("player","own_icon_draw_failed",5000,Map.of("reason",e.toString()));
            return current==null ? null : current.sprite();
        }
    }
    private static NativeImage image(byte[] rgba,int width,int height) {
        var image=new NativeImage(width,height,false);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int i=(y*width+x)*4;
            image.setPixelRGBA(x,y,(rgba[i]&255)|((rgba[i+1]&255)<<8)|((rgba[i+2]&255)<<16)|((rgba[i+3]&255)<<24));
        }
        return image;
    }
}
