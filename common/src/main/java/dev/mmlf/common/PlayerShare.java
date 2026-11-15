package dev.mmlf.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The one player's state, held by the primary. A background's copy of the player reads it ({@link Vitals}, sent by the
 * primary every tick and set on the copy before the background's tick) and does not evolve it itself: no hunger, no
 * natural regeneration, no ticking of the primary's effects. Changes made to the copy during a tick or by queued
 * work between ticks are compared against the last mirrored or reported state ({@link #diff}) and sent as a
 * {@link Change}. The primary applies them to the real player and includes the result in the next {@link Vitals}.
 */
public final class PlayerShare {
    private PlayerShare() {}

    /** One status effect: {@code id} is its registry name; {@code duration} -1 is infinite. */
    public record Effect(String id, int amplifier, int duration, boolean ambient, boolean visible, boolean icon) {}

    /** The primary's player at the end of a primary tick. Velocity, fall distance and stance are what its movement uses. */
    public record Vitals(long version, String dimension, float health, float absorption, int food, float saturation,
                         float exhaustion, int air, int fire, int frozen, List<Effect> effects,
                         double vx, double vy, double vz, float fallDistance, boolean sneaking, boolean sprinting, boolean swimming) {
        public Vitals { effects = effects == null ? List.of() : List.copyOf(effects); }
    }

    /**
     * What a background's logic did to its copy of the player in one tick, as changes to the primary's player: amounts
     * are added to the primary's ({@code health}, {@code absorption}, {@code food}, {@code saturation},
     * {@code exhaustion}, {@code frozen}); {@code fire} sets the burning time to at least that many ticks; effects are
     * added (as vanilla adds them: a stronger or longer one replaces) or removed; {@code ix,iy,iz} is a push to add to
     * the player's velocity.
     */
    public record Change(float health, float absorption, int food, float saturation, float exhaustion, int fire, int frozen,
                         List<Effect> added, List<String> removed, double ix, double iy, double iz) {
        public Change {
            added = added == null ? List.of() : List.copyOf(added);
            removed = removed == null ? List.of() : List.copyOf(removed);
        }
        public boolean empty() {
            return health == 0 && absorption == 0 && food == 0 && saturation == 0 && exhaustion == 0 && fire == 0 && frozen == 0
                && added.isEmpty() && removed.isEmpty() && ix == 0 && iy == 0 && iz == 0;
        }
    }

    /**
     * One attribute modifier of a background's own effect: {@code attribute} and {@code id} are registry names,
     * {@code operation} the name of vanilla's operation ({@code ADD_VALUE}, {@code ADD_MULTIPLIED_BASE},
     * {@code ADD_MULTIPLIED_TOTAL}).
     */
    public record Modifier(String attribute, String id, double amount, String operation) {}

    /**
     * An effect only a background knows (its own mod registers it), as the primary shows and feels it: its name in the
     * player's language, its category ({@code beneficial}, {@code harmful}, {@code neutral}) and color, its icon as
     * RGBA rows (top row first, base64; null until the background has read it) and its attribute modifiers.
     */
    public record Own(String id, String name, int amplifier, int duration, boolean ambient, boolean visible, boolean icon,
                      String category, int color, int iconWidth, int iconHeight, String iconPixels, List<Modifier> modifiers) {
        public Own { modifiers = modifiers == null ? List.of() : List.copyOf(modifiers); }
        /** Everything but the time left: what decides whether the primary must hear of it again. */
        public Own timeless() { return new Own(id, name, amplifier, 0, ambient, visible, icon, category, color, iconWidth, iconHeight, iconPixels, modifiers); }
    }

    /** All of one background's own effects on its copy of the player (an empty list: none). */
    public record OwnEffects(List<Own> effects) {
        public OwnEffects { effects = effects == null ? List.of() : List.copyOf(effects); }
    }

    /** Cures the primary sends when its player loses its effects: all of them (death, or vanilla's removal of all). */
    public static final String CURE_ALL = "all", CURE_DEATH = "death";

    /**
     * Whether cure {@code cure} (one of the above, or a NeoForge cure name) removes every effect where effects carry
     * no cures of their own (vanilla, Fabric): death, removal of all, milk and the totem do; others (honey) do not.
     */
    public static boolean curesAll(String cure) {
        return CURE_ALL.equals(cure) || CURE_DEATH.equals(cure) || "milk".equals(cure) || "protected_by_totem".equals(cure);
    }

    /**
     * Damage the primary works out from its own world as well (it has a stand-in, or the same vanilla block, where the
     * background's world has one) or from the player's own state: a background's copy taking it is not forwarded.
     */
    public static final Set<String> ENVIRONMENT = Set.of(
        "minecraft:in_fire", "minecraft:campfire", "minecraft:on_fire", "minecraft:lava", "minecraft:hot_floor",
        "minecraft:in_wall", "minecraft:cramming", "minecraft:drown", "minecraft:starve", "minecraft:cactus",
        "minecraft:fall", "minecraft:fly_into_wall", "minecraft:out_of_world", "minecraft:generic_kill",
        "minecraft:dry_out", "minecraft:sweet_berry_bush", "minecraft:freeze", "minecraft:stalagmite",
        "minecraft:outside_border", "minecraft:lightning_bolt", "minecraft:falling_block", "minecraft:falling_anvil",
        "minecraft:falling_stalactite");

    /** Whether damage of {@code type} (a damage type's registry name) a background's copy took goes to the primary. */
    public static boolean forwardsDamage(String type) { return type == null || !ENVIRONMENT.contains(type); }

    private static final float EPSILON = 1e-4f;

    /**
     * The change a background's tick made to its copy of the player.
     *
     * @param before      the copy as set from the primary's state at the tick's start (or as left by the last diff)
     * @param after       the copy at the tick's end
     * @param peaceful    the world is peaceful: the copy's own regeneration and feeding (vanilla's, in peaceful) are not changes
     * @param inPowderSnow the copy stands in powder snow: its freezing is the primary's too
     * @param pushed      the background's logic pushed the copy (vanilla marks it hurt): its velocity change is a push
     * @param shared      which effects the primary holds (registered there); the others are the background's own
     * @return the change, or null when there is none
     */
    public static Change diff(Vitals before, Vitals after, boolean peaceful, boolean inPowderSnow, boolean pushed, Predicate<String> shared) {
        if (before == null || after == null) return null;
        float health = after.health() - before.health();
        if (Math.abs(health) < EPSILON || (health > 0 && peaceful)) health = 0;
        float absorption = after.absorption() - before.absorption();
        if (Math.abs(absorption) < EPSILON) absorption = 0;
        int food = after.food() - before.food();
        if (food > 0 && peaceful) food = 0;
        float saturation = after.saturation() - before.saturation();
        if (Math.abs(saturation) < EPSILON || (saturation > 0 && peaceful)) saturation = 0;
        float exhaustion = Math.max(0, after.exhaustion() - before.exhaustion());
        if (exhaustion < EPSILON) exhaustion = 0;
        int fire = after.fire() > before.fire() && after.fire() > 0 ? after.fire() : 0;
        int frozen = after.frozen() > before.frozen() && !inPowderSnow ? after.frozen() - before.frozen() : 0;
        Map<String, Effect> was = new HashMap<>();
        for (Effect e : before.effects()) was.put(e.id(), e);
        List<Effect> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Effect e : after.effects()) {
            if (!shared.test(e.id())) continue;
            Effect old = was.remove(e.id());
            if (old == null || e.amplifier() != old.amplifier() || longer(e.duration(), old.duration())
                || e.ambient() != old.ambient() || e.visible() != old.visible() || e.icon() != old.icon()) added.add(e);
        }
        for (String id : was.keySet()) if (shared.test(id)) removed.add(id);
        double ix = 0, iy = 0, iz = 0;
        if (pushed) { ix = after.vx() - before.vx(); iy = after.vy() - before.vy(); iz = after.vz() - before.vz(); }
        var change = new Change(health, absorption, food, saturation, exhaustion, fire, frozen, added, removed, ix, iy, iz);
        return change.empty() ? null : change;
    }

    private static boolean longer(int now, int before) {
        if (now == before) return false;
        if (now < 0) return true;
        return before >= 0 && now > before;
    }
}
