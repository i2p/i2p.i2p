package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HTTPRequestFramingTest {
    @Test
    public void conflictingLengthsAcrossCaseAreRejected() throws Exception {
        reject("Content-Length: 1\r\ncOnTeNt-LeNgTh: 2\r\n");
    }

    @Test
    public void identicalLengthsBecomeOneCanonicalValue() throws Exception {
        Map<String, List<String>> headers = request("Content-Length: 001\r\ncontent-length: 1\r\n");
        assertEquals(1, headers.size());
        assertEquals(1, headers.get("Content-Length").size());
        assertEquals("1", headers.get("Content-Length").get(0));
    }

    @Test
    public void equalLengthListIsNormalized() throws Exception {
        assertEquals("42", request("Content-Length: 42, 042\r\n").get("Content-Length").get(0));
    }

    @Test
    public void invalidLengthsAreRejected() throws Exception {
        for (String value : new String[] { "", "+1", "-1", "1 2", "0x10", "1,", ",1", "1,2", "1\t2" })
            reject("Content-Length: " + value + "\r\n");
    }

    @Test
    public void oversizedLengthCannotOverflow() throws Exception {
        reject("Content-Length: 9223372036854775808\r\n");
        assertEquals("9223372036854775807", request("Content-Length: 9223372036854775807\r\n").get("Content-Length").get(0));
    }

    @Test
    public void transferEncodingAndLengthAreRejectedInEitherOrder() throws Exception {
        reject("Content-Length: 0\r\ntransfer-encoding: chunked\r\n");
        reject("Transfer-Encoding: chunked\r\ncOnTeNt-LeNgTh: 0\r\n");
    }

    @Test
    public void chunkedMustBeFinalAndUnique() throws Exception {
        for (String value : new String[] { "", "gzip", "chunked, gzip", "chunked, chunked", "gzip,", "chunked;foo=bar" })
            reject("Transfer-Encoding: " + value + "\r\n");
    }

    @Test
    public void http10CannotUseTransferEncoding() throws Exception {
        rejectMessage("POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n", new String[0]);
    }

    @Test
    public void codingOrderIsPreservedAcrossMixedCaseHeaders() throws Exception {
        Map<String, List<String>> headers = request("transfer-encoding: gzip\r\nTransfer-Encoding: CHUNKED\r\n");
        assertEquals(1, headers.get("Transfer-Encoding").size());
        assertEquals("gzip, chunked", headers.get("Transfer-Encoding").get(0));
    }

    @Test
    public void whitespaceAndSeparatorsInNamesAreRejected() throws Exception {
        for (String name : new String[] { " Content-Length", "Content-Length ", "Content\tLength", "Bad(Name)", "Bad/Name" })
            reject(name + ": 0\r\n");
    }

    @Test
    public void controlCharactersInValuesAreRejected() throws Exception {
        for (char c : new char[] { 0, 1, 11, 12, 31, 127 })
            reject("X-Test: before" + c + "after\r\n");
    }

    @Test
    public void foldedHeadersAreRejected() throws Exception {
        reject("X-Test: first\r\n second: hidden\r\n");
    }

    @Test
    public void embeddedCarriageReturnsInHeadersAreRejected() throws Exception {
        reject("Content\r-Length: 0\r\n");
        reject("Content-Length: 0\rX-Extra: value\r\n");
    }

    @Test
    public void embeddedCarriageReturnInRequestLineIsRejected() throws Exception {
        rejectMessage("GE\rT / HTTP/1.1\r\n\r\n", new String[0]);
    }

    @Test
    public void duplicateHostsAreRejected() throws Exception {
        reject("Host: first.i2p\r\nhost: second.i2p\r\n");
    }

    @Test
    public void strippedHeadersAreStillValidated() throws Exception {
        rejectMessage("POST / HTTP/1.1\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n",
            new String[] { "content-length" });
    }

    @Test
    public void validOptionalWhitespaceTokensAndBodyArePreserved() throws Exception {
        ByteArrayInputStream input = input("POST / HTTP/1.1\r\nX-!#$%&'*+-.^_`|~: \tvalue\t\r\nContent-Length:0\r\n\r\nBODY");
        Map<String, List<String>> headers = read(input, new String[0]);
        assertEquals("value", headers.get("X-!#$%&'*+-.^_`|~").get(0));
        assertEquals('B', input.read());
    }

    @Test
    public void lfOnlyRequestStillWorks() throws Exception {
        assertEquals("0", read(input("GET / HTTP/1.1\nContent-Length:0\n\n"), new String[0]).get("Content-Length").get(0));
    }

    @Test
    public void deadlineAppliesToCarriageReturns() throws Exception {
        deadline('\r');
    }

    @Test
    public void deadlineAppliesToNewline() throws Exception {
        deadline('\n');
    }

    @Test
    public void responseParsingKeepsItsExistingCompatibility() throws Exception {
        Map<String, List<String>> headers = I2PTunnelHTTPServer.readHeaders(null,
            input("HTTP/1.1 200 OK\r\nX-Test : value\r\n\r\n"), new StringBuilder(), new String[0],
            I2PAppContext.getGlobalContext(), 10000);
        assertEquals("value", headers.get("X-Test").get(0));
    }

    private static Map<String, List<String>> request(String headers) throws Exception {
        return read(input("POST / HTTP/1.1\r\n" + headers + "\r\n"), new String[0]);
    }

    private static ByteArrayInputStream input(String message) throws Exception {
        return new ByteArrayInputStream(message.getBytes("ISO-8859-1"));
    }

    private static Map<String, List<String>> read(InputStream input, String[] skip) throws Exception {
        I2PSocket socket = mock(I2PSocket.class);
        when(socket.getInputStream()).thenReturn(input);
        return I2PTunnelHTTPServer.readHeaders(socket, null, new StringBuilder(), skip,
            I2PAppContext.getGlobalContext(), 10000);
    }

    private static void reject(String headers) throws Exception {
        rejectMessage("POST / HTTP/1.1\r\n" + headers + "\r\n", new String[0]);
    }

    private static void rejectMessage(String message, String[] skip) throws Exception {
        try {
            read(input(message), skip);
            fail("Accepted ambiguous or malformed request");
        } catch (IOException expected) {
            assertEquals("BadRequestException", expected.getClass().getSimpleName());
        }
    }

    private static void deadline(final char first) throws Exception {
        InputStream slow = new InputStream() {
            int reads;
            public int read() {
                if (reads++ == 0) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    return first;
                }
                return '\n';
            }
        };
        I2PSocket socket = mock(I2PSocket.class);
        when(socket.getInputStream()).thenReturn(slow);
        Method method = I2PTunnelHTTPServer.class.getDeclaredMethod("readLine", I2PSocket.class, StringBuilder.class, long.class);
        method.setAccessible(true);
        try {
            method.invoke(null, socket, new StringBuilder(), 1L);
            fail("Line terminator bypassed total read deadline");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof SocketTimeoutException);
        }
    }
}
