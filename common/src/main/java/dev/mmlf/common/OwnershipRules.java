package dev.mmlf.common;

/**
 * Who a position of the primary's map belongs to. The primary's map is the one record: a stand-in there means the
 * position is another runtime's, which holds the actual block in its own world; anything else is the primary's.
 * The primary checks each position against what the owner reports for that chunk ({@link #decide}), so a lost or late
 * message is corrected the next time the chunk is checked, and a position never holds a block in both worlds for long.
 * Where both have one, the primary's wins.
 */
public final class OwnershipRules {
    private OwnershipRules() {}
    public static Action decide(boolean reported, Current current, boolean sameStandIn, boolean chunkReported,
                                boolean instructionsWaiting, long mismatchNanos, long graceNanos) {
        return decide(reported, current, sameStandIn, chunkReported, instructionsWaiting, mismatchNanos, graceNanos, false);
    }
    /** What the primary's map holds at the position. */
    public enum Current { STAND_IN, REPLACEABLE, OCCUPIED }
    public enum Action {
        /** Nothing to do. */ NONE,
        /** Put the reported block's stand-in there (it was empty or replaceable). */ PLACE,
        /** The stand-in is there but carries other properties: set the reported ones. */ UPDATE,
        /** A mismatch that may be settling (instructions of the chunk still waiting, or seen only just now): look again later. */ WAIT,
        /** The primary has its own block where the owner reports one: the owner removes its block. */ REVERT,
        /** A stand-in the owner no longer reports: the position is the primary's again. */ CLEAR
    }

    /**
     * @param reported            whether the owner reports one of its blocks at the position
     * @param current             what the primary's map holds there
     * @param sameStandIn         for a stand-in: whether it already carries the reported properties
     * @param chunkReported       whether the owner has reported this chunk since it connected (else silence means nothing)
     * @param instructionsWaiting whether generation instructions for this chunk are still to be carried out here
     * @param mismatchNanos       how long this mismatch has been seen, or a negative value when first seen now
     * @param graceNanos          how long a mismatch may last before it is resolved
     * @param released            whether the owner reported a block here before and has since removed it: its stand-in goes at once
     */
    public static Action decide(boolean reported, Current current, boolean sameStandIn, boolean chunkReported,
                                boolean instructionsWaiting, long mismatchNanos, long graceNanos, boolean released) {
        boolean settled = !instructionsWaiting && mismatchNanos >= graceNanos;
        if (!reported && current == Current.STAND_IN && released) return Action.CLEAR;
        if (reported) return switch (current) {
            case STAND_IN -> sameStandIn ? Action.NONE : Action.UPDATE;
            case REPLACEABLE -> Action.PLACE;
            case OCCUPIED -> settled ? Action.REVERT : Action.WAIT;
        };
        if (current != Current.STAND_IN || !chunkReported) return Action.NONE;
        return settled ? Action.CLEAR : Action.WAIT;
    }
}
