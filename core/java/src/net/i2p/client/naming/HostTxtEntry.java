package net.i2p.client.naming;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.OrderedProperties;
// for testing only
import java.io.File;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import net.i2p.data.Base32;
import net.i2p.data.PrivateKeyFile;
import net.i2p.util.RandomSource;


/**
 * A hostname, b64 destination, and optional properties.
 * Includes methods to sign and verify the entry.
 * Used by addressbook to parse subscription data,
 * and by i2ptunnel to generate signed metadata.
 * 
 * @since 0.9.26
 */
public class HostTxtEntry {

    private final String name;
    private final String dest;
    private final OrderedProperties props;
    private boolean isValidated;
    private boolean isValid;

    public static final char KV_SEPARATOR = '=';
    public static final String PROPS_SEPARATOR = "#!";
    public static final char PROP_SEPARATOR = '#';
    public static final String PROP_ACTION = "action";
    public static final String PROP_DATE = "date";
    public static final String PROP_DEST = "dest";
    public static final String PROP_EXPIRES = "expires";
    public static final String PROP_NAME = "name";
    public static final String PROP_OLDDEST = "olddest";
    public static final String PROP_OLDNAME = "oldname";
    public static final String PROP_OLDSIG = "oldsig";
    public static final String PROP_SIG = "sig";
    public static final String ACTION_ADDDEST = "adddest";
    public static final String ACTION_ADDNAME = "addname";
    public static final String ACTION_ADDSUBDOMAIN = "addsubdomain";
    public static final String ACTION_CHANGEDEST = "changedest";
    public static final String ACTION_CHANGENAME = "changename";
    public static final String ACTION_REMOVE = "remove";
    public static final String ACTION_REMOVEALL = "removeall";
    public static final String ACTION_UPDATE = "update";

    /**
     * Properties will be null
     */
    public HostTxtEntry(String name, String dest) {
        this(name, dest, (OrderedProperties) null);
    }

    /**
     * @param sprops line part after the #!, non-null
     * @throws IllegalArgumentException on dup key in sprops and other errors
     */
    public HostTxtEntry(String name, String dest, String sprops) throws IllegalArgumentException {
        this(name, dest, parseProps(sprops));
    }

    /**
     * A 'remove' entry. Name and Dest will be null.
     * @param sprops line part after the #!, non-null
     * @throws IllegalArgumentException on dup key in sprops and other errors
     */
    public HostTxtEntry(String sprops) throws IllegalArgumentException {
        this(null, null, parseProps(sprops));
    }

    /**
     * @param props may be null
     */
    public HostTxtEntry(String name, String dest, OrderedProperties props) {
        this.name = name;
        this.dest = dest;
        this.props = props;
    }

    public String getName() {
        return name;
    }

    public String getDest() {
        return dest;
    }

    public OrderedProperties getProps() {
        return props;
    }

    /**
     * @param line part after the #!
     * @throws IllegalArgumentException on dup key and other errors
     */
    private static OrderedProperties parseProps(String line) throws IllegalArgumentException {
        line = line.trim();
        OrderedProperties rv = new OrderedProperties();
        String[] entries = DataHelper.split(line, "#");
        for (int i = 0; i < entries.length; i++) {
            String kv = entries[i];
            int eq = kv.indexOf('=');
            if (eq <= 0 || eq == kv.length() - 1)
                throw new IllegalArgumentException("No value: \"" + kv + '"');
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            Object old = rv.setProperty(k, v);
            if (old != null)
                throw new IllegalArgumentException("Dup key: " + k);
        }
        return rv;
    }

    /**
     * Write as a standard line name=dest[#!k1=v1#k2=v2...]
     * Includes newline.
     */
    public void write(BufferedWriter out) throws IOException {
        write((Writer) out);
        out.newLine();
    }

    /**
     * Write as a standard line name=dest[#!k1=v1#k2=v2...]
     * Does not include newline.
     */
    public void write(Writer out) throws IOException {
        if (name != null && dest != null) {
            out.write(name);
            out.write(KV_SEPARATOR);
            out.write(dest);
        }
        writeProps(out);
    }

    /**
     * Write as a "remove" line #!dest=dest#name=name#k1=v1#sig=sig...]
     * This works whether constructed with name and dest, or just properties.
     * Includes newline.
     * Must have been constructed with non-null properties.
     */
    public void writeRemoveLine(BufferedWriter out) throws IOException {
        writeRemove(out);
        out.newLine();
    }

    /**
     * Write as a "remove" line #!dest=dest#name=name#k1=v1#sig=sig...]
     * This works whether constructed with name and dest, or just properties.
     * Does not include newline.
     * Must have been constructed with non-null properties.
     */
    public void writeRemove(Writer out) throws IOException {
        if (props == null)
            throw new IllegalStateException();
        if (name != null && dest != null) {
            props.setProperty(PROP_NAME, name);
            props.setProperty(PROP_DEST, dest);
        }
        writeProps(out);
        if (name != null && dest != null) {
            props.remove(PROP_NAME);
            props.remove(PROP_DEST);
        }
    }

    /**
     * Write the props part (if any) only, without newline
     */
    public void writeProps(Writer out) throws IOException {
        writeProps(out, false, false);
    }

    /**
     * Write the props part (if any) only, without newline
     */
    private void writeProps(Writer out, boolean omitSig, boolean omitOldSig) throws IOException {
        if (props == null)
            return;
        boolean started = false;
        for (Map.Entry<Object, Object> e : props.entrySet()) {
            String k = (String) e.getKey();
            if (omitSig && k.equals(PROP_SIG))
                continue;
            if (omitOldSig && k.equals(PROP_OLDSIG))
                continue;
            if (started) {
                out.write(PROP_SEPARATOR);
            } else {
                started = true;
                out.write(PROPS_SEPARATOR);
            }
            String v = (String) e.getValue();
            out.write(k);
            out.write(KV_SEPARATOR);
            out.write(v);
        }
    }

    /**
     * Verify with the dest public key using the "sig" property
     */
    public boolean hasValidSig() {
        if (props == null || name == null || dest == null)
            return false;
        if (!isValidated) {
            isValidated = true;
            StringWriter buf = new StringWriter(1024);
            String sig = props.getProperty(PROP_SIG);
            if (sig == null)
                return false;
            buf.append(name);
            buf.append(KV_SEPARATOR);
            buf.append(dest);
            try {
                writeProps(buf, true, false);
            } catch (IOException ioe) {
                // won't happen
                return false;
            }
            byte[] sdata = Base64.decode(sig);
            if (sdata == null)
                return false;
            Destination d;
            try {
                d = new Destination(dest);
            } catch (DataFormatException dfe) {
                return false;
            }
            SigningPublicKey spk = d.getSigningPublicKey();
            SigType type = spk.getType();
            if (type == null)
                return false;
            Signature s;
            try {
                s = new Signature(type, sdata);
            } catch (IllegalArgumentException iae) {
                return false;
            }
            isValid = DSAEngine.getInstance().verifySignature(s, DataHelper.getUTF8(buf.toString()), spk);
        }
        return isValid;
    }

    /**
     * Verify with the "olddest" property's public key using the "oldsig" property
     */
    public boolean hasValidInnerSig() {
        if (props == null || name == null || dest == null)
            return false;
        boolean rv = false;
        // don't cache result
        if (true) {
            StringWriter buf = new StringWriter(1024);
            String sig = props.getProperty(PROP_OLDSIG);
            String olddest = props.getProperty(PROP_OLDDEST);
            if (sig == null || olddest == null)
                return false;
            buf.append(name);
            buf.append(KV_SEPARATOR);
            buf.append(dest);
            try {
                writeProps(buf, true, true);
            } catch (IOException ioe) {
                // won't happen
                return false;
            }
            byte[] sdata = Base64.decode(sig);
            if (sdata == null)
                return false;
            Destination d;
            try {
                d = new Destination(olddest);
            } catch (DataFormatException dfe) {
                return false;
            }
            SigningPublicKey spk = d.getSigningPublicKey();
            SigType type = spk.getType();
            if (type == null)
                return false;
            Signature s;
            try {
                s = new Signature(type, sdata);
            } catch (IllegalArgumentException iae) {
                return false;
            }
            rv = DSAEngine.getInstance().verifySignature(s, DataHelper.getUTF8(buf.toString()), spk);
        }
        return rv;
    }

    /**
     * Verify with the "dest" property's public key using the "sig" property
     */
    public boolean hasValidRemoveSig() {
        if (props == null)
            return false;
        boolean rv = false;
        // don't cache result
        if (true) {
            StringWriter buf = new StringWriter(1024);
            String sig = props.getProperty(PROP_SIG);
            String olddest = props.getProperty(PROP_DEST);
            if (sig == null || olddest == null)
                return false;
            try {
                writeProps(buf, true, true);
            } catch (IOException ioe) {
                // won't happen
                return false;
            }
            byte[] sdata = Base64.decode(sig);
            if (sdata == null)
                return false;
            Destination d;
            try {
                d = new Destination(olddest);
            } catch (DataFormatException dfe) {
                return false;
            }
            SigningPublicKey spk = d.getSigningPublicKey();
            SigType type = spk.getType();
            if (type == null)
                return false;
            Signature s;
            try {
                s = new Signature(type, sdata);
            } catch (IllegalArgumentException iae) {
                return false;
            }
            rv = DSAEngine.getInstance().verifySignature(s, DataHelper.getUTF8(buf.toString()), spk);
        }
        return rv;
    }

    @Override
    public int hashCode() {
        return dest.hashCode();
    }

    /**
     *  Compares Destination only, not properties
     */
    @Override
    public boolean equals(Object o) {
        if (o == this)
              return true;
        if (!(o instanceof HostTxtEntry))
              return false;
        HostTxtEntry he = (HostTxtEntry) o;
        return dest.equals(he.getDest());
    }

    /**
     * Sign and set the "sig" property
     * Must have been constructed with non-null properties.
     */
    public void sign(SigningPrivateKey spk) {
        signIt(spk, PROP_SIG);
    }

    /**
     * Sign and set the "oldsig" property
     * Must have been constructed with non-null properties.
     */
    public void signInner(SigningPrivateKey spk) {
        signIt(spk, PROP_OLDSIG);
    }

    /**
     * Sign as a "remove" line #!dest=dest#name=name#k1=v1#sig=sig...]
     * Must have been constructed with non-null properties.
     */
    public void signRemove(SigningPrivateKey spk) {
        if (props == null)
            throw new IllegalStateException();
        if (props.containsKey(PROP_SIG))
            throw new IllegalStateException();
        props.setProperty(PROP_NAME, name);
        props.setProperty(PROP_DEST, dest);
        if (!props.containsKey(PROP_DATE))
            props.setProperty(PROP_DATE, Long.toString(System.currentTimeMillis() / 1000));
        StringWriter buf = new StringWriter(1024);
        try {
            writeProps(buf);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        props.remove(PROP_NAME);
        props.remove(PROP_DEST);
        Signature s = DSAEngine.getInstance().sign(DataHelper.getUTF8(buf.toString()), spk);
        if (s == null)
            throw new IllegalArgumentException("sig failed");
        props.setProperty(PROP_SIG, s.toBase64());
    }

    /**
     * @param sigprop The signature property to set
     */
    private void signIt(SigningPrivateKey spk, String sigprop) {
        if (props == null)
            throw new IllegalStateException();
        if (props.containsKey(sigprop))
            throw new IllegalStateException();
        if (!props.containsKey(PROP_DATE))
            props.setProperty(PROP_DATE, Long.toString(System.currentTimeMillis() / 1000));
        StringWriter buf = new StringWriter(1024);
        buf.append(name);
        buf.append(KV_SEPARATOR);
        buf.append(dest);
        try {
            writeProps(buf);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        Signature s = DSAEngine.getInstance().sign(DataHelper.getUTF8(buf.toString()), spk);
        if (s == null)
            throw new IllegalArgumentException("sig failed");
        props.setProperty(sigprop, s.toBase64());
    }

    /**
     *  Usage: HostTxtEntry [-i] [-x] [hostname.i2p] [key=val]...
     */
/****
    public static void main(String[] args) throws Exception {
        boolean inner = false;
        boolean remove = false;
        if (args.length > 0 && args[0].equals("-i")) {
            inner = true;
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        if (args.length > 0 && args[0].equals("-x")) {
            remove = true;
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        String host;
        if (args.length > 0 && args[0].endsWith(".i2p")) {
            host = args[0];
            args = Arrays.copyOfRange(args, 1, args.length);
        } else {
            byte[] rand = new byte[5];
            RandomSource.getInstance().nextBytes(rand);
            host = Base32.encode(rand) + ".i2p";
        }
        OrderedProperties props = new OrderedProperties();
        for (int i = 0; i < args.length; i++) {
            int eq = args[i].indexOf("=");
            props.setProperty(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        props.setProperty("zzzz", "zzzzzzzzzzzzzzz");
        // outer
        File f = new File("tmp-eepPriv.dat");
        PrivateKeyFile pkf = new PrivateKeyFile(f);
        pkf.createIfAbsent(SigType.EdDSA_SHA512_Ed25519);
        //f.delete();
        PrivateKeyFile pkf2;
        if (inner) {
            // inner
            File f2 = new File("tmp-eepPriv2.dat");
            pkf2 = new PrivateKeyFile(f2);
            pkf2.createIfAbsent(SigType.DSA_SHA1);
            //f2.delete();
            props.setProperty(PROP_OLDDEST, pkf2.getDestination().toBase64());
        } else {
            pkf2 = null;
        }
        HostTxtEntry he = new HostTxtEntry(host, pkf.getDestination().toBase64(), props);
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out));
        //out.write("Before signing:\n");
        //he.write(out);
        //out.flush();
        SigningPrivateKey priv = pkf.getSigningPrivKey();
        StringWriter sw = new StringWriter(1024);
        BufferedWriter buf = new BufferedWriter(sw);
        if (!remove) {
            if (inner) {
                SigningPrivateKey priv2 = pkf2.getSigningPrivKey();
                he.signInner(priv2);
                //out.write("After signing inner:\n");
                //he.write(out);
            }
            he.sign(priv);
            //out.write("After signing:\n");
            he.write(out);
            out.flush();
            if (inner && !he.hasValidInnerSig())
                throw new IllegalStateException("Inner fail 1");
            if (!he.hasValidSig())
                throw new IllegalStateException("Outer fail 1");
            // now create 2nd, read in
            he.write(buf);
            buf.flush();
            String line = sw.toString();
            line = line.substring(line.indexOf(PROPS_SEPARATOR) + 2);
            HostTxtEntry he2 = new HostTxtEntry(host, pkf.getDestination().toBase64(), line);
            if (inner && !he2.hasValidInnerSig())
                throw new IllegalStateException("Inner fail 2");
            if (!he2.hasValidSig())
                throw new IllegalStateException("Outer fail 2");
        } else {
            // 'remove' tests (corrupts earlier sigs)
            he.getProps().remove(PROP_SIG);
            he.signRemove(priv);
            //out.write("Remove entry:\n");
            sw = new StringWriter(1024);
            buf = new BufferedWriter(sw);
            he.writeRemoveLine(buf);
            buf.flush();
            out.write(sw.toString());
            out.flush();
            String line = sw.toString().substring(2).trim();
            HostTxtEntry he3 = new HostTxtEntry(line);
            if (!he3.hasValidRemoveSig())
                throw new IllegalStateException("Remove verify fail");
        }

        //out.write("Test passed\n");
        //out.flush();
    }
****/
}
