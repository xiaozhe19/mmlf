package dev.mmlf.bridge.render;

import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.RenderShaderRegistry;
import java.util.*;
import java.util.function.Supplier;

/** Reports each runtime's shader availability for diagnostics; the window owner always renders the scene. */
public final class RenderShaderCapabilities {
    private record Report(RenderShaderRegistry.State state,long receivedNanos) {}
    private static final RenderShaderRegistry registry=new RenderShaderRegistry();
    private static final Map<String,Report> reports=new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean initialized;
    private static long lastSend;
    private static String lastStates="";
    private RenderShaderCapabilities() {}

    /** Other rendering backends can register their own shader state without changing the coordinator. */
    public static void register(String id,Supplier<RenderShaderRegistry.State> probe) { registry.register(id,probe); }

    private static void discover() {
        if(initialized) return; initialized=true;
        try {
            Class<?> api=Class.forName("net.irisshaders.iris.api.v0.IrisApi",false,RenderShaderCapabilities.class.getClassLoader());
            var instance=api.getMethod("getInstance"); var active=api.getMethod("isShaderPackInUse");
            register("iris-api",()->{
                try { return Boolean.TRUE.equals(active.invoke(instance.invoke(null))) ? RenderShaderRegistry.State.ACTIVE : RenderShaderRegistry.State.INACTIVE; }
                catch(ReflectiveOperationException | LinkageError e) { return RenderShaderRegistry.State.UNKNOWN; }
            });
        } catch(ClassNotFoundException ignored) {
            // No registered provider: unknown is safer than assuming all possible shader backends are absent.
        } catch(ReflectiveOperationException | LinkageError e) {
            MmlfLog.warn("render.capability","shader_probe_unavailable",Map.of("reason",e.toString()));
        }
    }
    public static void tick() {
        discover(); long now=System.nanoTime();
        if(now-lastSend<2_000_000_000L) return; lastSend=now;
        var state=registry.probe(); reports.put(BridgeCore.runtimeId(),new Report(state,now));
        var values=new HashMap<String,String>(); values.put("state",state.name());
        if(BridgeCore.isPrimary()) reportStates(now);
        BridgeCore.send("event","*",new Event("mmlf:render_shader_capability",values));
    }
    public static void receive(String source,Map<String,String> values) {
        if(!Set.of("fabric","neoforge").contains(source) || source.equals(BridgeCore.runtimeId()) || values==null) return;
        try { reports.put(source,new Report(RenderShaderRegistry.State.valueOf(values.get("state")),System.nanoTime())); }
        catch(IllegalArgumentException | NullPointerException ignored) { }
    }
    /** Whether a shader pack is active in this runtime, as far as a registered provider can tell. */
    public static boolean localActive() { discover(); return registry.probe()==RenderShaderRegistry.State.ACTIVE; }
    private static void reportStates(long now) {
        var states=new TreeMap<String,String>();
        for(String id:List.of("fabric","neoforge")) {
            Report report=reports.get(id);
            states.put(id,(report==null || now-report.receivedNanos()>6_000_000_000L?RenderShaderRegistry.State.UNKNOWN:report.state()).name());
        }
        String value=states.toString();
        if(!value.equals(lastStates)) { lastStates=value; MmlfLog.info("render.capability","shader_states",Map.of("states",states)); }
    }
}
