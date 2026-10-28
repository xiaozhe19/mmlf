package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CompletedFrameSelectorTest {
 @Test void choosesNewestReadyWithoutRegressingWhenFencesCompleteOutOfOrder() {
  assertEquals(2,CompletedFrameSelector.newest(new long[]{10,12,11},new boolean[]{true,false,true},9));
  assertEquals(1,CompletedFrameSelector.newest(new long[]{10,12,11},new boolean[]{true,true,true},11));
  assertEquals(-1,CompletedFrameSelector.newest(new long[]{10,12,11},new boolean[]{true,false,true},11));
  assertThrows(IllegalArgumentException.class,()->CompletedFrameSelector.newest(new long[]{1},new boolean[]{},0));
 }
}
