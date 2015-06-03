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
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.naming.NamingService;
import net.i2p.crypto.SigType;
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
class SAMUtils {

    //private final static Log _log = new Log(SAMUtils.class);

    /**
     * Generate a random destination key using DSA_SHA1 signature type.
     * Caller must close streams. Fails silently.
     *
     * @param priv Stream used to write the destination and private keys
     * @param pub Stream used to write the destination (may be null)
     */
    public static void genRandomKey(OutputStream priv, OutputStream pub) {
        genRandomKey(priv, pub, SigType.DSA_SHA1);
    }

    /**
     * Generate a random destination key.
     * Caller must close streams. Fails silently.
     *
     * @param priv Stream used to write the destination and private keys
     * @param pub Stream used to write the destination (may be null)
     * @param sigType what signature type
     * @since 0.9.14
     */
    public static void genRandomKey(OutputStream priv, OutputStream pub, SigType sigType) {
        //_log.debug("Generating random keys...");
        try {
            I2PClient c = I2PClientFactory.createClient();
            Destination d = c.createDestination(priv, sigType);
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

    /**
     * Check whether a base64-encoded {dest,privkey,signingprivkey} is valid
     *
     * @param dest The base64-encoded destination and keys to be checked (same format as PrivateKeyFile)
     * @return true if valid
     */
    public static boolean checkPrivateDestination(String dest) {
        byte[] b = Base64.decode(dest);
        if (b == null || b.length < 663)
            return false;
    	ByteArrayInputStream destKeyStream = new ByteArrayInputStream(b);
    	try {
    		Destination d = new Destination();
    		d.readBytes(destKeyStream);
    		new PrivateKey().readBytes(destKeyStream);
    		SigningPrivateKey spk = new SigningPrivateKey(d.getSigningPublicKey().getType());
    		spk.readBytes(destKeyStream);
    	} catch (DataFormatException e) {
                return false;
    	} catch (IOException e) {
                return false;
    	}
        return destKeyStream.available() == 0;
    }


    /**
     * Resolved the specified hostname.
     *
     * @param name Hostname to be resolved
     *
     * @return the Destination for the specified hostname, or null if not found
     */
    private static Destination lookupHost(String name) {
        NamingService ns = I2PAppContext.getGlobalContext().namingService();
        Destination dest = ns.lookup(name);
        return dest;
    }
    
    /**
     * Resolve the destination from a key or a hostname
     *
     * @param s Hostname or key to be resolved
     *
     * @return the Destination for the specified hostname, non-null
     * @throws DataFormatException on bad Base 64 or name not found
     */
    public static Destination getDest(String s) throws DataFormatException
    {
        // NamingService caches b64 so just use it for everything
        // TODO: Add a static local cache here so SAM doesn't flush the
        // NamingService cache
    	Destination d = lookupHost(s);
        if (d == null) {
            String msg;
            if (s.length() >= 516)
                msg = "Bad Base64 dest: ";
            else if (s.length() == 60 && s.endsWith(".b32.i2p"))
                msg = "Lease set not found: ";
            else
                msg = "Host name not found: ";
            throw new DataFormatException(msg + s);
        }
    	return d;
    }

    /**
     * Parse SAM parameters, and put them into a Propetries object
     *
     * @param tok A StringTokenizer pointing to the SAM parameters
     *
     * @throws SAMException if the data was formatted incorrectly
     * @return Properties with the parsed SAM params, never null
     */
    public static Properties parseParams(StringTokenizer tok) throws SAMException {
        int ntoks = tok.countTokens();
        Properties props = new Properties();
        
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < ntoks; ++i) {
            String token = tok.nextToken();

            int pos = token.indexOf("=");
            if (pos <= 0) {
                //_log.debug("Error in params format");
                if (pos == 0) {
                    throw new SAMException("No param specified [" + token + "]");
                } else {
                    throw new SAMException("Bad formatting for param [" + token + "]");
                }
            }
            
            String param = token.substring(0, pos);
            value.append(token.substring(pos+1));
            if (value.length() == 0)
                throw new SAMException("Empty value for param " + param);
            
            // FIXME: The following code does not take into account that there
            // may have been multiple subsequent space chars in the input that
            // StringTokenizer treates as one.
            if (value.charAt(0) == '"') {
                while ( (i < ntoks) && (value.lastIndexOf("\"") <= 0) ) {
                    value.append(' ').append(tok.nextToken());
                    i++;
                }
            }

            props.setProperty(param, value.toString());
            value.setLength(0);
        }

        //if (_log.shouldLog(Log.DEBUG)) {
        //    _log.debug("Parsed properties: " + dumpProperties(props));
        //}

        return props;
    }

    /* Dump a Properties object in an human-readable form */
/****
    private static String dumpProperties(Properties props) {
        StringBuilder builder = new StringBuilder();
        String key, val;
        boolean firstIter = true;
        
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            key = (String) entry.getKey();
            val = (String) entry.getValue();
            
            if (!firstIter) {
                builder.append(";");
            } else {
                firstIter = false;
            }
            builder.append(" \"" + key + "\" -> \"" + val + "\"");
        }
        
        return builder.toString();
    }
****/
    
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
