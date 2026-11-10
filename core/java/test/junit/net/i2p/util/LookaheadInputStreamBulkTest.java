package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class LookaheadInputStreamBulkTest {
    @Test
    public void testBulkReadsPreserveDataAndFooter() throws IOException {
        byte[] data = new byte[8193];
        new Random(42).nextBytes(data);
        for (int footer : new int[] { 1, 8, 31, 1024 }) {
            for (int block : new int[] { 1, 3, 7, 8, 31, 512, 4096 }) {
                LookaheadInputStream in = new LookaheadInputStream(footer);
                in.initialize(new ByteArrayInputStream(data));
                byte[] output = new byte[data.length];
                int offset = 0;
                int count;
                while ((count = in.read(output, offset, Math.min(block, output.length - offset))) >= 0)
                    offset += count;
                assertEquals(data.length - footer, offset);
                assertArrayEquals(Arrays.copyOf(data, offset), Arrays.copyOf(output, offset));
                assertArrayEquals(Arrays.copyOfRange(data, offset, data.length), in.getFooter());
            }
        }
    }

    @Test
    public void testMixedReadsOffsetsAndShortSourceReads() throws IOException {
        byte[] data = new byte[257];
        new Random(7).nextBytes(data);
        ByteArrayInputStream source = new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(5, length));
            }
        };
        LookaheadInputStream in = new LookaheadInputStream(8);
        in.initialize(source);
        int position = 0;
        byte[] target = new byte[23];
        while (position < data.length - 8) {
            assertEquals(data[position++] & 0xff, in.read());
            int count = in.read(target, 3, 13);
            assertTrue(count > 0);
            assertArrayEquals(Arrays.copyOfRange(data, position, position + count),
                              Arrays.copyOfRange(target, 3, 3 + count));
            position += count;
        }
        assertEquals(-1, in.read());
        assertArrayEquals(Arrays.copyOfRange(data, position, data.length), in.getFooter());
    }

    @Test
    public void testUnderlyingReadsAreBatched() throws IOException {
        final int[] singleReads = { 0 };
        final int[] bulkReads = { 0 };
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[8200]) {
            @Override
            public synchronized int read() {
                singleReads[0]++;
                return super.read();
            }
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                bulkReads[0]++;
                return super.read(target, offset, length);
            }
        };
        LookaheadInputStream in = new LookaheadInputStream(8);
        in.initialize(source);
        singleReads[0] = bulkReads[0] = 0;
        assertEquals(8192, in.read(new byte[8192]));
        assertEquals(0, singleReads[0]);
        assertEquals(1, bulkReads[0]);
    }

    @Test
    public void testEmptyReadAtEof() throws IOException {
        LookaheadInputStream in = new LookaheadInputStream(8);
        in.initialize(new ByteArrayInputStream(new byte[8]));
        assertEquals(-1, in.read());
        assertEquals(0, in.read(new byte[1], 1, 0));
    }

    @Test
    public void testZeroLookaheadPassesDataThrough() throws IOException {
        LookaheadInputStream in = new LookaheadInputStream(0);
        in.initialize(new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
        assertEquals(1, in.read());
        byte[] target = new byte[3];
        assertEquals(2, in.read(target));
        assertArrayEquals(new byte[] { 2, 3, 0 }, target);
        assertEquals(-1, in.read());
        assertArrayEquals(new byte[0], in.getFooter());
    }

    @Test
    public void testInvalidRangesDoNotConsumeInput() throws IOException {
        LookaheadInputStream in = new LookaheadInputStream(8);
        in.initialize(new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }));
        assertThrows(NullPointerException.class, () -> in.read(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[2], -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[2], 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[2], 1, Integer.MAX_VALUE));
        assertEquals(1, in.read());
    }

    @Test
    public void testCanBeReinitialized() throws IOException {
        LookaheadInputStream in = new LookaheadInputStream(3);
        for (int i = 0; i < 3; i++) {
            in.initialize(new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5 }));
            byte[] target = new byte[9];
            assertEquals(2, in.read(target));
            assertEquals(-1, in.read());
            assertArrayEquals(new byte[] { 3, 4, 5 }, in.getFooter());
        }
    }
}
