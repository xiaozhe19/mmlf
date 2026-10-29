package dev.mmlf.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Authoritative per-runtime JSONL log. Levels are independent of the host game's logger filters. */
public final class MmlfLog {
 public enum Level { DEBUG, INFO, WARN, ERROR }
 private static Level threshold=Level.DEBUG;
 private static String runtime="unconfigured";
 private static BufferedWriter output;
 private static final Map<String,Long> samples=new HashMap<>();
 private MmlfLog() {}
 public static synchronized void configure(String id,Path directory,String level) throws IOException {
  if(output!=null) output.close();
  runtime=id; threshold=Level.valueOf(level.toUpperCase(Locale.ROOT));
  Files.createDirectories(directory.resolve("logs"));
  output=Files.newBufferedWriter(directory.resolve("logs").resolve(id+".jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
  samples.clear();
  info("logging","configured",Map.of("level",threshold.name(),"file",id+".jsonl"));
 }
 /** INFO unless asked otherwise: release jars never log at DEBUG by themselves; development runs ask for it. */
 public static String configuredLevel() {return System.getProperty("mmlf.logLevel",System.getenv().getOrDefault("MMLF_LOG_LEVEL","INFO"));}
 public static void debug(String module,String event,Map<String,?> fields) { write(Level.DEBUG,module,event,fields); }
 public static void info(String module,String event,Map<String,?> fields) { write(Level.INFO,module,event,fields); }
 public static void warn(String module,String event,Map<String,?> fields) { write(Level.WARN,module,event,fields); }
 public static void error(String module,String event,Map<String,?> fields) { write(Level.ERROR,module,event,fields); }
 public static synchronized void sampled(String module,String event,long intervalMillis,Map<String,?> fields) {
  String key=module+":"+event;long now=System.nanoTime();
  Long previous=samples.get(key); if(previous!=null && now-previous<intervalMillis*1_000_000L) return;
  samples.put(key,now);debug(module,event,fields);
 }
 public static void wire(String direction,Wire wire) {
  Map<String,Object> fields=new LinkedHashMap<>();
  fields.put("type",wire.type()); fields.put("messageId",wire.id()); fields.put("source",wire.source()); fields.put("target",wire.target());
  for(String key:List.of("actionId","cameraId","frameId","slot","revision","action","topic","objectId"))
   if(wire.data().has(key)) fields.put(key,wire.data().get(key));
  if(wire.type().equals("event") && wire.data().has("topic") && wire.data().get("topic").getAsString().equals("mmlf:visual")) sampled("protocol",direction+"_visual",1000,fields);
  else if(Set.of("pose","frame","ping","pong").contains(wire.type())) sampled("protocol",direction+"_"+wire.type(),1000,fields);
  else debug("protocol",direction,fields);
 }
 private static synchronized void write(Level level,String module,String event,Map<String,?> fields) {
  if(level.ordinal()<threshold.ordinal()) return;
  Map<String,Object> record=new LinkedHashMap<>();
  record.put("time",Instant.now().toString()); record.put("level",level.name());record.put("runtime",runtime);
  record.put("module",module);record.put("event",event);record.put("thread",Thread.currentThread().getName());
  Map<String,Object> safe=new LinkedHashMap<>();
  fields.forEach((key,value)->safe.put(key,key.toLowerCase(Locale.ROOT).matches(".*(token|password|secret|credential|authorization).*" )?"[REDACTED]":value));
  record.put("fields",safe);
  String line=Wire.JSON.toJson(record);
  if(output!=null) try {output.write(line);output.newLine();output.flush();} catch(IOException e) {System.err.println("MMLF log write failed: "+e.getClass().getSimpleName());}
  System.out.println("[MMLF "+level+" "+runtime+" "+module+"] "+event+" "+Wire.JSON.toJson(safe));
 }
 public static synchronized void close() {if(output!=null) try {output.close();} catch(IOException ignored) {} finally {output=null;} }
}
