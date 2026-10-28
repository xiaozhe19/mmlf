package dev.mmlf.launcher;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Conservative classifier: ambiguous archives must be assigned by the user. */
public final class ModClassifier {
 public enum Loader { FABRIC, NEOFORGE, FORGE, AMBIGUOUS, UNKNOWN }
 public record Result(Loader loader,String detail) {}
 private ModClassifier() {}
 public static Result classify(Path file) throws IOException {
  try(ZipFile zip=new ZipFile(file.toFile())) {
   boolean fabric=zip.getEntry("fabric.mod.json")!=null;
   boolean neo=zip.getEntry("META-INF/neoforge.mods.toml")!=null;
   boolean forge=zip.getEntry("META-INF/mods.toml")!=null;
   int count=(fabric?1:0)+(neo?1:0)+(forge?1:0);
   if(count>1) return new Result(Loader.AMBIGUOUS,"Multiple loader metadata entries; assign explicitly.");
   if(count==0) return new Result(Loader.UNKNOWN,"No recognized loader metadata.");
   if(fabric) {
    try(InputStream input=zip.getInputStream(zip.getEntry("fabric.mod.json"))) {
     JsonObject metadata=JsonParser.parseReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
     return new Result(Loader.FABRIC,metadata.has("id") ? metadata.get("id").getAsString() : "Missing Fabric mod id");
    } catch(RuntimeException ex) { return new Result(Loader.UNKNOWN,"Invalid fabric.mod.json"); }
   }
   return new Result(neo?Loader.NEOFORGE:Loader.FORGE,"Metadata detected; dependencies and version require loader validation.");
  } catch(ZipException e) { return new Result(Loader.UNKNOWN,"Invalid JAR archive."); }
 }
}
