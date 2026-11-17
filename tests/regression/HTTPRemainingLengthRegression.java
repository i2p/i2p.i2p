package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import net.i2p.I2PAppContext;

/** Exercises the real HTTP/HTTPS fetch loops using only in-memory response bytes. */
public final class HTTPRemainingLengthRegression {
    private static SSLEepGet.SSLState sslState;
    private static int checks;
    private static int failures;

    /** SSLEepGet sets the socket timeout; no real socket connection is needed here. */
    private static final class InMemorySocket extends Socket {
        @Override
        public void setSoTimeout(int timeout) { }
    }

    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        MessageDigest valid = MessageDigest.getInstance("SHA-256");
        for (boolean tls : new boolean[] {false, true}) {
            check(context, tls, "empty fixed body", "Content-Length: 0\r\n\r\n", "", true, valid);
            check(context, tls, "small fixed body", "Content-Length: 5\r\n\r\nhello", "hello", true, valid);
            check(context, tls, "close-delimited body", "\r\nhello", "hello", true, valid);
            check(context, tls, "small chunked body", "Transfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n",
                  "hello", true, valid);
            check(context, tls, "multiple small chunks", "Transfer-Encoding: chunked\r\n\r\n1\r\nA\r\n1\r\nB\r\n0\r\n\r\n",
                  "AB", true, valid);

            // These are truncated response prefixes. None requires allocating or reading a large body.
            for (long length : new long[] {Integer.MAX_VALUE, 2147483648L, 4294967296L, 4294967297L, Long.MAX_VALUE}) {
                check(context, tls, "truncated fixed body " + length,
                      "Content-Length: " + length + "\r\n\r\nX", "X", false, null);
                String hexLength = Long.toHexString(length);
                check(context, tls, "truncated first chunk " + length,
                      "Transfer-Encoding: chunked\r\n\r\n" + hexLength + "\r\nX", "X", false, null);
                check(context, tls, "truncated later chunk " + length,
                      "Transfer-Encoding: chunked\r\n\r\n1\r\nA\r\n" + hexLength + "\r\nB", "AB", false, null);
            }
        }
        System.out.println("VALID_BODY_SHA256=" + HexFormat.of().formatHex(valid.digest()));
        System.out.println("CHECKS=" + checks + "; FAILURES=" + failures);
        context.logManager().shutdown();
        if (failures != 0)
            System.exit(1);
    }

    private static void check(I2PAppContext context, boolean tls, String name, String response,
                              String expectedBody, boolean expectComplete, MessageDigest valid) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EepGet client;
        if (tls) {
            SSLEepGet ssl = new SSLEepGet(context, output, "https://example.invalid/", sslState);
            if (sslState == null)
                sslState = ssl.getSSLState();
            client = ssl;
            client._proxy = new InMemorySocket();
        } else {
            client = new EepGet(context, false, null, -1, 0, -1, -1, null, output,
                                "http://example.invalid/", true, null, null);
        }
        client._proxyIn = new ByteArrayInputStream(("HTTP/1.1 200 OK\r\n" + response)
                                                  .getBytes(StandardCharsets.US_ASCII));
        client._keepFetching = true;
        boolean complete = true;
        try {
            client.doFetch(null);
        } catch (IOException expectedForTruncatedBody) {
            complete = false;
        } finally {
            if (client._proxy != null)
                client._proxy.close();
        }
        byte[] actual = output.toByteArray();
        byte[] expected = expectedBody.getBytes(StandardCharsets.US_ASCII);
        checks++;
        if (complete != expectComplete || !Arrays.equals(actual, expected)) {
            failures++;
            System.out.println("FAIL: " + (tls ? "HTTPS " : "HTTP ") + name
                               + ": complete=" + complete + ", expectedComplete=" + expectComplete
                               + ", output=" + HexFormat.of().formatHex(actual)
                               + ", expectedOutput=" + HexFormat.of().formatHex(expected));
        }
        if (valid != null) {
            valid.update(name.getBytes(StandardCharsets.US_ASCII));
            valid.update((byte) 0);
            valid.update(actual);
            valid.update((byte) 0);
        }
    }
}
