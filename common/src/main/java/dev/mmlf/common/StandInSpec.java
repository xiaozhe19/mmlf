package dev.mmlf.common;

/**
 * What the primary's stand-in for another runtime's block carries in the primary's map (and save): whether it fills its
 * cube for light and occlusion, the light it emits, the vanilla fluid it holds (none, water or lava, as a source) and
 * whether it conducts redstone like a solid block. Encoded as {@code solid,light,fluid,conductor}, e.g. {@code 1,0,none,1}.
 */
public record StandInSpec(boolean solid, int light, String fluid, boolean conductor) {
    public static final String NONE = "none", WATER = "water", LAVA = "lava";
    public StandInSpec {
        light = Math.max(0, Math.min(15, light));
        fluid = WATER.equals(fluid) || LAVA.equals(fluid) ? fluid : NONE;
    }
    /** A solid, dark, dry, non-conducting stand-in: what an unknown block is taken as. */
    public static final StandInSpec DEFAULT = new StandInSpec(true, 0, NONE, false);
    public String encode() { return (solid ? "1" : "0") + "," + light + "," + fluid + "," + (conductor ? "1" : "0"); }
    /** The spec, or {@link #DEFAULT} for null or malformed text. */
    public static StandInSpec parse(String text) {
        if (text == null) return DEFAULT;
        String[] parts = text.split(",", -1);
        if (parts.length != 4) return DEFAULT;
        try { return new StandInSpec("1".equals(parts[0]), Integer.parseInt(parts[1]), parts[2], "1".equals(parts[3])); }
        catch (NumberFormatException e) { return DEFAULT; }
    }
}
