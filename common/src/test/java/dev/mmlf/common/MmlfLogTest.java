package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class MmlfLogTest {
 @TempDir Path temp;
 @Test void levelsAndRedactionArePersisted() throws Exception {
  try {
   MmlfLog.configure("fabric",temp,"INFO");
   MmlfLog.debug("input","hidden",Map.of());
   MmlfLog.info("input","visible",Map.of("token","do-not-store","actionId","123"));
   MmlfLog.close();
   String info=Files.readString(temp.resolve("logs/fabric.jsonl"));
   assertFalse(info.contains("hidden"));assertFalse(info.contains("do-not-store"));assertTrue(info.contains("[REDACTED]"));assertTrue(info.contains("123"));
   MmlfLog.configure("neoforge",temp,"DEBUG");MmlfLog.debug("render","frame",Map.of("frameId",42));MmlfLog.close();
   assertTrue(Files.readString(temp.resolve("logs/neoforge.jsonl")).contains("\"level\":\"DEBUG\""));
  } finally {MmlfLog.close();}
 }
 /** A block that just broke reports an infinite break speed; logging it once crashed the game tick that logged it. */
 @Test void nonFiniteNumbersAreLoggedAsText() throws Exception {
  try {
   MmlfLog.configure("neoforge",temp,"DEBUG");
   assertDoesNotThrow(()->MmlfLog.debug("interaction","mining",Map.of("speed",Float.POSITIVE_INFINITY,"ratio",Double.NaN)));
   MmlfLog.close();
   String log=Files.readString(temp.resolve("logs/neoforge.jsonl"));
   assertTrue(log.contains("\"speed\":\"Infinity\"")); assertTrue(log.contains("\"ratio\":\"NaN\""));
  } finally {MmlfLog.close();}
 }
}
