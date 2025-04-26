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
import net.i2p.util.ByteArrayStream;
import net.i2p.util.HexDump;

/**
 * Class for creating and loading I2P repliable datagrams version 3.
 * Ref: Proposal 163
 *
 *<pre>
  +----+----+----+----+----+----+----+----+
  |                                       |
  +            fromHash                   +
  |                                       |
  +                                       +
  |                                       |
  +----+----+----+----+----+----+----+----+
  |  flags  |     options (optional)      |
  +----+----+                             +
  ~                                       ~
  ~                                       ~
  +----+----+----+----+----+----+----+----+
  |                                       |
  ~            payload                    ~
  ~                                       ~
  |                                       |
  +----+----+----+----+----+----+----+----+
 *</pre>
 *
 * @since 0.9.66
 */
public class Datagram3 {

    private final Hash _from;
    private final byte[] _payload;
    private final Properties _options;

    private static final int INIT_DGRAM_BUFSIZE = 2*1024;
    private static final int MIN_DGRAM_SIZE = 32 + 2;
    private static final int MAX_DGRAM_BUFSIZE = 61*1024;
    private static final byte VERSION_MASK = 0x0f;
    private static final byte OPTIONS = 0x10;
    private static final byte VERSION = 3;


    /**
     * As returned from load()
     */
    private Datagram3(Hash dest, byte[] data, Properties options) {
        _from = dest;
        _payload = data;
        _options = options;
    }

    /**
     * Make a repliable I2P datagram3 containing the specified payload.
     *
     * @param payload non-null Bytes to be contained in the I2P datagram.
     * @return non-null, throws on all errors
     * @throws DataFormatException if payload is too big
     */
    public static byte[] make(I2PAppContext ctx, I2PSession session, byte[] payload) throws DataFormatException {
        return make(ctx, session, payload, null);
    }

    /**
     * Make a repliable I2P datagram3 containing the specified payload.
     *
     * @param payload non-null Bytes to be contained in the I2P datagram.
     * @param options may be null
     * @return non-null, throws on all errors
     * @throws DataFormatException if payload is too big
     */
    public static byte[] make(I2PAppContext ctx, I2PSession session, byte[] payload,
                              Properties options) throws DataFormatException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 34);
        try {
            out.write(session.getMyDestination().calculateHash().getData());
            out.write((byte) 0); // high byte flags
            byte flags = VERSION;
            if (options != null && !options.isEmpty())
                flags |= OPTIONS;
            out.write(flags);    // low byte flags
            if (options != null && !options.isEmpty())
                DataHelper.writeProperties(out, options);
            out.write(payload);
            if (out.size() > MAX_DGRAM_BUFSIZE)
                throw new DataFormatException("Too big");
        } catch (IOException ioe) {}
        return out.toByteArray();
    }

    /**
     * Load an I2P repliable datagram3.
     *
     * @param dgram non-null I2P repliable datagram to be loaded
     * @return non-null, throws on all errors
     * @throws DataFormatException If there is an error in the datagram format
     */
    public static Datagram3 load(I2PAppContext ctx, I2PSession session, byte[] dgram) throws DataFormatException {
        if (dgram.length < MIN_DGRAM_SIZE)
            throw new DataFormatException("Datagram3 too small: " + dgram.length);
        ByteArrayInputStream in = new ByteArrayInputStream(dgram);
        try {
            Hash rxDest = Hash.create(in);
            in.read(); // ignore high byte of flags
            int flags = in.read();
            int version = flags & VERSION_MASK;
            if (version != VERSION)
                throw new DataFormatException("Bad version " + version);
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
            int datalen = dgram.length - (Hash.HASH_LENGTH + 2 + optlen);
            byte[] payload = new byte[datalen];
            System.arraycopy(dgram, Hash.HASH_LENGTH + 2 + optlen, payload, 0, datalen);
            return new Datagram3(rxDest, payload, options);
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
     * @return The Hash of the Destination of the I2P repliable datagram sender
     */
    public Hash getSender() {
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
        I2PClient cl = I2PClientFactory.createClient();
        ByteArrayStream bas1 = new ByteArrayStream(800);
        ByteArrayStream bas2 = new ByteArrayStream(800);
        // sess 1
        cl.createDestination(bas1, SigType.EdDSA_SHA512_Ed25519);
        // sess 2
        cl.createDestination(bas2, SigType.EdDSA_SHA512_Ed25519);

        Properties p = new Properties();
        I2PSession s1 = cl.createSession(bas1.asInputStream(), p);
        I2PSession s2 = cl.createSession(bas2.asInputStream(), p);
        Destination d1 = s1.getMyDestination();
        Destination d2 = s2.getMyDestination();

        Properties opts = new Properties();
        opts.setProperty("foooooooooooo", "bar");
        opts.setProperty("a", "b");

        byte[] data1 = new byte[1024];
        ctx.random().nextBytes(data1);
        byte[] dg1 = Datagram3.make(ctx, s1, data1, opts);
        Datagram3 datag = Datagram3.load(ctx, s2, dg1);
        byte[] data2 = datag.getPayload();
        Hash dr = datag.getSender();
        if (!dr.equals(d1.calculateHash())) {
            System.out.println("FAIL sender mismatch");
        } else if (!DataHelper.eq(data1, data2)) {
            System.out.println("FAIL data mismatch");
            System.out.println("Send Payload:\n" + HexDump.dump(data1));
            System.out.println("Rcv Payload:\n" + HexDump.dump(data2));
        } else {
            System.out.println("PASS");
        }
    }
*/
}
