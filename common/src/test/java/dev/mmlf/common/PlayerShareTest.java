package dev.mmlf.common;

import dev.mmlf.common.PlayerShare.Effect;
import dev.mmlf.common.PlayerShare.Vitals;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlayerShareTest {
    private static Vitals vitals(float health, int food, float saturation, float exhaustion, int fire, int frozen, List<Effect> effects, double vy) {
        return new Vitals(1, "minecraft:overworld", health, 0, food, saturation, exhaustion, 300, fire, frozen, effects, 0, vy, 0, 0, false, false, false);
    }
    private static final Effect SPEED = new Effect("minecraft:speed", 0, 200, false, true, true);

    @Test void anUnchangedCopyIsNoChange() {
        var v = vitals(20, 20, 5, 0, 0, 0, List.of(SPEED), 0);
        assertNull(PlayerShare.diff(v, v, false, false, false, id -> true));
        assertNull(PlayerShare.diff(null, v, false, false, false, id -> true), "nothing set yet: nothing to compare");
    }

    @Test void healthFoodAndExhaustionAreSentAsAmounts() {
        var c = PlayerShare.diff(vitals(10, 10, 2, 0.5f, 0, 0, List.of(), 0), vitals(14, 13, 3, 0.6f, 0, 0, List.of(), 0), false, false, false, id -> true);
        assertEquals(4, c.health(), 1e-4);
        assertEquals(3, c.food());
        assertEquals(1, c.saturation(), 1e-4);
        assertEquals(0.1f, c.exhaustion(), 1e-4);
    }

    @Test void peacefulRegenerationAndFeedingStayTheBackgroundsOwn() {
        var c = PlayerShare.diff(vitals(10, 10, 2, 0, 0, 0, List.of(), 0), vitals(11, 11, 2, 0, 0, 0, List.of(), 0), true, false, false, id -> true);
        assertNull(c);
        var hurt = PlayerShare.diff(vitals(10, 10, 2, 0, 0, 0, List.of(), 0), vitals(8, 10, 2, 0, 0, 0, List.of(), 0), true, false, false, id -> true);
        assertEquals(-2, hurt.health(), 1e-4, "a loss is the background logic's even in peaceful");
    }

    @Test void fireOnlyGrowsAndFreezingInPowderSnowIsThePrimarys() {
        assertNull(PlayerShare.diff(vitals(20, 20, 5, 0, 40, 0, List.of(), 0), vitals(20, 20, 5, 0, 39, 0, List.of(), 0), false, false, false, id -> true), "burning down is not a change");
        assertEquals(160, PlayerShare.diff(vitals(20, 20, 5, 0, 40, 0, List.of(), 0), vitals(20, 20, 5, 0, 160, 0, List.of(), 0), false, false, false, id -> true).fire());
        assertNull(PlayerShare.diff(vitals(20, 20, 5, 0, 0, 3, List.of(), 0), vitals(20, 20, 5, 0, 0, 4, List.of(), 0), false, true, false, id -> true));
        assertEquals(40, PlayerShare.diff(vitals(20, 20, 5, 0, 0, 3, List.of(), 0), vitals(20, 20, 5, 0, 0, 43, List.of(), 0), false, false, false, id -> true).frozen());
    }

    @Test void effectsAreAddedWhenNewStrongerOrLongerAndRemovedWhenGone() {
        var none = vitals(20, 20, 5, 0, 0, 0, List.of(), 0);
        var speed = vitals(20, 20, 5, 0, 0, 0, List.of(SPEED), 0);
        assertEquals(List.of(SPEED), PlayerShare.diff(none, speed, false, false, false, id -> true).added());
        assertEquals(List.of("minecraft:speed"), PlayerShare.diff(speed, none, false, false, false, id -> true).removed());
        var stronger = new Effect("minecraft:speed", 1, 200, false, true, true);
        assertEquals(List.of(stronger), PlayerShare.diff(speed, vitals(20, 20, 5, 0, 0, 0, List.of(stronger), 0), false, false, false, id -> true).added());
        var shorter = new Effect("minecraft:speed", 0, 100, false, true, true);
        assertNull(PlayerShare.diff(speed, vitals(20, 20, 5, 0, 0, 0, List.of(shorter), 0), false, false, false, id -> true));
        var infinite = new Effect("minecraft:speed", 0, -1, false, true, true);
        assertEquals(List.of(infinite), PlayerShare.diff(speed, vitals(20, 20, 5, 0, 0, 0, List.of(infinite), 0), false, false, false, id -> true).added());
    }

    @Test void theBackgroundsOwnEffectsAreNotSent() {
        var mod = new Effect("othermod:glow", 0, 100, false, true, true);
        var c = PlayerShare.diff(vitals(20, 20, 5, 0, 0, 0, List.of(), 0), vitals(20, 20, 5, 0, 0, 0, List.of(mod), 0), false, false, false, id -> id.startsWith("minecraft:"));
        assertNull(c);
    }

    @Test void aVelocityChangeIsAPushOnlyWhenMarked() {
        assertNull(PlayerShare.diff(vitals(20, 20, 5, 0, 0, 0, List.of(), 0.4), vitals(20, 20, 5, 0, 0, 0, List.of(), 0.32), false, false, false, id -> true));
        assertEquals(0.6, PlayerShare.diff(vitals(20, 20, 5, 0, 0, 0, List.of(), 0), vitals(20, 20, 5, 0, 0, 0, List.of(), 0.6), false, false, true, id -> true).iy(), 1e-9);
    }

    @Test void environmentalDamageIsThePrimarysOwn() {
        assertFalse(PlayerShare.forwardsDamage("minecraft:fall"));
        assertFalse(PlayerShare.forwardsDamage("minecraft:on_fire"));
        assertTrue(PlayerShare.forwardsDamage("minecraft:magic"));
        assertTrue(PlayerShare.forwardsDamage("othermod:spikes"));
    }

    @Test void vitalsRoundTripThroughTheWire() {
        var v = vitals(12.5f, 17, 3, 1.5f, 20, 0, List.of(SPEED), -0.08);
        assertEquals(v, Wire.JSON.fromJson(Wire.JSON.toJson(v), Vitals.class));
        var c = new PlayerShare.Change(-2, 0, 1, 0, 0.1f, 0, 0, List.of(SPEED), List.of("minecraft:poison"), 0, 0.5, 0);
        assertEquals(c, Wire.JSON.fromJson(Wire.JSON.toJson(c), PlayerShare.Change.class));
    }

    @Test void ownEffectsRoundTripAndCompareWithoutTheirTimeLeft() {
        var own = new PlayerShare.Own("othermod:haste", "Haste", 1, 400, false, true, true, "beneficial", 0xFFAA00, 2, 1, "AAAAAAAAAAA=",
            List.of(new PlayerShare.Modifier("minecraft:generic.movement_speed", "othermod:haste", 0.2, "ADD_MULTIPLIED_TOTAL")));
        var all = new PlayerShare.OwnEffects(List.of(own));
        assertEquals(all, Wire.JSON.fromJson(Wire.JSON.toJson(all), PlayerShare.OwnEffects.class));
        var later = new PlayerShare.Own("othermod:haste", "Haste", 1, 380, false, true, true, "beneficial", 0xFFAA00, 2, 1, "AAAAAAAAAAA=", own.modifiers());
        assertNotEquals(own, later);
        assertEquals(own.timeless(), later.timeless(), "counting down is not news for the primary");
        assertEquals(List.of(), new PlayerShare.OwnEffects(null).effects());
    }

    @Test void onlyCuresOfEverythingClearEffectsWithoutCuresOfTheirOwn() {
        assertTrue(PlayerShare.curesAll(PlayerShare.CURE_DEATH));
        assertTrue(PlayerShare.curesAll(PlayerShare.CURE_ALL));
        assertTrue(PlayerShare.curesAll("milk"));
        assertTrue(PlayerShare.curesAll("protected_by_totem"));
        assertFalse(PlayerShare.curesAll("honey"), "honey only takes poison, which is the primary's");
    }
}
