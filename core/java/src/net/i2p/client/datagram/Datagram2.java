package net.i2p.client.datagram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.ByteArrayStream;
import net.i2p.util.HexDump;

/**
 * Class for creating and loading I2P repliable datagrams version 2.
 * Ref: Proposal 163
 *
 *<pre>
  +----+----+----+----+----+----+----+----+
  |                                       |
  ~            from                       ~
  ~                                       ~
  |                                       |
  +----+----+----+----+----+----+----+----+
  |  flags  |     options (optional)      |
  +----+----+                             +
  ~                                       ~
  ~                                       ~
  +----+----+----+----+----+----+----+----+
  |                                       |
  ~     offline_signature (optional)      ~
  ~   expires, sigtype, pubkey, offsig    ~
  |                                       |
  +----+----+----+----+----+----+----+----+
  |                                       |
  ~            payload                    ~
  ~                                       ~
  |                                       |
  +----+----+----+----+----+----+----+----+
  |                                       |
  ~            signature                  ~
  ~                                       ~
  |                                       |
  +----+----+----+----+----+----+----+----+
 *</pre>
 *
 * @since 0.9.66
 */
public class Datagram2 {

    private final Destination _from;
    private final byte[] _payload;
    private final Properties _options;

    private static final int INIT_DGRAM_BUFSIZE = 2*1024;
    private static final int MIN_DGRAM_SIZE = 387 + 2 + 40;
    private static final int MAX_DGRAM_BUFSIZE = 61*1024;
    private static final byte VERSION_MASK = 0x0f;
    private static final byte OPTIONS = 0x10;
    private static final byte OFFLINE = 0x20;
    private static final byte VERSION = 2;


    /**
     * As returned from load()
     */
    private Datagram2(Destination dest, byte[] data, Properties options) {
        _from = dest;
        _payload = data;
        _options = options;
    }

    /**
     * Make a repliable I2P datagram2 containing the specified payload.
     *
     * @param payload non-null Bytes to be contained in the I2P datagram.
     * @return non-null, throws on all errors
     * @throws DataFormatException if payload is too big
     */
    public static byte[] make(I2PAppContext ctx, I2PSession session, byte[] payload, Hash tohash) throws DataFormatException {
        return make(ctx, session, payload, tohash, null);
    }

    /**
     * Make a repliable I2P datagram2 containing the specified payload.
     *
     * @param payload non-null Bytes to be contained in the I2P datagram.
     * @param options may be null
     * @return non-null, throws on all errors
     * @throws DataFormatException if payload is too big
     */
    public static byte[] make(I2PAppContext ctx, I2PSession session, byte[] payload,
                              Hash tohash, Properties options) throws DataFormatException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 512);
        try {
            Destination dest = session.getMyDestination();
            dest.writeBytes(out);
            // start of signed data
            int off = out.size();
            out.write(tohash.getData());
            out.write((byte) 0); // high byte flags
            byte flags = VERSION;
            if (options != null && !options.isEmpty())
                flags |= OPTIONS;
            if (session.isOffline())
                flags |= OFFLINE;
            out.write(flags);    // low byte flags
            if (options != null && !options.isEmpty())
                DataHelper.writeProperties(out, options);
            if (session.isOffline()) {
                DataHelper.writeLong(out, 4, session.getOfflineExpiration() / 1000);
                SigningPublicKey tspk = session.getTransientSigningPublicKey();
                DataHelper.writeLong(out, 2, tspk.getType().getCode());
                tspk.writeBytes(out);
                Signature tsig = session.getOfflineSignature();
                tsig.writeBytes(out);
            }
            out.write(payload);
            // end of signed data
            byte[] data = out.toByteArray();
            SigningPrivateKey sxPrivKey = session.getPrivateKey();
            Signature sig = ctx.dsa().sign(data, off, out.size() - off, sxPrivKey);
            if (sig == null)
                throw new IllegalArgumentException("Sig fail");
            sig.writeBytes(out);
            if (out.size() - Hash.HASH_LENGTH > MAX_DGRAM_BUFSIZE)
                throw new DataFormatException("Too big");
            byte[] rv = out.toByteArray();
            // remove hash
            System.arraycopy(rv, off + Hash.HASH_LENGTH, rv, off, rv.length - (off + Hash.HASH_LENGTH));
            return Arrays.copyOfRange(rv, 0, rv.length - Hash.HASH_LENGTH);
        } catch (IOException e) {
            throw new DataFormatException("DG2 maker error", e);
        }
    }

    /**
     * Load an I2P repliable datagram and verify the signature.
     *
     * @param dgram non-null I2P repliable datagram to be loaded
     * @return non-null, throws on all errors
     * @throws DataFormatException If there is an error in the datagram format
     * @throws I2PInvalidDatagramException If the signature fails
     */
    public static Datagram2 load(I2PAppContext ctx, I2PSession session, byte[] dgram) throws DataFormatException, I2PInvalidDatagramException {
        if (dgram.length < MIN_DGRAM_SIZE)
            throw new DataFormatException("Datagram2 too small: " + dgram.length);
        ByteArrayInputStream in = new ByteArrayInputStream(dgram);
        try {
            Destination rxDest = Destination.create(in);
            // start of signed data
            int off = dgram.length - in.available();
            in.read(); // ignore high byte of flags
            int flags = in.read();
            int version = flags & VERSION_MASK;
            if (version != VERSION)
                throw new DataFormatException("Bad version " + version);
            SigningPublicKey spk = rxDest.getSigningPublicKey();
            SigType type = spk.getType();
            if (type == null)
                throw new DataFormatException("unsupported sig type");
            int optlen = 0;
            Properties options = null;
            if ((flags & OPTIONS) != 0) {
                in.mark(0);
                optlen = (int) DataHelper.readLong(in, 2);
                if (optlen > 0) {
                    in.reset();
                    if (in.available() < optlen)
                        throw new DataFormatException("too small for options: " + dgram.length);
                    options = DataHelper.readProperties(in, null, true);
                }
                optlen += 2;
            }
            int offlinelen = 0;
            if ((flags & OFFLINE) != 0) {
                offlinelen = 6;
                int off2 = dgram.length - in.available();
                long transientExpires = DataHelper.readLong(in, 4) * 1000;
                if (transientExpires < ctx.clock().now())
                    throw new I2PInvalidDatagramException("Offline signature expired");
                int itype = (int) DataHelper.readLong(in, 2);
                SigType ttype = SigType.getByCode(itype);
                if (ttype == null || !ttype.isAvailable())
                    throw new I2PInvalidDatagramException("Unsupported transient sig type: " + itype);
                SigningPublicKey transientSigningPublicKey = new SigningPublicKey(type);
                byte[] buf = new byte[transientSigningPublicKey.length()];
                offlinelen += buf.length;
                in.read(buf);
                transientSigningPublicKey.setData(buf);
                SigType otype = rxDest.getSigningPublicKey().getType();
                Signature offlineSignature = new Signature(otype);
                buf = new byte[offlineSignature.length()];
                offlinelen += buf.length;
                in.read(buf);
                offlineSignature.setData(buf);
                byte[] data = new byte[0]; // fixme
                if (!ctx.dsa().verifySignature(offlineSignature, dgram, off2, 6 + transientSigningPublicKey.length(), spk))
                    throw new I2PInvalidDatagramException("Bad offline signature");
            }
            int siglen = type.getSigLen();
            in.skip(in.available() - siglen);
            // end of signed data
            Signature sig = new Signature(type);
            sig.readBytes(in);
            byte[] buf = new byte[dgram.length + Hash.HASH_LENGTH - (off + siglen)];
            System.arraycopy(session.getMyDestination().calculateHash().getData(), 0, buf, 0, Hash.HASH_LENGTH);
            System.arraycopy(dgram, off, buf, Hash.HASH_LENGTH, dgram.length - (off + siglen));
            if (!ctx.dsa().verifySignature(sig, buf, spk)) {
                throw new I2PInvalidDatagramException("Bad signature " + type);
            }
            if (offlinelen > 0)
                off += offlinelen;
            int datalen = dgram.length - (off + 2 + optlen + siglen);
            byte[] payload = new byte[datalen];
            System.arraycopy(dgram, off + 2 + optlen, payload, 0, datalen);
            return new Datagram2(rxDest, payload, options);
        } catch (IOException e) {
            throw new DataFormatException("Error loading datagram", e);
        }
    }
    
    /**
     * Get the payload carried by an I2P repliable datagram (previously loaded
     * with the load() method)
     *
     * @return A byte array containing the datagram payload
     */
    public byte[] getPayload() {
        return _payload;
    }
    
    /**
     * Get the sender of an I2P repliable datagram (previously loaded with the
     * load() method)
     *
     * @return The Destination of the I2P repliable datagram sender
     */
    public Destination getSender() {
        return _from;
    }
    
    /**
     * Get the options of an I2P repliable datagram (previously loaded with the
     * load() method), if any
     *
     * @return options or null
     */
    public Properties getOptions() {
        return _options;
    }

/*
    public static void main(String[] args) throws Exception {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        // 1 is normal dest, 2 is offline
        I2PClient cl = I2PClientFactory.createClient();
        ByteArrayStream bas1 = new ByteArrayStream(800);
        ByteArrayStream bas2 = new ByteArrayStream(800);
        // sess 1
        cl.createDestination(bas1, SigType.EdDSA_SHA512_Ed25519);
        // sess 2 offline
        cl.createDestination(bas2, SigType.EdDSA_SHA512_Ed25519);
        // sess 2 transient keys
        SimpleDataStructure[] tr = ctx.keyGenerator().generateSigningKeys(SigType.EdDSA_SHA512_Ed25519);
        SigningPublicKey tpub = (SigningPublicKey) tr[0];
        SigningPrivateKey tpriv = (SigningPrivateKey) tr[1];
        ByteArrayStream bas3 = new ByteArrayStream(800);
        byte[] b2 = bas2.toByteArray();
        int pklen = SigType.EdDSA_SHA512_Ed25519.getPrivkeyLen();
        int tocopy = b2.length - pklen;
        bas3.write(b2, 0, tocopy);
        // zero out privkey
        for (int i = 0; i < pklen; i++) {
             bas3.write((byte) 0);
        }
        byte[] oprivb = new byte[pklen];
        System.arraycopy(b2, tocopy, oprivb, 0, pklen);
        SigningPrivateKey opriv = new SigningPrivateKey(SigType.EdDSA_SHA512_Ed25519, oprivb);
        // offline section
        ByteArrayOutputStream baos = new ByteArrayOutputStream(70);
        // expires
        DataHelper.writeLong(bas3, 4, 0x7fffffff);
        DataHelper.writeLong(baos, 4, 0x7fffffff);
        // type
        DataHelper.writeLong(bas3, 2, SigType.EdDSA_SHA512_Ed25519.getCode());
        DataHelper.writeLong(baos, 2, SigType.EdDSA_SHA512_Ed25519.getCode());
        // transient pubkey
        byte[] tpubb = tpub.getData();
        bas3.write(tpubb);
        baos.write(tpubb);
        // sig
        Signature sig = ctx.dsa().sign(baos.toByteArray(), opriv);
        if (sig == null)
            throw new IllegalArgumentException("Sig fail");
        bas3.write(sig.getData());
        // transient privkey
        bas3.write(oprivb);

        Properties p = new Properties();
        I2PSession s1 = cl.createSession(bas1.asInputStream(), p);
        I2PSession s2 = cl.createSession(bas3.asInputStream(), p);
        Destination d1 = s1.getMyDestination();
        Destination d2 = s2.getMyDestination();

        Properties opts = new Properties();
        opts.setProperty("foooooooooooo", "bar");
        opts.setProperty("a", "b");

        // test 1: 1 to 2, normal
        byte[] data1 = new byte[1024];
        ctx.random().nextBytes(data1);
        byte[] dg1 = Datagram2.make(ctx, s1, data1, d2.calculateHash(), opts);
        Datagram2 datag = Datagram2.load(ctx, s2, dg1);
        byte[] data2 = datag.getPayload();
        Destination dr = datag.getSender();
        if (!dr.equals(d1)) {
            System.out.println("ONLINE FAIL sender mismatch");
        } else if (!DataHelper.eq(data1, data2)) {
            System.out.println("ONLINE FAIL data mismatch");
            System.out.println("Send Payload:\n" + HexDump.dump(data1));
            System.out.println("Rcv Payload:\n" + HexDump.dump(data2));
        } else {
            System.out.println("ONLINE PASS");
        }

        // test 2: 2 to 1, offline
        dg1 = Datagram2.make(ctx, s2, data1, d1.calculateHash(), opts);
        datag = Datagram2.load(ctx, s1, dg1);
        data2 = datag.getPayload();
        dr = datag.getSender();
        if (!dr.equals(d2)) {
            System.out.println("OFFLINE FAIL sender mismatch");
        } else if (!DataHelper.eq(data1, data2)) {
            System.out.println("OFFLINE FAIL data mismatch");
            System.out.println("Send Payload:\n" + HexDump.dump(data1));
            System.out.println("Rcv Payload:\n" + HexDump.dump(data2));
        } else {
            System.out.println("OFFLINE PASS");
        }
    }
*/
}
