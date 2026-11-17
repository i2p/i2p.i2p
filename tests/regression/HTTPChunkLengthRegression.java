package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.i2p.I2PAppContext;

public final class HTTPChunkLengthRegression {
    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        EepGet client = new EepGet(context, 0, "unused", "http://example.i2p/");
        int failures = 0;
        for (String line : new String[] {"0", "1", "7fff", "7fffffffffffffff"}) {
            client._proxyIn = input(line + "\r\n");
            if (client.readChunkLength() != Long.parseLong(line, 16))
                throw new AssertionError("valid chunk length changed");
        }
        client._proxyIn = input("-1\r\n");
        try {
            client.readChunkLength();
            failures++;
            System.out.println("FAIL: negative chunk length accepted");
        } catch (IOException expected) { }
        byte[] oversized = ("1".repeat(10000) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream stream = new ByteArrayInputStream(oversized);
        client._proxyIn = stream;
        try {
            client.readChunkLength();
            throw new AssertionError("oversized chunk line accepted");
        } catch (IOException expected) {
            if (oversized.length - stream.available() > 4097) {
                failures++;
                System.out.println("FAIL: chunk line read beyond its bound");
            }
            if (expected.getMessage().length() > 256) {
                failures++;
                System.out.println("FAIL: oversized chunk input copied into exception");
            }
        }
        System.out.println("CHECKS=7; FAILURES=" + failures);
        context.logManager().shutdown();
        if (failures != 0) System.exit(1);
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }
}
