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
import java.io.UnsupportedEncodingException;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 * SAM handler factory class.
 */
class SAMHandlerFactory {

    private static final String VERSION = "3.0";

    /**
     * Return the right SAM handler depending on the protocol version
     * required by the client.
     *
     * @param s Socket attached to SAM client
     * @param i2cpProps config options for our i2cp connection
     * @throws SAMException if the connection handshake (HELLO message) was malformed
     * @return A SAM protocol handler, or null if the client closed before the handshake
     */
    public static SAMHandler createSAMHandler(SocketChannel s, Properties i2cpProps) throws SAMException {
        String line;
        StringTokenizer tok;
        Log log = new Log(SAMHandlerFactory.class);

        try {
            line = DataHelper.readLine(s.socket().getInputStream());
            if (line == null) {
                log.debug("Connection closed by client");
                return null;
            }
            tok = new StringTokenizer(line.trim(), " ");
        } catch (IOException e) {
            throw new SAMException("Error reading from socket", e);
        } catch (Exception e) {
            throw new SAMException("Unexpected error", e);
        }

        // Message format: HELLO VERSION MIN=v1 MAX=v2
        if (tok.countTokens() != 4) {
            throw new SAMException("Bad format in HELLO message");
        }
        if (!tok.nextToken().equals("HELLO")) {
            throw new SAMException("Bad domain in HELLO message");
        }
        {
            String opcode;
            if (!(opcode = tok.nextToken()).equals("VERSION")) {
                throw new SAMException("Unrecognized HELLO message opcode: '"
                                       + opcode + "'");
            }
        }

        Properties props = SAMUtils.parseParams(tok);
        if (props.isEmpty()) {
            throw new SAMException("No parameters in HELLO VERSION message");
        }

        String minVer = props.getProperty("MIN");
        if (minVer == null) {
            throw new SAMException("Missing MIN parameter in HELLO VERSION message");
        }

        String maxVer = props.getProperty("MAX");
        if (maxVer == null) {
            //throw new SAMException("Missing MAX parameter in HELLO VERSION message");
            // MAX optional as of 0.9.14
            maxVer = "99.99";
        }

        String ver = chooseBestVersion(minVer, maxVer);

        if (ver == null) {
            SAMHandler.writeString("HELLO REPLY RESULT=NOVERSION\n", s);
            return null;
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
                handler = new SAMv1Handler(s, verMajor, verMinor, i2cpProps);
                break;
            case 2:
                handler = new SAMv2Handler(s, verMajor, verMinor, i2cpProps);
                break;
            case 3:
            	handler = new SAMv3Handler(s, verMajor, verMinor, i2cpProps);
            	break;
            default:
                log.error("BUG! Trying to initialize the wrong SAM version!");
                throw new SAMException("BUG! (in handler instantiation)");
            }
        } catch (IOException e) {
            log.error("Error creating the handler for version "+verMajor, e);
            throw new SAMException("IOException caught during SAM handler instantiation");
        }
        return handler;
    }

    /*
     * @return "x.y" the best version we can use, or null on failure
     */
    private static String chooseBestVersion(String minVer, String maxVer) {
        // in VersionComparator, "3" < "3.0" so
        // use comparisons carefully
        if (VersionComparator.comp("3.0", minVer) >= 0) {
            // Documentation said:
            // In order to force protocol version 3.0, the values of $min and $max
            // must be "3.0".
            int maxcomp = VersionComparator.comp("3", maxVer);
            if (maxcomp == 0 || maxVer.equals("3.0"))
                return "3.0";  // spoof version
            if (maxcomp < 0)
                return VERSION;
        }
        if (VersionComparator.comp("2.0", minVer) >= 0 &&
            VersionComparator.comp("2", maxVer) <= 0)
            return "2.0";
        if (VersionComparator.comp("1.0", minVer) >= 0 &&
            VersionComparator.comp("1", maxVer) <= 0)
            return "1.0";
        return null;
    }

    /* Get the major protocol version from a string */
    private static int getMajor(String ver) {
        if ( (ver == null) || (ver.indexOf('.') < 0) )
            return -1;
        try {
            String major = ver.substring(0, ver.indexOf("."));
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return -1;
        } catch (ArrayIndexOutOfBoundsException e) {
            return -1;
        }
    }

    /* Get the minor protocol version from a string */
    private static int getMinor(String ver) {
        if ( (ver == null) || (ver.indexOf('.') < 0) )
            return -1;
        try {
            String major = ver.substring(ver.indexOf(".") + 1);
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return -1;
        } catch (ArrayIndexOutOfBoundsException e) {
            return -1;
        }
    }
}
