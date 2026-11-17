package net.i2p.i2ptunnel.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class DechunkedFramingTest {
    private DechunkedOutputStream stream() {
        return new DechunkedOutputStream(new ByteArrayOutputStream(), () -> {}, true);
    }

    private void write(DechunkedOutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testValidMessageAtEveryWriteBoundary() throws IOException {
        String message = "3;extension=yes\r\nabc\r\n2\r\nde\r\n0\r\nX-Test: yes\r\n\r\n";
        byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
        for (boolean strip : new boolean[] { true, false }) {
            for (int block = 1; block < bytes.length; block++) {
                final int[] done = { 0 };
                ByteArrayOutputStream target = new ByteArrayOutputStream();
                DechunkedOutputStream out = new DechunkedOutputStream(target, () -> done[0]++, strip);
                for (int offset = 0; offset < bytes.length; offset += block)
                    out.write(bytes, offset, Math.min(block, bytes.length - offset));
                assertEquals(1, done[0]);
                assertEquals(strip ? "abcde" : message, target.toString("US-ASCII"));
            }
        }
    }

    @Test
    public void testEmptyChunkLengthIsRejected() {
        assertThrows(IOException.class, () -> write(stream(), "\r\n\r\n"));
    }

    @Test
    public void testMissingDataTerminatorIsRejected() {
        assertThrows(IOException.class, () -> write(stream(), "1\r\naX"));
    }

    @Test
    public void testMissingLfAfterDataCrIsRejected() {
        assertThrows(IOException.class, () -> write(stream(), "1\r\na\rX"));
    }

    @Test
    public void testMissingLfAfterTrailerCrIsRejected() {
        assertThrows(IOException.class, () -> write(stream(), "0\r\nX: y\rZ"));
    }

    @Test
    public void testLongExtensionsAreBoundedAcrossWrites() throws IOException {
        DechunkedOutputStream out = stream();
        write(out, "1;");
        byte[] block = new byte[1024];
        java.util.Arrays.fill(block, (byte) 'x');
        assertThrows(IOException.class, () -> {
            for (int i = 0; i < 65; i++)
                out.write(block);
        });
    }

    @Test
    public void testTrailersHaveAnAggregateLimit() throws IOException {
        DechunkedOutputStream out = stream();
        write(out, "0\r\n");
        assertThrows(IOException.class, () -> {
            for (int i = 0; i < 12000; i++)
                write(out, "X: y\r\n");
        });
    }

    @Test
    public void testHexLettersUseSameLimitAsDigits() throws IOException {
        write(stream(), "7fffffff\r\n");
        write(stream(), "7FFFFFFF\r\n");
        assertThrows(IOException.class, () -> write(stream(), "80000000\r\n"));
    }

    @Test
    public void testMetadataLimitResetsAfterEachChunk() throws IOException {
        final int[] done = { 0 };
        DechunkedOutputStream out = new DechunkedOutputStream(new ByteArrayOutputStream(), () -> done[0]++, true);
        for (int i = 0; i < 20000; i++)
            write(out, "1\r\na\r\n");
        write(out, "0\r\n\r\n");
        assertEquals(1, done[0]);
    }
}
