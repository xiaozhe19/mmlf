package dev.mmlf.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Co-generation ({@code CoGeneration}): what a background's decoration of one chunk does to the primary's map, as
 * instructions the primary carries out.
 * <ul>
 * <li>{@code S|pos|expected|state}: the decoration left a vanilla block (or air) here; the primary sets it.</li>
 * <li>{@code C|pos|expected|spec}: the decoration left a block of the background here; the primary puts the stand-in
 * for it ({@link StandInSpec}) in place of its own block, so its map records the position as the background's. Over air
 * it is a claim. If the primary's own block there is not the expected one, it rejects the claim and the background
 * removes its block, so the position never holds a block in both worlds. Without {@code spec} (instructions saved by an
 * earlier version) the stand-in is {@link StandInSpec#DEFAULT}.</li>
 * </ul>
 * {@code expected} is the full state name the background saw before its decoration, copied from the primary. An
 * instruction is carried out only while the primary still has exactly that ({@link #decide}): what the primary's own
 * decoration has put there since wins. {@link #FOREIGN} stands for a block of the primary's mods, which the background
 * only saw as a stand-in: never matched. A position already holding a stand-in ({@link #OWNED}) is the background's
 * already: a claim there is done, anything else rejected.
 */
public final class GenerationInstructions {
    private GenerationInstructions() {}
    public static final String FOREIGN="*";
    /** What the primary reports for a position whose map already holds a stand-in. */
    public static final String OWNED="#owned";
    /** Prefix of an outcome that puts a stand-in, followed by its encoded {@link StandInSpec}. */
    public static final String STAND_IN="#stand_in|";

    /**
     * One position's first and last state during a decoration, as full state names, and whether the last is the
     * background's own; {@code standIn} is its stand-in's encoded {@link StandInSpec} when it is.
     */
    public record Write(long pos,String before,String after,boolean ownAfter,String standIn) {
        public Write(long pos,String before,String after,boolean ownAfter) { this(pos,before,after,ownAfter,null); }
    }

    /** The instructions for a decoration's writes, in the order the positions were first written. Unchanged positions give none. */
    public static List<String> build(List<Write> writes) {
        List<String> out=new ArrayList<>();
        for(Write w:writes) {
            if(w.before().equals(w.after())) continue;
            if(w.ownAfter()) out.add("C|"+w.pos()+"|"+w.before()+"|"+(w.standIn()==null?StandInSpec.DEFAULT.encode():w.standIn()));
            else out.add("S|"+w.pos()+"|"+w.before()+"|"+w.after());
        }
        return out;
    }

    /** A parsed instruction; {@code state} is the stand-in spec for a claim (null when the instruction had none). */
    public record Instruction(boolean clear,long pos,String expected,String state) {}
    public static Instruction parse(String text) {
        if(text==null) return null;
        String[] parts=text.split("\\|",4);
        try {
            if(parts[0].equals("C") && (parts.length==3 || parts.length==4)) return new Instruction(true,Long.parseLong(parts[1]),parts[2],parts.length==4?parts[3]:null);
            if(parts.length==4 && parts[0].equals("S")) return new Instruction(false,Long.parseLong(parts[1]),parts[2],parts[3]);
        } catch(NumberFormatException ignored) { }
        return null;
    }

    /** What the primary does with a list of instructions, given the state name it has at each position now. */
    public record Outcome(Map<Long,String> apply,List<Long> rejectedClears,int rejected) {}
    /**
     * Instructions are carried out where the primary still has the expected state; a later instruction for the same
     * position replaces an earlier one. A claim puts {@link #STAND_IN} and its spec. Rejected claims are reported back,
     * so the background removes its block there.
     */
    public static Outcome decide(List<String> instructions,LongFunction<String> current) {
        Map<Long,String> apply=new LinkedHashMap<>(); List<Long> rejectedClears=new ArrayList<>(); int rejected=0;
        for(String text:instructions) {
            Instruction i=parse(text); if(i==null) { rejected++; continue; }
            String now=apply.containsKey(i.pos()) ? apply.get(i.pos()) : current.apply(i.pos());
            if(i.clear() && (OWNED.equals(now) || now!=null && now.startsWith(STAND_IN))) continue;
            boolean matches=!FOREIGN.equals(i.expected()) && i.expected().equals(now);
            if(!matches) { rejected++; if(i.clear()) rejectedClears.add(i.pos()); continue; }
            apply.put(i.pos(),i.clear() ? STAND_IN+StandInSpec.parse(i.state()).encode() : i.state());
        }
        return new Outcome(apply,rejectedClears,rejected);
    }
    public static final String AIR="minecraft:air";
}
