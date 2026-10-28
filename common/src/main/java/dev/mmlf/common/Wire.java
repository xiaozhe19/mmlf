package dev.mmlf.common;

import com.google.gson.*;

/** Versioned, loader-neutral control envelope. Binary pixels never travel in JSON. */
public record Wire(int version, String type, String source, String target, String id, JsonObject data) {
    public static final int VERSION = 1;
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public static Wire of(String type, String source, String target, Object data) {
        return new Wire(VERSION, type, source, target, java.util.UUID.randomUUID().toString(),
            data instanceof JsonObject j ? j : JSON.toJsonTree(data).getAsJsonObject());
    }
    public static Wire parse(String text) {
        if (text.length() > 1_048_576) throw new IllegalArgumentException("control message too large");
        Wire w = JSON.fromJson(text, Wire.class);
        if (w == null || w.version != VERSION || w.type == null || w.source == null || w.id == null || w.data == null)
            throw new IllegalArgumentException("invalid envelope or incompatible protocol");
        return w;
    }
    public String encode() { return JSON.toJson(this); }
    public <T> T body(Class<T> type) { return JSON.fromJson(data, type); }
}
