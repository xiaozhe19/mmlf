package dev.mmlf.launcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class ModClassifierTest {
 @TempDir Path temp;
 @Test void detectsLoadersAndRefusesAmbiguousFiles() throws Exception {
  Path jar=temp.resolve("one.jar");
  try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(jar))) {
   out.putNextEntry(new ZipEntry("fabric.mod.json")); out.write("{\"id\":\"example\"}".getBytes()); out.closeEntry();
  }
  assertEquals(ModClassifier.Loader.FABRIC,ModClassifier.classify(jar).loader());
  Path mixed=temp.resolve("mixed.jar");
  try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(mixed))) {
   for(String name:new String[]{"fabric.mod.json","META-INF/neoforge.mods.toml"}) {out.putNextEntry(new ZipEntry(name));out.closeEntry();}
  }
  assertEquals(ModClassifier.Loader.AMBIGUOUS,ModClassifier.classify(mixed).loader());
 }
}
