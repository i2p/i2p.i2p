package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.i2p.I2PAppContext;

public final class HTTPPostLengthRegression {
    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        int failures = 0;
        MessageDigest ascii = MessageDigest.getInstance("SHA-256");
        for (String body : new String[] {"plain ASCII", "caf\u00e9", "\u4e2d\u6587", "\ud83d\ude00"}) {
            EepGet client = new EepGet(context, false, null, -1, 0, -1, -1, null,
                    new ByteArrayOutputStream(), "http://example.i2p/submit", true, null, body);
            String request = client.getRequest();
            int declared = Integer.parseInt(request.split("Content-length: ")[1].split("\r\n")[0]);
            String transmitted = request.substring(request.indexOf("\r\n\r\n") + 4);
            if (!transmitted.equals(body)) throw new AssertionError("POST body changed");
            if (declared != transmitted.getBytes(StandardCharsets.UTF_8).length) {
                failures++;
                System.out.println("FAIL: declared length does not match UTF-8 body bytes");
            }
            if (body.equals("plain ASCII")) ascii.update(request.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("REQUEST_SHA256=" + HexFormat.of().formatHex(ascii.digest()));
        System.out.println("CHECKS=4; FAILURES=" + failures);
        context.logManager().shutdown();
        if (failures != 0) System.exit(1);
    }
}
