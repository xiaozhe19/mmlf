package dev.mmlf.common;

import dev.mmlf.common.Messages.Event;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hands each received message to the one handler registered for its type, or, for {@code event} messages, for its
 * topic. A handler also states which runtimes act on the message ({@link Scope}); a message routed to a handler whose
 * scope does not match is dropped, never passed to another handler. Unknown types are dropped; unknown event topics go
 * to the unrouted-event handler, if any.
 * <p>
 * The router does not change threads: each handler runs on the thread that calls {@link #dispatch}, and moves work to
 * another thread itself. Handlers are registered before the link starts and are not replaced afterwards.
 */
public final class MessageRouter {
    /** Which runtimes act on a message, and from which source. */
    public enum Scope {
        /** Every runtime, whoever sent it. */
        ANY,
        /** Only the primary. */
        ON_PRIMARY,
        /** Only a background, whoever sent it. */
        ON_BACKGROUND,
        /** Only a background, and only what the primary sent. */
        ON_BACKGROUND_FROM_PRIMARY
    }
    /** This runtime's role, read at each dispatch: the session may be joined after the router is made. */
    public interface Roles {
        boolean isPrimary();
        /** The primary's runtime id, or null before a session is joined. */
        String primaryId();
    }
    @FunctionalInterface public interface Handler { void handle(Wire message); }
    @FunctionalInterface public interface EventHandler { void handle(Wire message,Event event); }

    private record Route<H>(Scope scope,H handler) {}
    private record PrefixRoute(String prefix,Route<EventHandler> route) {}

    private final Roles roles;
    private final Map<String,Route<Handler>> types=new ConcurrentHashMap<>();
    private final Map<String,Route<EventHandler>> topics=new ConcurrentHashMap<>();
    private final List<PrefixRoute> prefixes=new CopyOnWriteArrayList<>();
    private volatile EventHandler unrouted;

    public MessageRouter(Roles roles) { this.roles=Objects.requireNonNull(roles); }

    public void on(String type,Scope scope,Handler handler) {
        if("event".equals(type)) throw new IllegalArgumentException("events are routed by topic");
        if(types.putIfAbsent(type,new Route<>(scope,handler))!=null) throw new IllegalStateException("type already routed: "+type);
    }
    public void onEvent(String topic,Scope scope,EventHandler handler) {
        if(topics.putIfAbsent(topic,new Route<>(scope,handler))!=null) throw new IllegalStateException("topic already routed: "+topic);
    }
    /** Topics starting with {@code prefix}, checked after the exact topics, in registration order. */
    public void onEventPrefix(String prefix,Scope scope,EventHandler handler) {
        for(PrefixRoute p:prefixes) if(p.prefix().equals(prefix)) throw new IllegalStateException("prefix already routed: "+prefix);
        prefixes.add(new PrefixRoute(prefix,new Route<>(scope,handler)));
    }
    /** Events no topic or prefix matches (other mods' events); runs for any runtime and source. */
    public void onUnroutedEvent(EventHandler handler) { unrouted=handler; }

    public void dispatch(Wire message) {
        if("event".equals(message.type())) { dispatchEvent(message); return; }
        Route<Handler> route=types.get(message.type());
        if(route!=null && applies(route.scope(),message)) route.handler().handle(message);
    }
    private void dispatchEvent(Wire message) {
        Event event=message.body(Event.class);
        String topic=event==null?null:event.topic();
        if(topic==null) return;
        Route<EventHandler> route=topics.get(topic);
        if(route==null) for(PrefixRoute p:prefixes) if(topic.startsWith(p.prefix())) { route=p.route(); break; }
        if(route!=null) { if(applies(route.scope(),message)) route.handler().handle(message,event); return; }
        EventHandler fallback=unrouted;
        if(fallback!=null) fallback.handle(message,event);
    }
    private boolean applies(Scope scope,Wire message) {
        return switch(scope) {
            case ANY -> true;
            case ON_PRIMARY -> roles.isPrimary();
            case ON_BACKGROUND -> !roles.isPrimary();
            case ON_BACKGROUND_FROM_PRIMARY -> !roles.isPrimary() && message.source()!=null && message.source().equals(roles.primaryId());
        };
    }
}
