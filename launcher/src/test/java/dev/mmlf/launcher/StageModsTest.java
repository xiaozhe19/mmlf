package dev.mmlf.launcher;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Development staging: a mod set replaces the previous one, and mods it did not stage are left alone. */
class StageModsTest {
    @TempDir Path dir;
    private static void jar(Path file,String entry) throws Exception {
        Files.createDirectories(file.getParent());
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(entry)); if(entry.equals("fabric.mod.json")) out.write("{\"id\":\"x\"}".getBytes()); out.closeEntry();
        }
    }
    private Set<String> jars(Path d) throws Exception {
        try(var s=Files.list(d)) { return new TreeSet<>(s.map(p->p.getFileName().toString()).filter(n->n.endsWith(".jar")).toList()); }
    }

    @Test void previousSetIsReplaced() throws Exception {
        Path a=dir.resolve("a"), b=dir.resolve("b"), run=dir.resolve("run/mods");
        jar(a.resolve("sodium.jar"),"fabric.mod.json"); jar(a.resolve("carpet.jar"),"fabric.mod.json"); jar(a.resolve("fd.jar"),"META-INF/neoforge.mods.toml");
        jar(b.resolve("lithium.jar"),"fabric.mod.json");
        jar(run.resolve("by-hand.jar"),"fabric.mod.json");
        SessionHost.stageMods(a,ModClassifier.Loader.FABRIC,run);
        assertEquals(Set.of("by-hand.jar","carpet.jar","sodium.jar"),jars(run));
        SessionHost.stageMods(b,ModClassifier.Loader.FABRIC,run);
        assertEquals(Set.of("by-hand.jar","lithium.jar"),jars(run),"the earlier profile's mods are gone, the hand-placed one stays");
    }
}
