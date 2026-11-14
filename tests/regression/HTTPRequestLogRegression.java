package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.i2p.I2PAppContext;

public final class HTTPRequestLogRegression {
    private static final String AUTH = "AUTH_PRIVATE_MARKER";
    private static final String COOKIE = "COOKIE_PRIVATE_MARKER";
    private static final String BODY = "BODY_PRIVATE_MARKER";

    private static final class Capture extends Log {
        final StringBuilder messages = new StringBuilder();
        Capture(LogManager manager) { super(manager, HTTPRequestLogRegression.class); }
        @Override public boolean shouldLog(int priority) { return true; }
        @Override public void debug(String message) { messages.append(message).append('\n'); }
    }

    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        String url = "http://example.i2p/path";
        EepGet[] clients = {
            new EepGet(context, true, "127.0.0.1", 4444, 0, "unused", url, BODY),
            new PartialEepGet(context, "127.0.0.1", 4444, new ByteArrayOutputStream(), url, 32),
            new EepHead(context, "127.0.0.1", 4444, 0, url)
        };
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int failures = 0;
        for (EepGet client : clients) {
            client.addAuthorization("user", "password");
            client._authState.authMode = EepGet.AUTH_MODE.BASIC;
            client.addHeader("Authorization", "Bearer " + AUTH);
            client.addHeader("Cookie", "session=" + COOKIE);
            Capture captured = new Capture(context.logManager());
            Field logger = EepGet.class.getDeclaredField("_log");
            logger.setAccessible(true);
            logger.set(client, captured);
            String request = client.getRequest();
            digest.update(request.getBytes(StandardCharsets.UTF_8));
            if (!request.contains("Proxy-Authorization: Basic dXNlcjpwYXNzd29yZA=="))
                throw new AssertionError("proxy authorization was removed from the request");
            if (!(client instanceof EepHead) && (!request.contains(AUTH) || !request.contains(COOKIE)))
                throw new AssertionError("custom headers were removed from the request");
            if (client.getClass() == EepGet.class && !request.endsWith(BODY))
                throw new AssertionError("POST body was removed from the request");
            String messages = captured.messages.toString();
            if (messages.contains(AUTH) || messages.contains(COOKIE) || messages.contains(BODY)
                || messages.contains("dXNlcjpwYXNzd29yZA==")) {
                failures++;
                System.out.println("FAIL: request content in " + client.getClass().getSimpleName() + " debug log");
            }
        }
        System.out.println("REQUEST_SHA256=" + HexFormat.of().formatHex(digest.digest()));
        System.out.println("CHECKS=3; FAILURES=" + failures);
        context.logManager().shutdown();
        if (failures != 0) System.exit(1);
    }
}
