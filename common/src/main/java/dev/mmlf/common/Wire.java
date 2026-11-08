package dev.mmlf.common;

import com.google.gson.*;

/**
 * Versioned, loader-neutral control envelope. Binary pixels never travel in JSON. {@code delivery} is set only on
 * reliable runtime-to-runtime messages ({@link ReliableLink}); everything else is a snapshot or a request whose loss
 * the sender detects itself.
 */
public record Wire(int version, String type, String source, String target, String id, JsonObject data, ReliableLink.Delivery delivery) {
    public static final int VERSION = 2;
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public static Wire of(String type, String source, String target, Object data) {
        return new Wire(VERSION, type, source, target, java.util.UUID.randomUUID().toString(),
            data instanceof JsonObject j ? j : JSON.toJsonTree(data).getAsJsonObject(), null);
    }
    public static Wire parse(String text) {
        if (text.length() > 1_048_576) throw new IllegalArgumentException("control message too large");
        Wire w = JSON.fromJson(text, Wire.class);
        if (w == null || w.version != VERSION || w.type == null || w.source == null || w.id == null || w.data == null)
            throw new IllegalArgumentException("invalid envelope or incompatible protocol");
        return w;
    }
    public Wire withDelivery(ReliableLink.Delivery value) { return new Wire(version, type, source, target, id, data, value); }
    public String encode() { return JSON.toJson(this); }
    public <T> T body(Class<T> type) { return JSON.fromJson(data, type); }
}
