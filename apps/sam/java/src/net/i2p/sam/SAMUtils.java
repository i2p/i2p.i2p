package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.naming.NamingService;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.util.Log;

/**
 * Miscellaneous utility methods used by SAM protocol handlers.
 *
 * @author human
 */
public class SAMUtils {

    private final static Log _log = new Log(SAMUtils.class);

    /**
     * Generate a random destination key
     *
     * @param priv Stream used to write the private key
     * @param pub Stream used to write the public key (may be null)
     */
    public static void genRandomKey(OutputStream priv, OutputStream pub) {
        _log.debug("Generating random keys...");
        try {
            I2PClient c = I2PClientFactory.createClient();
            Destination d = c.createDestination(priv);
            priv.flush();

            if (pub != null) {
                d.writeBytes(pub);
                pub.flush();
            }
        } catch (I2PException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check whether a base64-encoded dest is valid
     *
     * @param dest The base64-encoded destination to be checked
     *
     * @return True if the destination is valid, false otherwise
     */
    public static boolean checkDestination(String dest) {
        try {
            Destination d = new Destination();
            d.fromBase64(dest);

            return true;
        } catch (DataFormatException e) {
            return false;
        }
    }
    
    public static class InvalidDestination extends Exception {
    	static final long serialVersionUID = 0x1 ;
    }
    public static void checkPrivateDestination(String dest) throws InvalidDestination {
    	ByteArrayInputStream destKeyStream = new ByteArrayInputStream(Base64.decode(dest));

    	try {
    		new Destination().readBytes(destKeyStream);
    		new PrivateKey().readBytes(destKeyStream);
    		new SigningPrivateKey().readBytes(destKeyStream);
    	} catch (Exception e) {
    		throw new InvalidDestination();
    	}
    }


    /**
     * Resolved the specified hostname.
     *
     * @param name Hostname to be resolved
     * @param pubKey A stream to write the Destination public key (may be null)
     *
     * @return the Destination for the specified hostname, or null if not found
     */
    public static Destination lookupHost(String name, OutputStream pubKey) {
        NamingService ns = I2PAppContext.getGlobalContext().namingService();
        Destination dest = ns.lookup(name);

        if ((pubKey != null) && (dest != null)) {
            try {
                dest.writeBytes(pubKey);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            } catch (DataFormatException e) {
                e.printStackTrace();
                return null;
            }
        }

        return dest;
    }
    
    /**
     * Resolve the destination from a key or a hostname
     *
     * @param s Hostname or key to be resolved
     *
     * @return the Destination for the specified hostname, or null if not found
     */
    public static Destination getDest(String s) throws DataFormatException
    {
    	Destination d = new Destination() ;
    	try {
    		d.fromBase64(s);
    	} catch (DataFormatException e) {
    		d = lookupHost(s, null);
    		if ( d==null ) {
    			throw e ;
    		}
    	}
    	return d ;
    }

    /**
     * Parse SAM parameters, and put them into a Propetries object
     *
     * @param tok A StringTokenizer pointing to the SAM parameters
     *
     * @throws SAMException if the data was formatted incorrectly
     * @return Properties with the parsed SAM params
     */
    public static Properties parseParams(StringTokenizer tok) throws SAMException {
        int pos, ntoks = tok.countTokens();
        String token, param;
        Properties props = new Properties();
        
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < ntoks; ++i) {
            token = tok.nextToken();

            pos = token.indexOf("=");
            if (pos == -1) {
                _log.debug("Error in params format");
                throw new SAMException("Bad formatting for param [" + token + "]");
            }
            param = token.substring(0, pos);
            value.append(token.substring(pos+1));
            if (value.charAt(0) == '"') {
                while ( (i < ntoks) && (value.lastIndexOf("\"") <= 0) ) {
                    value.append(' ').append(tok.nextToken());
                    i++;
                }
            }

            props.setProperty(param, value.toString());
            value.setLength(0);
        }

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Parsed properties: " + dumpProperties(props));
        }

        return props;
    }

    /* Dump a Properties object in an human-readable form */
    private static String dumpProperties(Properties props) {
        Enumeration names = props.propertyNames();
        String msg = "";
        String key, val;
        boolean firstIter = true;
        
        while (names.hasMoreElements()) {
            key = (String)names.nextElement();
            val = props.getProperty(key);
            
            if (!firstIter) {
                msg += ";";
            } else {
                firstIter = false;
            }
            msg += " \"" + key + "\" -> \"" + val + "\"";
        }
        
        return msg;
    }
    
/****
    public static void main(String args[]) {
        try {
            test("a=b c=d e=\"f g h\"");
            test("a=\"b c d\" e=\"f g h\" i=\"j\"");
            test("a=\"b c d\" e=f i=\"j\"");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static void test(String props) throws Exception {
        StringTokenizer tok = new StringTokenizer(props);
        Properties p = parseParams(tok);
        System.out.println(p);
    }
****/
}
