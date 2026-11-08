package dev.mmlf.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoseHistoryTest {
    @Test void remembersRecentSequencesOnly() {
        PoseHistory history=new PoseHistory(4);
        assertEquals(Long.MIN_VALUE,history.publishedAt(1));
        for(long s=1;s<=6;s++) history.published(s,s*1000);
        assertEquals(6000,history.publishedAt(6));
        assertEquals(3000,history.publishedAt(3));
        assertEquals(Long.MIN_VALUE,history.publishedAt(2),"overwritten by sequence 6");
        assertEquals(Long.MIN_VALUE,history.publishedAt(0));
        assertEquals(Long.MIN_VALUE,history.publishedAt(7),"not yet published");
        assertEquals(0,history.behind(6));
        assertEquals(3,history.behind(3));
    }
}
