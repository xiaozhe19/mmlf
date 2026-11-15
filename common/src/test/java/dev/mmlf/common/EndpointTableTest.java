package dev.mmlf.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EndpointTableTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test void aTouchedPositionStaysActiveUntilItHasBeenIdleLongEnough() {
        var table = new EndpointTable(600, 16);
        assertTrue(table.touch(OVERWORLD, 7L, 100));
        assertTrue(table.expire(700).isEmpty(), "exactly the idle time: still open");
        assertEquals(1, table.expire(701).size());
        assertFalse(table.isActive(OVERWORLD, 7L));
    }

    @Test void furtherActivityKeepsItOpen() {
        var table = new EndpointTable(600, 16);
        table.touch(OVERWORLD, 7L, 100);
        table.touch(OVERWORLD, 7L, 650);
        assertTrue(table.expire(1200).isEmpty());
        table.touch(OVERWORLD, 7L, 10);
        assertTrue(table.expire(1250).isEmpty(), "an older tick never moves the last activity back");
        assertEquals(1, table.expire(1251).size());
    }

    @Test void dimensionsAreKeptApart() {
        var table = new EndpointTable(600, 16);
        table.touch(OVERWORLD, 7L, 0);
        assertFalse(table.isActive("minecraft:the_nether", 7L));
        table.touch("minecraft:the_nether", 7L, 0);
        assertEquals(2, table.size());
        table.remove(OVERWORLD, 7L);
        assertTrue(table.isActive("minecraft:the_nether", 7L));
        assertFalse(table.isActive(OVERWORLD, 7L));
    }

    @Test void aFullTableRefusesNewPositionsButKeepsItsOwnOpen() {
        var table = new EndpointTable(600, 2);
        assertTrue(table.touch(OVERWORLD, 1L, 0));
        assertTrue(table.touch(OVERWORLD, 2L, 0));
        assertFalse(table.touch(OVERWORLD, 3L, 0));
        assertEquals(1, table.refused());
        assertTrue(table.touch(OVERWORLD, 1L, 500), "an open position is still kept alive");
        table.expire(601);
        assertEquals(1, table.size(), "position 2 expired, position 1 was renewed");
        assertTrue(table.touch(OVERWORLD, 3L, 601), "room again");
    }

    @Test void activeIsACopy() {
        var table = new EndpointTable(600, 16);
        table.touch(OVERWORLD, 1L, 0);
        table.touch(OVERWORLD, 2L, 0);
        for (var endpoint : table.active()) table.remove(endpoint.dimension(), endpoint.packed());
        assertEquals(0, table.size());
    }

    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EndpointTable(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new EndpointTable(1, 0));
    }
}
