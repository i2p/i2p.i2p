package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class SSU2UnsignedAckTest {
    @Test
    public void testUnsignedPacketNumbersWithoutRanges() {
        for (long thru : new long[] { 0x7fffffffL, 0x80000000L, 0xffffffffL }) {
            SSU2Bitfield bits = SSU2Bitfield.fromACKBlock(thru, 2, null, 0);
            assertEquals(thru, bits.getHighestSet());
            assertTrue(bits.get(thru));
            assertTrue(bits.get(thru - 1));
            assertTrue(bits.get(thru - 2));
            assertFalse(bits.get(thru - 3));
        }
    }

    @Test
    public void testUnsignedPacketNumbersWithRanges() {
        for (long thru : new long[] { 0x7fffffffL, 0x80000000L, 0xffffffffL }) {
            SSU2Bitfield bits = SSU2Bitfield.fromACKBlock(thru, 1, new byte[] { 2, 2 }, 1);
            assertEquals(thru, bits.getHighestSet());
            assertTrue(bits.get(thru));
            assertTrue(bits.get(thru - 1));
            assertFalse(bits.get(thru - 2));
            assertFalse(bits.get(thru - 3));
            assertTrue(bits.get(thru - 4));
            assertTrue(bits.get(thru - 5));
        }
    }

    @Test
    public void testUnsignedPacketNumbersInDiagnostics() {
        assertEquals("ACK 4294967295-4294967294 NACK 4294967293-4294967292 ACK 4294967291-4294967290",
                     SSU2Bitfield.toString(0xffffffffL, 1, new byte[] { 2, 2 }, 1));
    }

    @Test
    public void testRoundTripAckBlocks() throws Exception {
        Random random = new Random(42);
        for (long offset : new long[] { 0, 0x7fffff00L, 0x80000000L, 0xffffff00L }) {
            SSU2Bitfield sent = new SSU2Bitfield(256, offset);
            for (int i = 0; i < 256; i++) {
                if (random.nextBoolean())
                    sent.set(offset + i);
            }
            SSU2Payload.AckBlock ack = sent.toAckBlock(256);
            byte[] data = new byte[3 + ack.getDataLength()];
            ack.write(data, 0);
            long thru = net.i2p.data.DataHelper.fromLong(data, 3, 4);
            byte[] ranges = Arrays.copyOfRange(data, 8, data.length);
            SSU2Bitfield received = SSU2Bitfield.fromACKBlock(thru, data[7] & 0xff, ranges, ranges.length / 2);
            for (int i = 0; i < 256; i++)
                assertEquals("Packet " + (offset + i), sent.get(offset + i), received.get(offset + i));
        }
    }
}
