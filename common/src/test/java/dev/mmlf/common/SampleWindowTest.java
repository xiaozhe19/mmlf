package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SampleWindowTest {
 @Test void reportsAndResetsRecentFiniteSamples() {
  var window=new SampleWindow(4); window.add(-1); window.add(Double.NaN);
  for(int i=1;i<=5;i++) window.add(i);
  var p=window.drain(); assertEquals(4,p.count()); assertEquals(3,p.p50()); assertEquals(5,p.p95()); assertEquals(5,p.p99());
  assertEquals(0,window.drain().count());
 }
}
