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
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.naming.NamingService;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

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
/****
    public static boolean checkDestination(String dest) {
        try {
            Destination d = new Destination();
            d.fromBase64(dest);

            return true;
        } catch (DataFormatException e) {
            return false;
        }
    }
****/

    /**
     * Check whether a base64-encoded {dest,privkey,signingprivkey[,offlinesig]} is valid
     *
     * This only checks that the length is correct. It does not validate
     * for pubkey/privkey match, or check the signatures.
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
            Destination d = Destination.create(destKeyStream);
            new PrivateKey().readBytes(destKeyStream);
            SigType dtype = d.getSigningPublicKey().getType();
            SigningPrivateKey spk = new SigningPrivateKey(dtype);
            spk.readBytes(destKeyStream);
            if (spk.isOffline()) {
                // offlineExpiration
                DataHelper.readLong(destKeyStream, 4);
                int itype = (int) DataHelper.readLong(destKeyStream, 2);
                SigType type = SigType.getByCode(itype);
                if (type == null)
                    return false;
                SigningPublicKey transientSigningPublicKey = new SigningPublicKey(type);
                transientSigningPublicKey.readBytes(destKeyStream);
                Signature offlineSignature = new Signature(dtype);
                offlineSignature.readBytes(destKeyStream);
                // replace spk
                spk = new SigningPrivateKey(type);
                spk.readBytes(destKeyStream);
            }
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
            else if (s.length() >= 60 && s.endsWith(".b32.i2p"))
                msg = "Lease set not found: ";
            else
                msg = "Host name not found: ";
            throw new DataFormatException(msg + s);
        }
    	return d;
    }

    public static final String COMMAND = "\"\"COMMAND\"\"";
    public static final String OPCODE = "\"\"OPCODE\"\"";

    /**
     *  Parse SAM parameters, and put them into a Propetries object
     *
     *  Modified from EepGet.
     *  COMMAND and OPCODE are mapped to upper case; keys, values, and ping data are not.
     *  Double quotes around values are stripped.
     *
     *  Possible input:
     *<pre>
     *  COMMAND
     *  COMMAND OPCODE
     *  COMMAND OPCODE [key=val]...
     *  COMMAND OPCODE [key=" val with spaces "]...
     *  PING
     *  PONG
     *  PING any   thing goes
     *  PONG any   thing   goes
     *
     *  Escaping is allowed with a backslash, e.g. \"
     *  No spaces before or after '=' allowed
     *  Keys may not be quoted
     *  COMMAND, OPCODE, and keys may not have '=' or whitespace unless escaped
     *  Duplicate keys not allowed
     *</pre>
     *
     *  A key without a value is not allowed by the spec, but is
     *  returned with the value "true".
     *
     *  COMMAND is returned as the value of the key ""COMMAND"".
     *  OPCODE, or the remainder of the PING/PONG line if any, is returned as the value of the key ""OPCODE"".
     *
     *  @param args non-null
     *  @throws SAMException on some errors but not all
     *  @return non-null, may be empty. Does not throw on missing COMMAND or OPCODE; caller must check.
     */
    public static Properties parseParams(String args) throws SAMException {
        final Properties rv = new Properties();
        final StringBuilder buf = new StringBuilder(32);
        final int length = args.length();
        boolean isQuoted = false;
        String key = null;
        // We go one past the end to force a fake trailing space
        // to make things easier, so we don't need cleanup at the end
        for (int i = 0; i <= length; i++) {
            char c = (i < length) ? args.charAt(i) : ' ';
            switch (c) {
                case '"':
                    if (isQuoted) {
                        // keys never quoted
                        if (key != null) {
                            if (rv.setProperty(key, buf.length() > 0 ? buf.toString() : "true") != null)
                                throw new SAMException("Duplicate parameter " + key);
                            key = null;
                        }
                        buf.setLength(0);
                    }
                    isQuoted = !isQuoted;
                    break;

                case '\r':
                case '\n':
                    break;

                case ' ':
                case '\b':
                case '\f':
                case '\t':
                    // whitespace - if we're in a quoted section, keep this as part of the quote,
                    // otherwise use it as a delim
                    if (isQuoted) {
                        buf.append(c);
                    } else {
                        if (key != null) {
                            if (rv.setProperty(key, buf.length() > 0 ? buf.toString() : "true") != null)
                                throw new SAMException("Duplicate parameter " + key);
                            key = null;
                        } else if (buf.length() > 0) {
                            // key without value
                            String k = buf.toString();
                            if (rv.isEmpty()) {
                                k =  k.toUpperCase(Locale.US);
                                rv.setProperty(COMMAND, k);
                                if (k.equals("PING") || k.equals("PONG")) {
                                    // eat the rest of the line
                                    if (i + 1 < args.length()) {
                                        String pingData = args.substring(i + 1);
                                        rv.setProperty(OPCODE, pingData);
                                    }
                                    // this will force an end of the loop
                                    i = length + 1;
                                }
                            } else if (rv.size() == 1) {
                                rv.setProperty(OPCODE, k.toUpperCase(Locale.US));
                            } else {
                                if (rv.setProperty(k, "true") != null)
                                    throw new SAMException("Duplicate parameter " + k);
                            }
                        }
                        buf.setLength(0);
                    }
                    break;

                case '=':
                    if (isQuoted) {
                        buf.append(c);
                    } else if (key != null) {
                        // '=' in a value
                        buf.append(c);
                    } else {
                        if (buf.length() == 0)
                            throw new SAMException("Empty parameter name");
                        key = buf.toString();
                        buf.setLength(0);
                    }
                    break;

                case '\\':
                    if (++i >= length)
                        throw new SAMException("Unterminated escape");
                    c = args.charAt(i);
                    // fall through...

                default:
                    buf.append(c);
                    break;
            }
        }
        // nothing needed here, as we forced a trailing space in the loop
        // unterminated quoted content will be lost
        if (isQuoted)
            throw new SAMException("Unterminated quote");
        return rv;
    }

/****
    public static void main(String args[]) {
        try {
            test("a=b c=d e=\"f g h\"");
            test("a=\"b c d\" e=\"f g h\" i=\"j\"");
            test("a=\"b c d\" e=f i=\"j\"");
            if (args.length == 0) {
                System.out.println("Usage: CommandParser file || CommandParser text to parse");
                return;
            }
            if (args.length > 1 || !(new java.io.File(args[0])).exists()) {
                StringBuilder buf = new StringBuilder(128);
                for (int i = 0; i < args.length; i++) {
                    if (i != 0)
                        buf.append(' ');
                    buf.append(args[i]);
                }
                test(buf.toString());
            } else {
                java.io.InputStream in = new java.io.FileInputStream(args[0]);
                String line;
                while ((line = net.i2p.data.DataHelper.readLine(in)) != null) {
                    try {
                        test(line);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void test(String props) throws Exception {
        System.out.println("Testing: " + props);
        Properties m = parseParams(props);
        System.out.println("Found " + m.size() + " keys");
        for (Map.Entry e : m.entrySet()) {
            System.out.println(e.getKey() + "=[" + e.getValue() + ']');
        }
        System.out.println("-------------");
    }
****/
}

