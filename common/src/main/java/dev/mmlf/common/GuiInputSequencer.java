package dev.mmlf.common;

import dev.mmlf.common.Messages.GuiInput;
import java.util.*;

/**
 * Ordering and release bookkeeping for hosted-screen input. Button and key edges are never dropped or merged; pointer
 * movement is coalesced to the newest position and always flushed before the next edge, so press, drag and release keep
 * their order. Both ends remember what is held, so a lost focus or a closed screen releases it instead of leaving a drag.
 */
public final class GuiInputSequencer {
    public static final String MOVE="move", PRESS="press", RELEASE="release", SCROLL="scroll", KEY_DOWN="key_down",
        KEY_UP="key_up", KEY_REPEAT="key_repeat", CHAR="char", RESIZE="resize", RELEASE_ALL="release_all", CLOSE="close";
    private static final Set<String> KINDS=Set.of(MOVE,PRESS,RELEASE,SCROLL,KEY_DOWN,KEY_UP,KEY_REPEAT,CHAR,RESIZE,RELEASE_ALL,CLOSE);
    public static boolean validKind(String kind) { return kind!=null && KINDS.contains(kind); }

    /** Primary side: numbers the events of one instance. */
    public static final class Sender {
        private String instance; private long next;
        private final Held held=new Held();
        private GuiInput pendingMove;
        private int guiScale, width, height;
        public synchronized void begin(String instanceId) { instance=instanceId; next=0; held.clear(); pendingMove=null; }
        public synchronized String instance() { return instance; }
        public synchronized void window(int scale,int w,int h) { guiScale=scale; width=w; height=h; }
        /** Movement is held back until {@link #flush} or the next edge. */
        public synchronized void move(double x,double y,long now) { if(instance!=null) pendingMove=make(MOVE,x,y,-1,0,0,-1,-1,0,null,now,false); }
        public synchronized List<GuiInput> flush() {
            if(pendingMove==null) return List.of();
            GuiInput move=renumber(pendingMove); pendingMove=null; return List.of(move);
        }
        /** Returns the events to send in order: any pending movement first, then this edge. */
        public synchronized List<GuiInput> edge(String kind,double x,double y,int button,double scrollX,double scrollY,int key,int scancode,int modifiers,String text,long now) {
            if(instance==null || !validKind(kind) || MOVE.equals(kind)) return List.of();
            if(PRESS.equals(kind) && !held.buttons.add(button)) return List.of();
            if(RELEASE.equals(kind) && !held.buttons.remove(button)) return List.of();
            if(KEY_DOWN.equals(kind)) held.keys.put(key,scancode);
            if(KEY_UP.equals(kind) && held.keys.remove(key)==null) return List.of();
            List<GuiInput> out=new ArrayList<>(flush());
            out.add(make(kind,x,y,button,scrollX,scrollY,key,scancode,modifiers,text,now,true));
            return out;
        }
        /** Releases everything held, e.g. when the primary window loses focus. */
        public synchronized List<GuiInput> releaseAll(double x,double y,long now) {
            if(instance==null) return List.of();
            List<GuiInput> out=new ArrayList<>(flush());
            for(int button:held.buttons) out.add(make(RELEASE,x,y,button,0,0,-1,-1,0,null,now,true));
            for(var key:held.keys.entrySet()) out.add(make(KEY_UP,x,y,-1,0,0,key.getKey(),key.getValue(),0,null,now,true));
            held.clear();
            out.add(make(RELEASE_ALL,x,y,-1,0,0,-1,-1,0,null,now,true));
            return out;
        }
        public synchronized boolean holding() { return !held.buttons.isEmpty() || !held.keys.isEmpty(); }
        public synchronized boolean heldKey(int key) { return held.keys.containsKey(key); }
        private GuiInput make(String kind,double x,double y,int button,double sx,double sy,int key,int scancode,int modifiers,String text,long now,boolean number) {
            return new GuiInput(instance,number?++next:0,kind,clamp(x),clamp(y),button,sx,sy,key,scancode,modifiers,text,guiScale,width,height,now);
        }
        private GuiInput renumber(GuiInput m) {
            return new GuiInput(m.instanceId(),++next,m.kind(),m.x(),m.y(),m.button(),m.scrollX(),m.scrollY(),m.key(),m.scancode(),m.modifiers(),m.text(),m.guiScale(),m.width(),m.height(),m.sentNanos());
        }
    }

    /** Owner side: accepts each event of the current instance once and in order. */
    public static final class Receiver {
        public enum Verdict { ACCEPT, DUPLICATE, WRONG_INSTANCE, INVALID }
        private String instance; private long last; private long gaps;
        private final Held held=new Held();
        public synchronized void begin(String instanceId) { instance=instanceId; last=0; held.clear(); }
        public synchronized String instance() { return instance; }
        public synchronized boolean holds(int key) { return held.keys.containsKey(key); }
        /** Sequence numbers skipped so far; the ordered transport should keep this at zero. */
        public synchronized long gaps() { return gaps; }
        public synchronized Verdict accept(GuiInput input) {
            if(input==null || !validKind(input.kind()) || !Double.isFinite(input.x()) || !Double.isFinite(input.y())
                || !Double.isFinite(input.scrollX()) || !Double.isFinite(input.scrollY())) return Verdict.INVALID;
            if(instance==null || !instance.equals(input.instanceId())) return Verdict.WRONG_INSTANCE;
            if(input.sequence()<=last) return Verdict.DUPLICATE;
            if(input.sequence()>last+1) gaps+=input.sequence()-last-1;
            last=input.sequence();
            switch(input.kind()) {
                case PRESS -> held.buttons.add(input.button());
                case RELEASE -> held.buttons.remove(input.button());
                case KEY_DOWN -> held.keys.put(input.key(),input.scancode());
                case KEY_UP -> held.keys.remove(input.key());
                case RELEASE_ALL -> held.clear();
                default -> { }
            }
            return Verdict.ACCEPT;
        }
        /** Buttons still held, which a closing screen must release; clears them and the held keys. */
        public synchronized List<Integer> drainButtons() {
            List<Integer> out=new ArrayList<>(held.buttons); held.clear(); return out;
        }
        /** Keys still held as {key, scancode}; call before {@link #drainButtons}. */
        public synchronized List<int[]> heldKeys() {
            List<int[]> out=new ArrayList<>();
            for(var k:held.keys.entrySet()) out.add(new int[]{k.getKey(),k.getValue()});
            return out;
        }
    }

    private static final class Held {
        final Set<Integer> buttons=new LinkedHashSet<>(); final Map<Integer,Integer> keys=new LinkedHashMap<>();
        void clear() { buttons.clear(); keys.clear(); }
    }
    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(0,Math.min(1,v)) : 0; }
    private GuiInputSequencer() {}
}
