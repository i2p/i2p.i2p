package net.i2p.i2ptunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class HTTPResponseHeaderValueTest {
    @Test
    public void testUnspacedValuesKeepFirstCharacter() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = new HTTPResponseOutputStream(target);
        out.write("HTTP/1.1 200 OK\r\nContent-Type:application/json\r\nX-Test:value\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        String result = target.toString("US-ASCII");
        assertTrue(result, result.contains("Content-Type: application/json\r\n"));
        assertTrue(result, result.contains("X-Test: value\r\n"));
        assertEquals("application/json", out._contentType);
    }

    @Test
    public void testContentLengthCannotCompleteBeforeBody() throws IOException {
        final int[] done = { 0 };
        HTTPResponseOutputStream out = new HTTPResponseOutputStream(new ByteArrayOutputStream(),
                true, true, false, () -> done[0]++);
        out.write("HTTP/1.1 200 OK\r\nContent-Length:10\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(10, out._dataExpected);
        assertEquals(0, done[0]);
        out.write(new byte[9]);
        assertEquals(0, done[0]);
        out.write(0);
        assertEquals(1, done[0]);
    }

    @Test
    public void testSplitWritesAndLfOnlyHeaders() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        HTTPResponseOutputStream out = new HTTPResponseOutputStream(target);
        byte[] response = "HTTP/1.1 200 OK\nX-Test:abc\n\n".getBytes(StandardCharsets.US_ASCII);
        for (byte value : response)
            out.write(value);
        assertTrue(target.toString("US-ASCII").contains("X-Test: abc\r\n"));
    }

    @Test
    public void testOptionalWhitespaceAndEmptyValues() throws IOException {
        for (String value : new String[] { " value", "\tvalue", "  value  " }) {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            HTTPResponseOutputStream out = new HTTPResponseOutputStream(target);
            out.write(("HTTP/1.1 200 OK\r\nX-Test:" + value + "\r\nX-Empty:\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertTrue(target.toString("US-ASCII").contains("X-Test: value\r\n"));
            assertTrue(target.toString("US-ASCII").contains("X-Empty: \r\n"));
        }
    }
}
