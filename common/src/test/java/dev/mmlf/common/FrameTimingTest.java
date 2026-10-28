package dev.mmlf.common;

import dev.mmlf.common.Messages.Frame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameTimingTest {
    private Frame frame(long captured,double offset,double uncertainty) {
        return new Frame("remote",1,null,0,1,1,captured,true,"opengl-window-depth",offset,"session",1,uncertainty);
    }
    @Test void calibratesAndAccountsForBothUncertaintyBounds() {
        var clock=FrameTiming.fromPing(1000,2100,1200);
        assertEquals(1000,clock.offsetNanos()); assertEquals(100,clock.uncertaintyNanos());
        var age=FrameTiming.age(frame(500,500,50),clock,1200);
        assertEquals(1200,age.estimatedNanos()); assertEquals(150,age.uncertaintyNanos());
        assertTrue(FrameTiming.isFresh(frame(500,500,50),clock,1200,1350));
        assertFalse(FrameTiming.isFresh(frame(500,500,50),clock,1200,1349));
    }
    @Test void oneSlowPingDoesNotInvalidateCalibration() {
        var filter=new FrameTiming.ClockFilter(3);
        var good=FrameTiming.fromPing(0,1_500_000,2_000_000);
        assertSame(good,filter.add(good));
        // A 300 ms round trip alone would exceed MAX_UNCERTAINTY and reject every frame.
        var slow=FrameTiming.fromPing(1_000_000_000L,1_150_000_000L,1_300_000_000L);
        assertFalse(slow.validAt(slow.sampledNanos()));
        assertSame(good,filter.add(slow));
        var better=FrameTiming.fromPing(2_000_000_000L,2_000_200_000L,2_000_400_000L);
        assertSame(better,filter.add(better));
        assertNull(filter.best(better.sampledNanos()+FrameTiming.MAX_CALIBRATION_AGE+1));
        filter.clear(); assertNull(filter.best(better.sampledNanos()));
    }
    @Test void rejectsMissingStaleUncertainAndFutureMeasurements() {
        var clock=FrameTiming.fromPing(1000,2100,1200);
        assertNull(FrameTiming.age(frame(0,0,0),null,1200));
        assertNull(FrameTiming.age(frame(0,0,-1),clock,1200));
        assertNull(FrameTiming.age(frame(0,0,FrameTiming.MAX_UNCERTAINTY+1),clock,1200));
        assertNull(FrameTiming.age(frame(5000,1000,0),clock,1200));
        assertNull(FrameTiming.age(frame(0,0,0),clock,1200+FrameTiming.MAX_CALIBRATION_AGE+1));
        assertThrows(IllegalArgumentException.class,()->FrameTiming.fromPing(2,1,1));
    }
}
