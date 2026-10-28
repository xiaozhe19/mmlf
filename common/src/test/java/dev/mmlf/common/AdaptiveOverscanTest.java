package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdaptiveOverscanTest {
 @Test void stationaryKeepsNativeDensityAndTurnExpandsImmediatelyWithinLimit() {
  var adaptive=new AdaptiveOverscan(1.25);
  assertEquals(1,adaptive.update(70,16.0/9,0,0,0.04,1));
  assertEquals(1.25,adaptive.update(70,16.0/9,500,0,0.04,2),1e-9);
  double released=adaptive.update(70,16.0/9,0,0,0.04,250_000_002L);
  assertTrue(released<1.25 && released>1);
  for(long time=500_000_002L;time<=1_500_000_002L;time+=250_000_000L) adaptive.update(70,16.0/9,0,0,0.04,time);
  assertEquals(1,adaptive.update(70,16.0/9,0,0,0.04,1_750_000_002L),1e-9);
 }
 @Test void accountsForHorizontalAndVerticalGeometryAndRejectsInvalidValues() {
  var a=new AdaptiveOverscan(2);
  double horizontal=a.update(70,16.0/9,100,0,0.05,1);
  a.reset(); double vertical=a.update(70,16.0/9,0,100,0.05,1);
  assertEquals(1.2010897,horizontal,1e-6); assertEquals(1.1983585,vertical,1e-6);
  assertEquals(vertical,a.update(Double.NaN,1,0,0,1,2));
  assertThrows(IllegalArgumentException.class,()->new AdaptiveOverscan(0.9));
 }
}
