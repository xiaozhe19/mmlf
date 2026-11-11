package dev.mmlf.common;

import dev.mmlf.common.OwnershipRules.Action;
import dev.mmlf.common.OwnershipRules.Current;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipRulesTest {
    private static final long GRACE = 3_000_000_000L;

    @Test void aReportedBlockTakesEmptyOrReplaceablePositionsAtOnce() {
        assertEquals(Action.PLACE, OwnershipRules.decide(true, Current.REPLACEABLE, false, true, true, -1, GRACE));
        assertEquals(Action.NONE, OwnershipRules.decide(true, Current.STAND_IN, true, true, false, -1, GRACE));
        assertEquals(Action.UPDATE, OwnershipRules.decide(true, Current.STAND_IN, false, true, false, -1, GRACE));
    }

    @Test void thePrimarysOwnBlockWinsOnceTheMismatchHasSettled() {
        assertEquals(Action.WAIT, OwnershipRules.decide(true, Current.OCCUPIED, false, true, false, -1, GRACE), "first seen: may be an instruction on its way");
        assertEquals(Action.WAIT, OwnershipRules.decide(true, Current.OCCUPIED, false, true, true, GRACE * 10, GRACE), "the chunk's instructions are still waiting");
        assertEquals(Action.REVERT, OwnershipRules.decide(true, Current.OCCUPIED, false, true, false, GRACE, GRACE));
    }

    @Test void aStandInTheOwnerNoLongerReportsIsClearedOnlyOnceItReportedTheChunk() {
        assertEquals(Action.NONE, OwnershipRules.decide(false, Current.STAND_IN, false, false, false, GRACE * 10, GRACE), "owner absent: its blocks stay held");
        assertEquals(Action.WAIT, OwnershipRules.decide(false, Current.STAND_IN, false, true, false, -1, GRACE));
        assertEquals(Action.CLEAR, OwnershipRules.decide(false, Current.STAND_IN, false, true, false, GRACE, GRACE));
        assertEquals(Action.NONE, OwnershipRules.decide(false, Current.OCCUPIED, false, true, false, GRACE, GRACE));
        assertEquals(Action.NONE, OwnershipRules.decide(false, Current.REPLACEABLE, false, true, false, GRACE, GRACE));
    }

    @Test void aBlockTheOwnerRemovedLeavesItsStandInAtOnce() {
        assertEquals(Action.CLEAR, OwnershipRules.decide(false, Current.STAND_IN, false, true, true, -1, GRACE, true), "broken by the player: no hole left behind");
        assertEquals(Action.CLEAR, OwnershipRules.decide(false, Current.STAND_IN, false, false, false, -1, GRACE, true));
        assertEquals(Action.PLACE, OwnershipRules.decide(true, Current.REPLACEABLE, false, true, false, -1, GRACE, true), "reported again: it is there again");
    }

    @Test void standInSpecsRoundTripAndFallBackToTheDefault() {
        var spec = new StandInSpec(false, 7, StandInSpec.WATER, true);
        assertEquals(spec, StandInSpec.parse(spec.encode()));
        assertEquals("0,7,water,1", spec.encode());
        assertEquals(StandInSpec.NONE, new StandInSpec(true, 0, "mod:oil", false).fluid(), "only vanilla water and lava are held");
        assertEquals(15, new StandInSpec(true, 99, StandInSpec.LAVA, false).light());
        assertEquals(StandInSpec.DEFAULT, StandInSpec.parse(null));
        assertEquals(StandInSpec.DEFAULT, StandInSpec.parse("1,x,none,0"));
        assertEquals(StandInSpec.DEFAULT, StandInSpec.parse("1,0"));
    }
}
