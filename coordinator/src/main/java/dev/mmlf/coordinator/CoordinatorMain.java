package dev.mmlf.coordinator;

import dev.mmlf.common.Session;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class CoordinatorMain {
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args.length>0?args[0]:"runtime/session").toAbsolutePath();
        Files.createDirectories(dir);
        dev.mmlf.common.MmlfLog.configure("coordinator",dir,dev.mmlf.common.MmlfLog.configuredLevel());
        byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
        String token=HexFormat.of().formatHex(secret);
        Coordinator server=new Coordinator(0,token);
        server.start();
        while(server.getPort()==0) Thread.sleep(20);
        new Session("ws://127.0.0.1:"+server.getPort(),token,dir.toString(),args.length>1?args[1]:"fabric").write(dir.resolve("session.json"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.stop(); } catch(Exception ignored) {} }));
    }
}
