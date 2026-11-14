package net.i2p.socks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class SOCKS5LengthRegression {
    private static int checks;
    private static int failures;
    private static void finish() {
        System.out.println("CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) System.exit(1);
    }
    public static void main(String[] args) throws Exception { checkSOCKS(); finish(); }
    private static void checkSOCKS() throws Exception {
        ByteArrayOutputStream boundary = new ByteArrayOutputStream();
        SOCKS5Client.connect(successReply(true), boundary, "example.i2p", 80, "u".repeat(255), "p".repeat(255));
        check("SOCKS accepts 255-byte credentials",
              (boundary.toByteArray()[5] & 0xff) == 255 && (boundary.toByteArray()[261] & 0xff) == 255,
              "the boundary credentials were encoded incorrectly");

        ByteArrayOutputStream authOutput = new ByteArrayOutputStream();
        boolean authRejected = false;
        try {
            // 128 characters, but 256 bytes in UTF-8.
            SOCKS5Client.connect(successReply(true), authOutput, "example.i2p", 80,
                                 "\u00e9".repeat(128), "password");
        } catch (SOCKSException expected) {
            authRejected = true;
        }
        check("SOCKS rejects a 256-byte username", authRejected,
              authRejected ? "" : "accepted 256 bytes; the transmitted username length is "
                  + (authOutput.toByteArray()[5] & 0xff));

        ByteArrayOutputStream passwordOutput = new ByteArrayOutputStream();
        boolean passwordRejected = false;
        try {
            SOCKS5Client.connect(successReply(true), passwordOutput, "example.i2p", 80, "u", "p".repeat(256));
        } catch (SOCKSException expected) {
            passwordRejected = true;
        }
        check("SOCKS rejects a 256-byte password", passwordRejected,
              passwordRejected ? "" : "accepted 256 bytes; the transmitted password length is "
                  + (passwordOutput.toByteArray()[7] & 0xff));

        ByteArrayOutputStream hostBoundary = new ByteArrayOutputStream();
        SOCKS5Client.connect(successReply(false), hostBoundary, "a".repeat(251) + ".i2p", 80);
        check("SOCKS accepts a 255-byte hostname", (hostBoundary.toByteArray()[7] & 0xff) == 255,
              "the boundary hostname was encoded incorrectly");

        ByteArrayOutputStream hostOutput = new ByteArrayOutputStream();
        boolean hostRejected = false;
        try {
            SOCKS5Client.connect(successReply(false), hostOutput, "a".repeat(252) + ".i2p", 80);
        } catch (SOCKSException expected) {
            hostRejected = true;
        }
        check("SOCKS rejects a 256-byte hostname", hostRejected,
              hostRejected ? "" : "accepted 256 bytes; the transmitted hostname length is "
                  + (hostOutput.toByteArray()[7] & 0xff));
    }
    private static ByteArrayInputStream successReply(boolean auth) {
        byte[] reply = auth
            ? new byte[] { 5, 2, 1, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 80 }
            : new byte[] { 5, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 80 };
        return new ByteArrayInputStream(reply);
    }
    private static void check(String name, boolean success, String detail) {
        checks++;
        if (success) {
            System.out.println("PASS: " + name);
        } else {
            failures++;
            System.out.println("FAIL: " + name + ": " + detail);
        }
    }
}
