package dev.mmlf.bridge;

import dev.mmlf.common.FrameFile;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Session;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Opens the session's frame channels (world, hand, icon, GUI, catalog atlases): memory-mapped frame files, or frames
 * shared in memory ({@link FrameFile#memoryWriter}), chosen with {@code -Dmmlf.frameChannel} (or
 * {@code MMLF_FRAME_CHANNEL}): {@code file} (default) or {@code direct}. Both ends read the same JVM-wide setting, so
 * writer and reader always agree.
 */
public final class Frames {
    private Frames() {}
    public enum Mode { FILE, DIRECT }

    public static Mode mode(Session session) {
        String value=System.getProperty("mmlf.frameChannel",System.getenv().getOrDefault("MMLF_FRAME_CHANNEL","file")).toLowerCase(Locale.ROOT);
        return value.equals("direct")?Mode.DIRECT:Mode.FILE;
    }
    public static FrameFile writer(String name) throws IOException { return writer(name,FrameFile.MAX_PIXELS); }
    public static FrameFile writer(String name,int maxPixels) throws IOException {
        Session session=BridgeCore.session();
        FrameFile file=mode(session)==Mode.DIRECT
            ? FrameFile.memoryWriter(FrameFile.memoryKey(session.identity(),name),maxPixels)
            : FrameFile.writer(session.framePath(name),maxPixels);
        MmlfLog.info("render.transfer","frame_channel_opened",Map.of("name",name,"role","writer","mode",mode(session).name()));
        return file;
    }
    public static FrameFile reader(String name) throws IOException { return reader(name,FrameFile.MAX_PIXELS); }
    public static FrameFile reader(String name,int maxPixels) throws IOException {
        Session session=BridgeCore.session();
        return mode(session)==Mode.DIRECT
            ? FrameFile.memoryReader(FrameFile.memoryKey(session.identity(),name),maxPixels)
            : FrameFile.reader(session.framePath(name),maxPixels);
    }
}
