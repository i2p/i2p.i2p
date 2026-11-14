package net.i2p.util;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketEepGet;

public final class I2PSocketRequestLogRegression {
    private static final class Capture extends Log {
        final StringBuilder messages = new StringBuilder();
        Capture(LogManager manager) { super(manager, I2PSocketRequestLogRegression.class); }
        @Override public boolean shouldDebug() { return true; }
        @Override public void debug(String message) { messages.append(message); }
    }

    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        EepGet client = new I2PSocketEepGet(context, null, 0, "unused", "http://example.i2p/path");
        client.addHeader("Authorization", "Bearer PRIVATE_AUTH_MARKER");
        client.addHeader("Cookie", "PRIVATE_COOKIE_MARKER");
        Capture captured = new Capture(context.logManager());
        Field logger = EepGet.class.getDeclaredField("_log");
        logger.setAccessible(true);
        logger.set(client, captured);
        String request = client.getRequest();
        if (!request.contains("PRIVATE_AUTH_MARKER") || !request.contains("PRIVATE_COOKIE_MARKER"))
            throw new AssertionError("request headers changed");
        System.out.println("REQUEST_SHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                           .digest(request.getBytes(StandardCharsets.UTF_8))));
        boolean exposed = captured.messages.toString().contains("PRIVATE_");
        context.logManager().shutdown();
        if (exposed) throw new AssertionError("request headers copied into debug log");
        System.out.println("CHECKS=1; FAILURES=0");
    }
}
