package net.i2p.crypto;

import java.util.Arrays;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 * Calculate the HMAC-SHA256 of a key+message.
 *
 */
public class HMACSHA256Generator {
    private I2PAppContext _context;
    public HMACSHA256Generator(I2PAppContext context) {
        _context = context;
    }
    
    public static HMACSHA256Generator getInstance() {
        return I2PAppContext.getGlobalContext().hmac();
    }
    
    private static final int PAD_LENGTH = 64;
    
    private static final byte[] _IPAD = new byte[PAD_LENGTH];
    private static final byte[] _OPAD = new byte[PAD_LENGTH];
    static {
        for (int i = 0; i < _IPAD.length; i++) {
            _IPAD[i] = 0x36;
            _OPAD[i] = 0x5C;
        }
    }
    

    public Buffer createBuffer(int dataLen) { return new Buffer(dataLen); }
    
    public class Buffer {
        private byte padded[];
        private byte innerBuf[];
        private SHA256EntryCache.CacheEntry innerEntry;
        private byte rv[];
        private byte outerBuf[];
        private SHA256EntryCache.CacheEntry outerEntry;

        public Buffer(int dataLength) {
            padded = new byte[PAD_LENGTH];
            innerBuf = new byte[dataLength + PAD_LENGTH];
            innerEntry = _context.sha().cache().acquire(innerBuf.length);
            rv = new byte[Hash.HASH_LENGTH];
            outerBuf = new byte[Hash.HASH_LENGTH + PAD_LENGTH];
            outerEntry = _context.sha().cache().acquire(outerBuf.length);
        }
        
        public void releaseCached() {
            _context.sha().cache().release(innerEntry);
            _context.sha().cache().release(outerEntry);
        }
        
        public byte[] getHash() { return rv; }
    }
    
    /**
     * Calculate the HMAC of the data with the given key
     */
    public Hash calculate(SessionKey key, byte data[]) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");
        
        Buffer buf = new Buffer(data.length);
        calculate(key, data, buf);
        Hash rv = new Hash(buf.rv);
        buf.releaseCached();
        return rv;
    }
    
    /**
     * Calculate the HMAC of the data with the given key
     */
    public void calculate(SessionKey key, byte data[], Buffer buf) {
        // inner hash
        padKey(key.getData(), _IPAD, buf.padded);
        System.arraycopy(buf.padded, 0, buf.innerBuf, 0, PAD_LENGTH);
        System.arraycopy(data, 0, buf.innerBuf, PAD_LENGTH, data.length);
        
        Hash h = _context.sha().calculateHash(buf.innerBuf, buf.innerEntry);
        
        // outer hash
        padKey(key.getData(), _OPAD, buf.padded);
        System.arraycopy(buf.padded, 0, buf.outerBuf, 0, PAD_LENGTH);
        System.arraycopy(h.getData(), 0, buf.outerBuf, PAD_LENGTH, Hash.HASH_LENGTH);
        
        h = _context.sha().calculateHash(buf.outerBuf, buf.outerEntry);
        System.arraycopy(h.getData(), 0, buf.rv, 0, Hash.HASH_LENGTH);
    }
    
    private static final void padKey(byte key[], byte pad[], byte out[]) {
        for (int i = 0; i < SessionKey.KEYSIZE_BYTES; i++)
            out[i] = (byte) (key[i] ^ pad[i]);
        Arrays.fill(out, SessionKey.KEYSIZE_BYTES, PAD_LENGTH, pad[0]);
    }
}