package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.Collections;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * <p>Manage the actual encryption necessary to turn a message into what the 
 * gateway needs to know so that it can send out a tunnel message.  See the doc
 * "tunnel.html" for more details on the algorithm and motivation.</p>
 * 
 */
public class GatewayMessage {
    private I2PAppContext _context;
    private Log _log;
    /** _iv[i] is the IV used to encrypt the entire message at peer i */
    private byte _iv[][];
    /** payload, overwritten with the encrypted data at each peer */
    private byte _payload[];
    /** _eIV[i] is the IV used to encrypt the checksum block at peer i */
    private byte _eIV[][];
    /** _H[i] is the SHA256 of the decrypted payload as seen at peer i */
    private byte _H[][];
    /** _order[i] is the column of the checksum block in which _H[i] will be placed */
    private int _order[];
    /** 
     * _eH[column][row] contains the (encrypted) hash of _H[_order[column]] as seen in the
     * column at peer 'row' BEFORE decryption.  when _order[column] == row+1, the hash is 
     * in the cleartext.
     */
    private byte _eH[][][];
    /** _preV is _eH[*][8] */
    private byte _preV[];
    /** 
     * _V is SHA256 of _preV, overwritten with its own encrypted value at each 
     * layer, using the last IV_SIZE bytes of _eH[7][*] as the IV
     */
    private byte _V[];
    /** if true, the data has been encrypted */
    private boolean _encrypted;
    /** if true, someone gave us a payload */
    private boolean _payloadSet;
    /** includes the gateway and endpoint */
    static final int HOPS = 8;
    /** aes256 */
    static final int IV_SIZE = 16;
    private static final byte EMPTY[] = new byte[0];
    private static final int COLUMNS = HOPS;
    private static final int HASH_ROWS = HOPS;
    
    /** used to munge the IV during per-hop translations */
    static final byte IV_WHITENER[] = new byte[] { (byte)0x31, (byte)0xd6, (byte)0x74, (byte)0x17, 
                                                   (byte)0xa0, (byte)0xb6, (byte)0x28, (byte)0xed, 
                                                   (byte)0xdf, (byte)0xee, (byte)0x5b, (byte)0x86, 
                                                   (byte)0x74, (byte)0x61, (byte)0x50, (byte)0x7d };
    
    public GatewayMessage(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(GatewayMessage.class);
        initialize();
    }
    
    private void initialize() {
        _iv = new byte[HOPS-1][IV_SIZE];
        _eIV = new byte[HOPS-1][IV_SIZE];
        _H = new byte[HOPS][Hash.HASH_LENGTH];
        _eH = new byte[COLUMNS][HASH_ROWS][Hash.HASH_LENGTH];
        _preV = new byte[HOPS*Hash.HASH_LENGTH];
        _V = new byte[Hash.HASH_LENGTH];
        _order = new int[HOPS];
        _encrypted = false;
        _payloadSet = false;
    }
    
    /**
     * Provide the data to be encrypted through the tunnel.  This data will
     * be available only to the tunnel endpoint as it is entered.  Its size
     * must be a multiple of 16 bytes (0 is a valid size).  The parameter is 
     * overwritten during encryption.
     *
     */
    public void setPayload(byte payload[]) { 
        if ( (payload != null) && (payload.length % 16 != 0) )
            throw new IllegalArgumentException("Payload size must be a multiple of 16");
        _payload = payload; 
        _payloadSet = true;
    }
    
    /** 
     * Actually encrypt the payload, producing the tunnel checksum that is verified 
     * at each hop as well as the verification block that verifies the checksum at 
     * the end of the tunnel.  After encrypting, the data can be retrieved by 
     * exporting it to a byte array.
     * 
     */
    public void encrypt(GatewayTunnelConfig config) {
        if (!_payloadSet) throw new IllegalStateException("You must set the payload before encrypting");
        buildOrder();
        encryptIV(config);
        encryptPayload(config);
        encryptChecksumBlocks(config);
        encryptVerificationHash(config);
        _encrypted = true;
    }
    
    /**
     * We want the hashes to be placed randomly throughout the checksum block so
     * that sometimes the first peer finds their hash in column 3, sometimes in
     * column 1, etc (and hence, doesn't know whether they are the first peer)
     *
     */
    private final void buildOrder() {
        ArrayList order = new ArrayList(HOPS);
        for (int i = 0; i < HOPS; i++)
            order.add(new Integer(i));
        Collections.shuffle(order, _context.random());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("_order = " + order);
        
        for (int i = 0; i < HOPS; i++) {
            _order[i] = ((Integer)order.get(i)).intValue();
        }
    }
    
    /**
     * The IV is a random value, encrypted backwards and hashed along the way to
     * destroy any keying material from colluding attackers.
     *
     */
    private final void encryptIV(GatewayTunnelConfig cfg) {
        _context.random().nextBytes(_iv[0]);
        
        for (int i = 1; i < HOPS - 1; i++) {
            SessionKey key = cfg.getSessionKey(i);
            
            // decrypt, since we're simulating what the participants do
            _context.aes().decryptBlock(_iv[i-1], 0, key, _iv[i], 0);
            DataHelper.xor(_iv[i], 0, IV_WHITENER, 0, _iv[i], 0, IV_SIZE);
            Hash h = _context.sha().calculateHash(_iv[i]);
            System.arraycopy(h.getData(), 0, _iv[i], 0, IV_SIZE);
        }
        
        if (_log.shouldLog(Log.DEBUG)) {
            for (int i = 0; i < HOPS-1; i++)
                _log.debug("_iv[" + i + "] = " + Base64.encode(_iv[i]));
        }
    }
    
    /** 
     * Encrypt the payload and IV blocks, overwriting the _payload, 
     * populating _H[] with the SHA256(payload) along the way and placing
     * the last 16 bytes of the encrypted payload into eIV[] at each step.
     *
     */
    private final void encryptPayload(GatewayTunnelConfig cfg) {
        int numBlocks = (_payload != null ? _payload.length / 16 : 0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("# payload blocks: " + numBlocks);
        
        for (int i = HOPS - 1; i > 0; i--) {
            SessionKey key = cfg.getSessionKey(i);
            
            if ( (_payload != null) && (_payload.length > 0) ) {
                // set _H[i] = SHA256 of the payload seen at the i'th peer after decryption
                Hash h = _context.sha().calculateHash(_payload); 
                System.arraycopy(h.getData(), 0, _H[i], 0, Hash.HASH_LENGTH);
            
                // first block, use the IV
                DataHelper.xor(_iv[i-1], 0, _payload, 0, _payload, 0, IV_SIZE);
                _context.aes().encryptBlock(_payload, 0, key, _payload, 0);
                
                for (int j = 1; j < numBlocks; j++) {
                    // subsequent blocks, use the prev block as IV (aka CTR mode)
                    DataHelper.xor(_payload, (j-1)*IV_SIZE, _payload, j*IV_SIZE, _payload, j*IV_SIZE, IV_SIZE);
                    _context.aes().encryptBlock(_payload, j*IV_SIZE, key, _payload, j*IV_SIZE);
                }
                
                System.arraycopy(_payload, _payload.length - IV_SIZE, _eIV[i-1], 0, IV_SIZE);
            } else {
                Hash h = _context.sha().calculateHash(EMPTY); 
                System.arraycopy(h.getData(), 0, _H[i], 0, Hash.HASH_LENGTH);
                
                // nothing to encrypt... pass on the IV to the checksum blocks
                System.arraycopy(_iv, 0, _eIV[i-1], 0, IV_SIZE);
            }
        }
        
        // we need to know what the gateway would "decrypt" to, even though they
        // aren't actually doing any decrypting (so the first peer can't look 
        // back and say "hey, the previous peer had no match, they must be the
        // gateway")
        Hash h0 = null;
        if ( (_payload != null) && (_payload.length > 0) )
            h0 = _context.sha().calculateHash(_payload);
        else
            h0 = _context.sha().calculateHash(EMPTY);
        System.arraycopy(h0.getData(), 0, _H[0], 0, Hash.HASH_LENGTH);

        if (_log.shouldLog(Log.DEBUG)) {
            for (int i = 0; i < HOPS-1; i++)
                _log.debug("_eIV["+ i + "] = " + Base64.encode(_eIV[i]));
            for (int i = 0; i < HOPS; i++)
                _log.debug("_H["+ i + "] = " + Base64.encode(_H[i]));
        }
    }
    
    /**
     * Fill in the _eH[column][step] matrix with the encrypted _H values so
     * that at each step, exactly one column will contain the _H value for
     * that step in the clear.  _eH[column][0] will contain what is sent from
     * the gateway (peer0) to the first hop (peer1).  The encryption uses the _eIV for each 
     * step so that a plain AES/CTR decrypt of the entire message will expose
     * the layer.  The columns are ordered according to _order
     */
    private final void encryptChecksumBlocks(GatewayTunnelConfig cfg) {
        for (int column = 0; column < COLUMNS; column++) {
            // which _H[hash] value are we rendering in this column?
            int hash = _order[column];
            // fill in the cleartext version for this column
            System.arraycopy(_H[hash], 0, _eH[column][hash], 0, Hash.HASH_LENGTH);
            
            // now fill in the "earlier" _eH[column][row-1] values for earlier hops 
            // by encrypting _eH[column][row] with the peer's key, using the end 
            // of the previous column (or _eIV[row-1]) as the IV
            for (int row = hash; row > 0; row--) {
                SessionKey key = cfg.getSessionKey(row);
                // first half
                if (column == 0) {
                    DataHelper.xor(_eIV[row-1], 0, _eH[column][row], 0, _eH[column][row-1], 0, IV_SIZE);
                } else {
                    DataHelper.xor(_eH[column-1][row-1], IV_SIZE, _eH[column][row], 0, _eH[column][row-1], 0, IV_SIZE);
                }
                _context.aes().encryptBlock(_eH[column][row-1], 0, key, _eH[column][row-1], 0);
                
                // second half
                DataHelper.xor(_eH[column][row-1], 0, _eH[column][row], IV_SIZE, _eH[column][row-1], IV_SIZE, IV_SIZE);
                _context.aes().encryptBlock(_eH[column][row-1], IV_SIZE, key, _eH[column][row-1], IV_SIZE);
            }
            
            // fill in the "later" rows by encrypting the previous rows with the 
            // appropriate key, using the end of the previous column (or _eIV[row])
            // as the IV
            for (int row = hash + 1; row < HASH_ROWS; row++) {
                // row is the one we are *writing* to
                SessionKey key = cfg.getSessionKey(row);
                
                _context.aes().decryptBlock(_eH[column][row-1], 0, key, _eH[column][row], 0);
                if (column == 0)
                    DataHelper.xor(_eIV[row-1], 0, _eH[column][row], 0, _eH[column][row], 0, IV_SIZE);
                else
                    DataHelper.xor(_eH[column-1][row-1], IV_SIZE, _eH[column][row], 0, _eH[column][row], 0, IV_SIZE);
                
                _context.aes().decryptBlock(_eH[column][row-1], IV_SIZE, key, _eH[column][row], IV_SIZE);
                DataHelper.xor(_eH[column][row-1], 0, _eH[column][row], IV_SIZE, _eH[column][row], IV_SIZE, IV_SIZE);
            }
        }
        
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("_eH[column][peer]");
            for (int peer = 0; peer < HASH_ROWS; peer++) {
                for (int column = 0; column < COLUMNS; column++) {
                    try {
                        _log.debug("_eH[" + column + "][" + peer + "] = " + Base64.encode(_eH[column][peer]) 
                                   + (peer == 0 ? "" : DataHelper.eq(_H[peer-1], _eH[column][peer]) ? " CLEARTEXT" : ""));
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("column="+column + " peer=" + peer);
                    }
                }
            }
        }
    }
    
    /**
     * Build the _V hash as the SHA256 of _eH[*][7], then encrypt it on top of
     * itself using the last 16 bytes of _eH[7][*] as the IV.
     *
     */
    private final void encryptVerificationHash(GatewayTunnelConfig cfg) {
        for (int i = 0; i < COLUMNS; i++)
            System.arraycopy(_eH[i][HASH_ROWS-1], 0, _preV, i * Hash.HASH_LENGTH, Hash.HASH_LENGTH);
        Hash v = _context.sha().calculateHash(_preV);
        System.arraycopy(v.getData(), 0, _V, 0, Hash.HASH_LENGTH);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("_V final = " + Base64.encode(_V));
        
        for (int i = HOPS - 1; i > 0; i--) {
            SessionKey key = cfg.getSessionKey(i);
            // xor the last block of the encrypted payload with the first block of _V to
            // continue the CTR operation
            DataHelper.xor(_V, 0, _eH[COLUMNS-1][i-1], IV_SIZE, _V, 0, IV_SIZE);
            _context.aes().encryptBlock(_V, 0, key, _V, 0);
            DataHelper.xor(_V, 0, _V, IV_SIZE, _V, IV_SIZE, IV_SIZE);
            _context.aes().encryptBlock(_V, IV_SIZE, key, _V, IV_SIZE);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("_V at peer " + i + " = " + Base64.encode(_V));
        }
        
        // _V now contains the hash of what the checksum blocks look like on the 
        // endpoint, encrypted for delivery to the first hop
    }
    
    /**
     * Calculate the total size of the encrypted tunnel message that needs to be
     * sent to the first hop.  This includes the IV and checksum/verification hashes.
     *
     */
    public final int getExportedSize() { 
        return IV_SIZE +
               _payload.length +
               COLUMNS * Hash.HASH_LENGTH +
               Hash.HASH_LENGTH; // verification hash
    }
    
    /**
     * Write out the fully encrypted tunnel message to the target
     * (starting with what peer1 should see)
     *
     * @param target array to write to, which must be large enough 
     * @param offset offset into the array to start writing
     * @return offset into the array after writing
     * @throws IllegalStateException if it is not yet encrypted
     * @throws NullPointerException if the target is null 
     * @throws IllegalArgumentException if the target is too small
     */
    public final int export(byte target[], int offset) {
        if (!_encrypted) throw new IllegalStateException("Not yet encrypted - please call encrypt");
        if (target == null) throw new NullPointerException("Target is null");
        if (target.length - offset < getExportedSize()) throw new IllegalArgumentException("target is too small");
        
        int cur = offset;
        System.arraycopy(_iv[0], 0, target, cur, IV_SIZE);
        cur += IV_SIZE;
        System.arraycopy(_payload, 0, target, cur, _payload.length);
        cur += _payload.length;
        for (int column = 0; column < COLUMNS; column++) {
            System.arraycopy(_eH[column][0], 0, target, cur, Hash.HASH_LENGTH);
            cur += Hash.HASH_LENGTH;
        }
        System.arraycopy(_V, 0, target, cur, Hash.HASH_LENGTH);
        cur += Hash.HASH_LENGTH;
        return cur;
    }
    
    /**
     * Verify that the checksum block is as it should be (per _eH[*][peer+1]) 
     * when presented with what the peer has decrypted.  Useful for debugging
     * only.
     */
    public final boolean compareChecksumBlock(I2PAppContext ctx, byte message[], int peer) {
        Log log = ctx.logManager().getLog(GatewayMessage.class);
        boolean match = true;
        
        int off = message.length - (COLUMNS + 1) * Hash.HASH_LENGTH;
        for (int column = 0; column < COLUMNS; column++) {
            boolean ok = DataHelper.eq(_eH[column][peer], 0, message, off, Hash.HASH_LENGTH);
            if (log.shouldLog(Log.DEBUG))
                log.debug("checksum[" + column + "][" + (peer) + "] matches?  " + ok);
            
            off += Hash.HASH_LENGTH;
            match = match && ok;
        }
        
        return match;
    }
}
