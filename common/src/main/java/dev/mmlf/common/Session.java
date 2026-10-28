package dev.mmlf.common;

import java.nio.file.*;
import java.io.*;
import java.net.URI;

/** One fusion session. The primary runtime (main window and input) is chosen per session, never per loader. */
public record Session(String uri, String token, String directory, String primary) {
    public static Session read(Path path) throws IOException {
        Session session = Wire.JSON.fromJson(Files.readString(path), Session.class);
        URI uri = URI.create(session.uri);
        if (!"ws".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1)
            throw new IOException("MMLF requires a loopback WebSocket endpoint");
        if (session.primary == null || !session.primary.matches("[a-zA-Z0-9_-]{1,48}"))
            throw new IOException("MMLF session does not name a primary runtime");
        return session;
    }
    public Path root() { return Path.of(directory).toAbsolutePath().normalize(); }
    public Path framePath(String id) {
        if (!id.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("invalid runtime id");
        return root().resolve(id + ".frames");
    }
    public void write(Path path) throws IOException { Files.writeString(path, Wire.JSON.toJson(this)); }
}
