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

/**
 * SAM handler factory class.
 */
public class SAMHandlerFactory {

    private final static Log _log = new Log(SAMHandlerFactory.class);

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

        try {
            line = DataHelper.readLine(s.socket().getInputStream());
            if (line == null) {
                _log.debug("Connection closed by client");
                return null;
            }
            tok = new StringTokenizer(line.trim(), " ");
        } catch (IOException e) {
            throw new SAMException("Error reading from socket: "
                                   + e.getMessage());
        } catch (Exception e) {
            throw new SAMException("Unexpected error: " + e.getMessage());
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

        Properties props;
        props = SAMUtils.parseParams(tok);
        if (props == null) {
            throw new SAMException("No parameters in HELLO VERSION message");
        }

        String minVer = props.getProperty("MIN");
        if (minVer == null) {
            throw new SAMException("Missing MIN parameter in HELLO VERSION message");
        }

        String maxVer = props.getProperty("MAX");
        if (maxVer == null) {
            throw new SAMException("Missing MAX parameter in HELLO VERSION message");
        }

        String ver = chooseBestVersion(minVer, maxVer);

        try {
            if (ver == null) {
            	s.write(ByteBuffer.wrap(("HELLO REPLY RESULT=NOVERSION\n").getBytes("ISO-8859-1")));
            	return null ;
            }
            // Let's answer positively
            s.write(ByteBuffer.wrap(("HELLO REPLY RESULT=OK VERSION="
                       + ver + "\n").getBytes("ISO-8859-1")));
        } catch (UnsupportedEncodingException e) {
            _log.error("Caught UnsupportedEncodingException ("
                       + e.getMessage() + ")");
            throw new SAMException("Character encoding error: "
                                   + e.getMessage());
        } catch (IOException e) {
            throw new SAMException("Error writing to socket: "
                                   + e.getMessage());       
        }

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
                _log.error("BUG! Trying to initialize the wrong SAM version!");
                throw new SAMException("BUG! (in handler instantiation)");
            }
        } catch (IOException e) {
            _log.error("Error creating the handler for version "+verMajor, e);
            throw new SAMException("IOException caught during SAM handler instantiation");
        }
        return handler;
    }

    /* Return the best version we can use, or null on failure */
    private static String chooseBestVersion(String minVer, String maxVer) {
    	
        int minMajor = getMajor(minVer), minMinor = getMinor(minVer);
        int maxMajor = getMajor(maxVer), maxMinor = getMinor(maxVer);

        // Consistency checks
        if ((minMajor == -1) || (minMinor == -1)
            || (maxMajor == -1) || (maxMinor == -1)) {
            return null;
        }

	if ((minMinor >= 10) || (maxMinor >= 10)) return null ;
	
	float fminVer = (float) minMajor + (float) minMinor / 10 ;
	float fmaxVer = (float) maxMajor + (float) maxMinor / 10 ;
	

	if ( ( fminVer <=  3.0 ) && ( fmaxVer >= 3.0 ) ) return "3.0" ;

	if ( ( fminVer <=  2.0 ) && ( fmaxVer >= 2.0 ) ) return "2.0" ;
	
	if ( ( fminVer <=  1.0 ) && ( fmaxVer >= 1.0 ) ) return "1.0" ;
        
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
