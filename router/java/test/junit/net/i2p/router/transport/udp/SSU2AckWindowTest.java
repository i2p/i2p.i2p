package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class SSU2AckWindowTest {
    @Test
    public void testCallbacksOnlyScanReceivedWindow() {
        SSU2Bitfield received = new SSU2Bitfield(256, 256) {
            @Override
            public boolean get(long bit) {
                assertTrue("Scanned before received ACK window: " + bit, bit >= getOffset());
                return super.get(bit);
            }
        };
        received.set(256);
        received.set(511);
        SSU2Bitfield previouslyAcked = new SSU2Bitfield(1024, 0);
        final List<Long> callbacks = new ArrayList<Long>();
        received.forEachAndNot(previouslyAcked, callbacks::add);
        assertEquals(Arrays.asList(256L, 511L), callbacks);
        callbacks.clear();
        received.forEachAndNot(previouslyAcked, callbacks::add);
        assertTrue(callbacks.isEmpty());
    }
}
