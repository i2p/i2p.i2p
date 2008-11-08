package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.util.Log;

/** 
 * Fake ElG E and D, useful for when performance isn't being tested
 *
 * @author jrandom
 */
public class DummyElGamalEngine extends ElGamalEngine {
    private Log _log;

    /** 
     * The ElGamal engine should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public DummyElGamalEngine(I2PAppContext context) {
        super(context);
        _log = context.logManager().getLog(DummyElGamalEngine.class);
        _log.log(Log.CRIT, "Dummy ElGamal engine in use!  NO DATA SECURITY.  Danger Will Robinson, Danger!",
                 new Exception("I really hope you know what you're doing"));
    }
    private DummyElGamalEngine() { super(null); }
    
    /** encrypt the data to the public key
     * @return encrypted data
     * @param publicKey public key encrypt to
     * @param data data to encrypt
     */
    @Override
    public byte[] encrypt(byte data[], PublicKey publicKey) {
        if ((data == null) || (data.length >= 223))
            throw new IllegalArgumentException("Data to encrypt must be < 223 bytes at the moment");
        if (publicKey == null) throw new IllegalArgumentException("Null public key specified");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        try {
            baos.write(0xFF);
            Hash hash = SHA256Generator.getInstance().calculateHash(data);
            hash.writeBytes(baos);
            baos.write(data);
            baos.flush();
        } catch (Exception e) {
            _log.error("Internal error writing to buffer", e);
            return null;
        }
        byte d2[] = baos.toByteArray();
        byte[] out = new byte[514];
        System.arraycopy(d2, 0, out, (d2.length < 257 ? 257 - d2.length : 0), (d2.length > 257 ? 257 : d2.length));
        return out;
    }

    /** Decrypt the data
     * @param encrypted encrypted data
     * @param privateKey private key to decrypt with
     * @return unencrypted data
     */
    @Override
    public byte[] decrypt(byte encrypted[], PrivateKey privateKey) {
        if ((encrypted == null) || (encrypted.length > 514))
            throw new IllegalArgumentException("Data to decrypt must be <= 514 bytes at the moment");
        byte val[] = new byte[257];
        System.arraycopy(encrypted, 0, val, 0, val.length);
        int i = 0;
        for (i = 0; i < val.length; i++)
            if (val[i] != (byte) 0x00) break;
        ByteArrayInputStream bais = new ByteArrayInputStream(val, i, val.length - i);
        Hash hash = new Hash();
        byte rv[] = null;
        try {
            bais.read(); // skip first byte
            hash.readBytes(bais);
            rv = new byte[val.length - i - 1 - 32];
            bais.read(rv);
        } catch (Exception e) {
            _log.error("Internal error reading value", e);
            return null;
        }
        Hash calcHash = SHA256Generator.getInstance().calculateHash(rv);
        if (calcHash.equals(hash)) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Hash matches: " + DataHelper.toString(hash.getData(), hash.getData().length));
            return rv;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Doesn't match hash [calc=" + calcHash + " sent hash=" + hash + "]\ndata = " + new String(rv),
                       new Exception("Doesn't match"));
        return null;
    }
}