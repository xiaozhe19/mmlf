package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PredictionLeadTest {
 @Test void smoothsBoundsAndExpiresMeasurements() {
  var lead=new PredictionLead(25);
  assertEquals(25,lead.milliseconds(1));
  lead.observeRender(10); lead.observeTransfer(30,100);
  assertEquals(40,lead.milliseconds(100));
  lead.observeTransfer(130,101); assertEquals(50,lead.milliseconds(101));
  lead.observeTransfer(Double.NaN,102); assertEquals(50,lead.milliseconds(102));
  assertEquals(25,lead.milliseconds(5_000_000_102L));
  lead.reset(); assertEquals(25,lead.milliseconds(103));
 }
}
