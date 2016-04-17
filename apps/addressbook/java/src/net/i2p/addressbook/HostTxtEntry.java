package net.i2p.addressbook;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;

import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.OrderedProperties;
// for testing only
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.SigningPrivateKey;


/**
 * A hostname, b64 destination, and optional properties.
 * 
 * @since 0.9.26
 */
class HostTxtEntry {

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
        this.name = name;
        this.dest = dest;
        this.props = parseProps(sprops);
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
    private OrderedProperties parseProps(String line) throws IllegalArgumentException {
        line = line.trim();
        OrderedProperties rv = new OrderedProperties();
        String[] entries = DataHelper.split(line, "#");
        for (int i = 0; i < entries.length; i++) {
            String kv = entries[i];
            int eq = kv.indexOf("=");
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

    public void write(BufferedWriter out) throws IOException {
        out.write(name);
        out.write(KV_SEPARATOR);
        out.write(dest);
        if (props != null && props.size() > 0) {
            boolean started = false;
            for (Map.Entry<Object, Object> e : props.entrySet()) {
                if (started) {
                    out.write(PROP_SEPARATOR);
                } else {
                    started = true;
                    out.write(PROPS_SEPARATOR);
                }
                String k = (String) e.getKey();
                String v = (String) e.getValue();
                out.write(k);
                out.write(KV_SEPARATOR);
                out.write(v);
            }
        }
        out.newLine();
    }

    public boolean hasValidSig() {
        if (props == null)
            return false;
        if (!isValidated) {
            isValidated = true;
            StringBuilder buf = new StringBuilder(1024);
            String sig = null;
            buf.append(name);
            buf.append(KV_SEPARATOR);
            buf.append(dest);
            boolean started = false;
            for (Map.Entry<Object, Object> e : props.entrySet()) {
                String k = (String) e.getKey();
                String v = (String) e.getValue();
                if (k.equals(PROP_SIG)) {
                    if (sig != null)
                        return false;
                    sig = v;
                    // remove from the written data
                    continue;
                }
                if (started) {
                    buf.append(PROP_SEPARATOR);
                } else {
                    started = true;
                    buf.append(PROPS_SEPARATOR);
                }
                buf.append(k);
                buf.append(KV_SEPARATOR);
                buf.append(v);
            }
            if (sig == null)
                return false;
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

    /** for testing only */
    private void sign(SigningPrivateKey spk) {
        if (props == null)
            throw new IllegalStateException();
        Destination d;
        try {
            d = new Destination(dest);
        } catch (DataFormatException dfe) {
            throw new IllegalStateException("bah", dfe);
        }
        StringBuilder buf = new StringBuilder(1024);
        buf.append(name);
        buf.append(KV_SEPARATOR);
        buf.append(dest);
        boolean started = false;
        for (Map.Entry<Object, Object> e : props.entrySet()) {
            String k = (String) e.getKey();
            String v = (String) e.getValue();
            if (k.equals(PROP_SIG))
                throw new IllegalStateException();
            if (started) {
                buf.append(PROP_SEPARATOR);
            } else {
                started = true;
                buf.append(PROPS_SEPARATOR);
            }
            buf.append(k);
            buf.append(KV_SEPARATOR);
            buf.append(v);
        }
        Signature s = DSAEngine.getInstance().sign(DataHelper.getUTF8(buf.toString()), spk);
        if (s == null)
            throw new IllegalArgumentException("sig failed");
        props.setProperty(PROP_SIG, s.toBase64());
    }

    public static void main(String[] args) throws Exception {
        File f = new File("tmp-eepPriv.dat");
        PrivateKeyFile pkf = new PrivateKeyFile(f);
        pkf.createIfAbsent(SigType.EdDSA_SHA512_Ed25519);
        OrderedProperties props = new OrderedProperties();
        props.setProperty("c", "ccccccccccc");
        props.setProperty("a", "aaaa");
        HostTxtEntry he = new HostTxtEntry("foo.i2p", pkf.getDestination().toBase64(), props);
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out));
        out.write("Before signing:\n");
        he.write(out);
        out.flush();
        SigningPrivateKey priv = pkf.getSigningPrivKey();
        he.sign(priv);
        out.write("After signing:\n");
        he.write(out);
        out.flush();
        System.out.println("Orig has valid sig? " + he.hasValidSig());
        // now create 2nd, read in
        StringWriter sw = new StringWriter(1024);
        BufferedWriter buf = new BufferedWriter(sw);
        he.write(buf);
        buf.flush();
        String line = sw.toString();
        line = line.substring(line.indexOf(PROPS_SEPARATOR) + 2);
        HostTxtEntry he2 = new HostTxtEntry("foo.i2p", pkf.getDestination().toBase64(), line);
        System.out.println("Dupl. has valid sig? " + he2.hasValidSig());
        f.delete();
    }
}
