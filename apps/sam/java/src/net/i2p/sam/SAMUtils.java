package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.naming.NamingService;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
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
     * Get the Base64 representation of a Destination public key
     *
     * @param d A Destination
     *
     * @return A String representing the Destination public key
     */
    public static String getBase64DestinationPubKey(Destination d) {
	ByteArrayOutputStream baos = new ByteArrayOutputStream();

	try {
	    d.writeBytes(baos);
	    return Base64.encode(baos.toByteArray());
	} catch (IOException e) {
	    _log.error("getDestinationPubKey(): caught IOException", e);
	    return null;
	} catch (DataFormatException e) {
	    _log.error("getDestinationPubKey(): caught DataFormatException",e);
	    return null;
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
     * Resolved the specified hostname.
     *
     * @param name Hostname to be resolved
     * @param pubKey A stream to write the Destination public key (may be null)
     *
     * @return the Destination for the specified hostname, or null if not found
     */
    public static Destination lookupHost(String name, OutputStream pubKey) {
	NamingService ns = NamingService.getInstance();
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
     * Parse SAM parameters, and put them into a Propetries object
     *
     * @param tok A StringTokenizer pointing to the SAM parameters
     *
     * @return Properties with the parsed SAM params, or null if none is found
     */
    public static Properties parseParams(StringTokenizer tok) {
	int pos, nprops = 0, ntoks = tok.countTokens();
	String token, param, value;
	Properties props = new Properties();
	
	for (int i = 0; i < ntoks; ++i) {
	    token = tok.nextToken();

	    pos = token.indexOf("=");
	    if (pos == -1) {
		_log.debug("Error in params format");
		return null;
	    }
	    param = token.substring(0, pos);
	    value = token.substring(pos + 1);

	    props.setProperty(param, value);
	    nprops += 1;
	}

	if (_log.shouldLog(Log.DEBUG)) {
	    _log.debug("Parsed properties: " + dumpProperties(props));
	}

	if (nprops != 0) {
	    return props;
	} else {
	    return null;
	}
    }

    /* Dump a Properties object in an human-readable form */
    private static String dumpProperties(Properties props) {
	Enumeration enum = props.propertyNames();
	String msg = "";
	String key, val;
	boolean firstIter = true;
	
	while (enum.hasMoreElements()) {
	    key = (String)enum.nextElement();
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
}
