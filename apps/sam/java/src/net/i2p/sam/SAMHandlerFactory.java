package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 * SAM handler factory class.
 */
class SAMHandlerFactory {

    private static final String VERSION = "3.3";

    private static final int HELLO_TIMEOUT = 60 * 1000;

    /**
     * Return the right SAM handler depending on the protocol version
     * required by the client.
     *
     * @param s         Socket attached to SAM client
     * @param i2cpProps config options for our i2cp connection
     * @throws SAMException if the connection handshake (HELLO message) was
     *                      malformed
     * @return A SAM protocol handler, or null if the client closed before the
     *         handshake
     */
    public static SAMHandler createSAMHandler(SocketChannel s, Properties i2cpProps,
            SAMBridge parent) throws SAMException {
        String line;
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(SAMHandlerFactory.class);
        SAMSecureSessionInterface secureSession = parent.secureSession();

        try {
            Socket sock = s.socket();
            sock.setKeepAlive(true);
            StringBuilder buf = new StringBuilder(128);
            ReadLine.readLine(sock, buf, HELLO_TIMEOUT);
            sock.setSoTimeout(0);
            line = buf.toString();
        } catch (SocketTimeoutException e) {
            throw new SAMException("Timeout waiting for HELLO VERSION", e);
        } catch (IOException e) {
            throw new SAMException("Error reading from socket", e);
        } catch (RuntimeException e) {
            throw new SAMException("Unexpected error", e);
        }
        if (log.shouldDebug())
            log.debug("New message received: [" + line + ']');

        // Message format: HELLO VERSION [MIN=v1] [MAX=v2]
        Properties props = SAMUtils.parseParams(line);
        if (!"HELLO".equals(props.remove(SAMUtils.COMMAND)) ||
                !"VERSION".equals(props.remove(SAMUtils.OPCODE))) {
            throw new SAMException("Must start with HELLO VERSION");
        }

        String minVer = props.getProperty("MIN");
        if (minVer == null) {
            // throw new SAMException("Missing MIN parameter in HELLO VERSION message");
            // MIN optional as of 0.9.14
            minVer = "1";
        }

        String maxVer = props.getProperty("MAX");
        if (maxVer == null) {
            // throw new SAMException("Missing MAX parameter in HELLO VERSION message");
            // MAX optional as of 0.9.14
            maxVer = "99.99";
        }

        String ver = chooseBestVersion(minVer, maxVer);

        if (ver == null) {
            SAMHandler.writeString("HELLO REPLY RESULT=NOVERSION\n", s);
            return null;
        }

        if (secureSession != null) {
            boolean approval = secureSession.approveOrDenySecureSession(i2cpProps, props);
            if (!approval) {
                throw new SAMException("SAM connection cancelled by user request");
            }
        }

        // Let's answer positively
        if (!SAMHandler.writeString("HELLO REPLY RESULT=OK VERSION=" + ver + "\n", s))
            throw new SAMException("Error writing to socket");

        // ...and instantiate the right SAM handler
        int verMajor = getMajor(ver);
        int verMinor = getMinor(ver);
        SAMHandler handler;

        try {
            switch (verMajor) {
                case 1:
                    handler = new SAMv1Handler(s, verMajor, verMinor, i2cpProps, parent);
                    break;
                case 2:
                    handler = new SAMv2Handler(s, verMajor, verMinor, i2cpProps, parent);
                    break;
                case 3:
                    handler = new SAMv3Handler(s, verMajor, verMinor, i2cpProps, parent);
                    break;
                default:
                    log.error("BUG! Trying to initialize the wrong SAM version!");
                    throw new SAMException("BUG! (in handler instantiation)");
            }
        } catch (IOException e) {
            log.error("Error creating the handler for version " + verMajor, e);
            throw new SAMException("IOException caught during SAM handler instantiation");
        }
        return handler;
    }

    /*
     * @return "x.y" the best version we can use, or null on failure
     */
    private static String chooseBestVersion(String minVer, String maxVer) {
        if (VersionComparator.comp(VERSION, minVer) >= 0 &&
                VersionComparator.comp(VERSION, maxVer) <= 0)
            return VERSION;
        if (VersionComparator.comp("3.2", minVer) >= 0 &&
                VersionComparator.comp("3.2", maxVer) <= 0)
            return "3.2";
        if (VersionComparator.comp("3.1", minVer) >= 0 &&
                VersionComparator.comp("3.1", maxVer) <= 0)
            return "3.1";
        // in VersionComparator, "3" < "3.0" so
        // use comparisons carefully
        if (VersionComparator.comp("3.0", minVer) >= 0 &&
                VersionComparator.comp("3", maxVer) <= 0)
            return "3.0";
        if (VersionComparator.comp("2.0", minVer) >= 0 &&
                VersionComparator.comp("2", maxVer) <= 0)
            return "2.0";
        if (VersionComparator.comp("1.0", minVer) >= 0 &&
                VersionComparator.comp("1", maxVer) <= 0)
            return "1.0";
        return null;
    }

    /* Get the major protocol version from a string, or -1 */
    private static int getMajor(String ver) {
        if (ver == null)
            return -1;
        int dot = ver.indexOf('.');
        if (dot == 0)
            return -1;
        if (dot > 0)
            ver = ver.substring(0, dot);
        try {
            return Integer.parseInt(ver);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /* Get the minor protocol version from a string, or -1 */
    private static int getMinor(String ver) {
        if ((ver == null) || (ver.indexOf('.') < 0))
            return -1;
        try {
            String major = ver.substring(ver.indexOf('.') + 1);
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return -1;
        } catch (ArrayIndexOutOfBoundsException e) {
            return -1;
        }
    }
}
