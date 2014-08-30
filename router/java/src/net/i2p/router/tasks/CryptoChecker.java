package net.i2p.router.tasks;

import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;

import net.i2p.crypto.SigType;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Warn about unavailable crypto to router and wrapper logs
 *
 *  @since 0.9.15
 */
public class CryptoChecker {

    private static String JRE6 = "http://www.oracle.com/technetwork/java/javase/downloads/index.html";
    private static String JRE7 = "http://www.oracle.com/technetwork/java/javase/documentation/java-se-7-doc-download-435117.html";
    private static String JRE8 = "http://www.oracle.com/technetwork/java/javase/documentation/jdk8-doc-downloads-2133158.html";

    public static void warnUnavailableCrypto(RouterContext ctx) {
        if (SystemVersion.isAndroid())
            return;
        boolean unavail = false;
        Log log = null;
        for (SigType t : SigType.values()) {
            if (!t.isAvailable()) {
                if (!unavail) {
                    unavail = true;
                    log = ctx.logManager().getLog(CryptoChecker.class);
                }
                String s = "Crypto " + t + " is not available";
                log.logAlways(log.WARN, s);
                System.out.println("Warning: " + s);
            }
        }
        if (unavail) {
            if (!SystemVersion.isJava7()) {
                String s = "Java version: " + System.getProperty("java.version") + " Please consider upgrading to Java 7";
                log.logAlways(log.WARN, s);
                System.out.println(s);
            }
            if (!isUnlimited()) {
                String s = "Please consider installing the Java Cryptography Unlimited Strength Jurisdiction Policy Files from ";
                if (SystemVersion.isJava8())
                    s  += JRE8;
                else if (SystemVersion.isJava7())
                    s  += JRE7;
                else
                    s  += JRE6;
                log.logAlways(log.WARN, s);
                System.out.println(s);
            }
            String s = "This crypto will be required in a future release";
            log.logAlways(log.WARN, s);
            System.out.println("Warning: " + s);
        }
    }

    /**
     *  Following code adapted from Orchid
     *  According to http://www.subgraph.com/orchid.html ,
     *  "Orchid is licensed under a three-clause BSD license."
     */
    private static boolean isUnlimited() {
        try {
            if (Cipher.getMaxAllowedKeyLength("AES") < 256)
                return false;
        } catch (NoSuchAlgorithmException e) {
            return false;
        } catch (NoSuchMethodError e) {
        }
        return true;
    }
}

