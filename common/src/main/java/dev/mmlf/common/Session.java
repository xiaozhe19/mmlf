package dev.mmlf.common;

import java.nio.file.*;
import java.io.*;

/** One fusion session. The primary runtime (main window and input) is chosen per session, never per loader. */
public record Session(String uri, String token, String directory, String primary) {
    /** Endpoint of a Coordinator serving runtimes in this JVM through {@link InJvm}. */
    public static final String IN_JVM = "injvm://local";
    public boolean inJvm() { return IN_JVM.equals(uri); }
    public static Session read(Path path) throws IOException {
        Session session = Wire.JSON.fromJson(Files.readString(path), Session.class);
        if (!IN_JVM.equals(session.uri))
            throw new IOException("MMLF requires the in-JVM endpoint");
        if (session.primary == null || !session.primary.matches("[a-zA-Z0-9_-]{1,48}"))
            throw new IOException("MMLF session does not name a primary runtime");
        return session;
    }
    public String identity() {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public Path root() { return Path.of(directory).toAbsolutePath().normalize(); }
    public Path posePath() { return root().resolve("camera.pose"); }
    public Path framePath(String id) {
        if (!id.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("invalid runtime id");
        return root().resolve(id + ".frames");
    }
    public void write(Path path) throws IOException { Files.writeString(path, Wire.JSON.toJson(this)); }
}
