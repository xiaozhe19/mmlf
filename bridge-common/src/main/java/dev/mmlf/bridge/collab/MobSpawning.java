package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;
import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.MmlfLog;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;

/**
 * Natural spawning across runtimes. Each runtime spawns its own mobs in the one world: the primary as vanilla does, a
 * background (a hollow world following the primary's save) only its mods' mobs, never vanilla ones, which are the
 * primary's. Together they keep to one set of caps and one set of weights:
 * <ul>
 * <li>Caps: every second each runtime tells the other how many mobs of each category its spawner counted, and each
 * adds the other's count to its own when checking a category's cap ({@code SpawnStateMixin}).</li>
 * <li>Weights: a background draws from the whole spawn list of the biome (vanilla and its mods' entries) and drops a
 * vanilla draw; the primary drops a draw with the share of the background's mods' weight, which the background reports
 * per biome and category. Each mob then appears about as often as in one game with both sets of mods.</li>
 * <li>Mobs placed when a chunk is generated: a background's chunk places its mods' creatures once it holds the
 * primary's terrain (co-generation), drawn as above.</li>
 * <li>A background's vanilla special spawners (phantoms, patrols, cats, wandering trader) stay off.</li>
 * </ul>
 * {@code MMLF_BACKGROUND_SPAWN=off} (or {@code -Dmmlf.backgroundSpawn=off}): a background spawns nothing, as before.
 */
public final class MobSpawning {
    private MobSpawning() {}
    public static final boolean ENABLED=!"off".equalsIgnoreCase(System.getProperty("mmlf.backgroundSpawn",System.getenv().getOrDefault("MMLF_BACKGROUND_SPAWN","on")));
    private static final long STALE_NANOS=5_000_000_000L;

    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        volatile Map<MobCategory,Integer> remoteCounts=Map.of();
        volatile long remoteAt;
        volatile Map<String,Integer> remoteWeights=Map.of();
        boolean weightsSent;
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("mob_spawning",State::new);
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    /** A background whose natural spawning is its mods' share of the primary's world. */
    public static boolean backgroundSpawns() { return ENABLED && SharedWorld.hollow() && SessionLink.connected(); }
    private static boolean primaryShares() { return ENABLED && SessionLink.isPrimary() && SessionLink.connected(); }
    private static boolean vanilla(EntityType<?> type) { return BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals("minecraft"); }

    // ---- counts and weights between runtimes ----

    /** Server thread, end of every server tick (both roles). */
    public static void endOfTick(MinecraftServer server) {
        if(!ENABLED || !SessionLink.connected() || server.getTickCount()%20!=0) return;
        boolean primary=SessionLink.isPrimary();
        if(!primary && !SharedWorld.hollow()) return;
        var players=server.getPlayerList().getPlayers(); if(players.isEmpty()) return;
        ServerLevel level=players.getFirst().serverLevel();
        var spawn=level.getChunkSource().getLastSpawnState();
        if(spawn!=null) {
            Map<String,String> values=new HashMap<>();
            values.put("dimension",level.dimension().location().toString());
            for(var e:spawn.getMobCategoryCounts().object2IntEntrySet()) values.put(e.getKey().getName(),Integer.toString(e.getIntValue()));
            SessionLink.send("event","*",new Event("mmlf:mob_counts",values));
        }
        if(!primary && !state().weightsSent) { state().weightsSent=true; sendWeights(server); }
    }
    /** Background: its mods' spawn weight per biome and category ("biome|category"), once per world. */
    private static void sendWeights(MinecraftServer server) {
        Map<String,String> values=new HashMap<>();
        for(var entry:server.registryAccess().registryOrThrow(Registries.BIOME).entrySet()) {
            var settings=entry.getValue().getMobSettings();
            for(MobCategory category:MobCategory.values()) {
                int weight=0;
                for(SpawnerData data:settings.getMobs(category).unwrap()) if(!vanilla(data.type)) weight+=data.getWeight().asInt();
                if(weight>0) values.put(entry.getKey().location()+"|"+category.getName(),Integer.toString(weight));
            }
        }
        MmlfLog.info("spawn","weights_sent",Map.of("entries",values.size()));
        SessionLink.sendReliable("event","*",new Event("mmlf:spawn_weights",values));
    }
    /** Network thread: the other runtime's counts. */
    public static void receiveCounts(Map<String,String> values) {
        Map<MobCategory,Integer> counts=new EnumMap<>(MobCategory.class);
        for(MobCategory category:MobCategory.values()) {
            String v=values.get(category.getName());
            if(v!=null) try { counts.put(category,Integer.parseInt(v)); } catch(NumberFormatException ignored) { }
        }
        var s=state(); s.remoteCounts=counts; s.remoteAt=System.nanoTime();
        MmlfLog.sampled("spawn","counts_received",30000,Map.of("counts",counts.toString()));
    }
    /** Primary, network thread: the background's mods' weights. */
    public static void receiveWeights(Map<String,String> values) {
        Map<String,Integer> weights=new HashMap<>();
        values.forEach((k,v)->{ try { weights.put(k,Integer.parseInt(v)); } catch(NumberFormatException ignored) { } });
        state().remoteWeights=weights;
        MmlfLog.info("spawn","weights_received",Map.of("entries",weights.size()));
    }
    /** The other runtime's count of a category, 0 when none arrived lately. */
    public static int remoteCount(MobCategory category) {
        if(!ENABLED || !SessionLink.connected()) return 0;
        var s=state();
        if(System.nanoTime()-s.remoteAt>STALE_NANOS) return 0;
        return s.remoteCounts.getOrDefault(category,0);
    }

    // ---- spawning ----

    /** {@code NaturalSpawner.getRandomSpawnMobAt}: the draw, or none where the other runtime's share was drawn. */
    public static Optional<SpawnerData> natural(ServerLevel level,MobCategory category,RandomSource random,BlockPos pos,Optional<SpawnerData> drawn) {
        if(drawn.isEmpty()) return drawn;
        if(backgroundSpawns()) return vanilla(drawn.get().type) ? Optional.empty() : drawn;
        if(!primaryShares()) return drawn;
        var biome=level.getBiome(pos);
        var key=biome.unwrapKey().orElse(null); if(key==null) return drawn;
        Integer theirs=state().remoteWeights.get(key.location()+"|"+category.getName());
        if(theirs==null || theirs<=0) return drawn;
        int ours=0;
        for(SpawnerData data:biome.value().getMobSettings().getMobs(category).unwrap()) ours+=data.getWeight().asInt();
        return random.nextInt(ours+theirs)<theirs ? Optional.empty() : drawn;
    }
    /** {@code NaturalSpawner.spawnMobsForChunkGeneration}: a background places only its mods' creatures. */
    public static Optional<SpawnerData> generation(Optional<SpawnerData> drawn) {
        if(drawn.isEmpty() || !backgroundSpawns()) return drawn;
        return vanilla(drawn.get().type) ? Optional.empty() : drawn;
    }
    /** Whether a background's chunk places mobs as it is generated: only once it holds the primary's terrain. */
    public static boolean generationSpawns() { return backgroundSpawns() && CoGeneration.backgroundDecorates(); }
    /** {@code ServerLevel.tickCustomSpawners}: a background's vanilla special spawners make vanilla mobs, the primary's. */
    public static boolean skipCustomSpawner(CustomSpawner spawner) {
        return ENABLED && SharedWorld.hollow() && spawner.getClass().getName().startsWith("net.minecraft.");
    }
}
