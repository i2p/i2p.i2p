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
 * <p>Turn some raw data into something we can pass down the tunnel, decrypting
 * and verifying along the way.  The encryption used is such that decryption
 * merely requires running over the data with AES in CTR mode, calculating the
 * SHA256 of a certain fixed portion of the message (bytes 16 through $size-288),
 * and searching for that hash in the checksum block.  There is a fixed number 
 * of hops defined (8 peers after the gateway) so that we can verify the message
 * without either leaking the position in the tunnel or having the message 
 * continually "shrink" as layers are peeled off.  For tunnels shorter than 9
 * hops, the tunnel creator will take the place of the excess hops, decrypting 
 * with their keys (for outbound tunnels, this is done at the beginning, and for
 * inbound tunnels, the end).</p>
 *
 * <p>The hard part in the encryption is building that entangled checksum block, 
 * which requires essentially finding out what the hash of the payload will look 
 * like at each step, randomly ordering those hashes, then building a matrix of 
 * what each of those randomly ordered hashes will look like at each step.  
 * To visualize this a bit:</p>
 *
 * <table border="1">
 *  <tr><td colspan="2"></td>
 *      <td><b>IV</b></td><td><b>Payload</b></td>
 *      <td><b>eH[0]</b></td><td><b>eH[1]</b></td>
 *      <td><b>eH[2]</b></td><td><b>eH[3]</b></td>
 *      <td><b>eH[4]</b></td><td><b>eH[5]</b></td>
 *      <td><b>eH[6]</b></td><td><b>eH[7]</b></td>
 *      <td><b>V</b></td>
 *      <td><b>Key</b></td>
 *  </tr>
 *  <tr><td rowspan="2"><b>peer0</b></td><td><b>recv</b></td>
 *      <td>IV[0]</td><td>P[0]</td>
 *      <td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td>
 *      <td>V[0]</td>
 *      <td rowspan="2">K[0]</td>
 *  </tr>
 *  <tr><td><b>send</b></td>
 *      <td rowspan="2">IV[1]</td><td rowspan="2">P[1]</td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2">H(P[1])</td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2">V[1]</td>
 *  </tr>
 *  <tr><td rowspan="2"><b>peer1</b></td><td><b>recv</b></td>
 *      <td rowspan="2">K[1]</td>
 *  </tr>
 *  <tr><td><b>send</b></td>
 *      <td rowspan="2">IV[2]</td><td rowspan="2">P[2]</td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2">H(P[2])</td><td rowspan="2"></td>
 *      <td rowspan="2">V[2]</td>
 *  </tr>
 *  <tr><td rowspan="2"><b>peer2</b></td><td><b>recv</b></td>
 *      <td rowspan="2">K[2]</td>
 *  </tr>
 *  <tr><td><b>send</b></td>
 *      <td rowspan="2">IV[3]</td><td rowspan="2">P[3]</td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2">H(P[3])</td>
 *      <td rowspan="2">V[3]</td>
 *  </tr>
 *  <tr><td rowspan="2"><b>peer3</b></td><td><b>recv</b></td>
 *      <td rowspan="2">K[3]</td>
 *  </tr>
 *  <tr><td><b>send</b></td>
 *      <td rowspan="2">IV[4]</td><td rowspan="2">P[4]</td>
 *      <td rowspan="2">H(P[4])</td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2">V[4]</td>
 *  </tr>
 *  <tr><td rowspan="2"><b>peer4</b></td><td><b>recv</b></td>
 *      <td rowspan="2">K[4]</td>
 *  </tr>
 *  <tr><td><b>send</b></td>
 *      <td rowspan="2">IV[5]</td><td rowspan="2">P[5]</td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2">H(P[5])</td><td rowspan="2"></td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2">V[5]</td>
 *  </tr>
 *  <tr><td rowspan="2"><b>peer5</b></td><td><b>recv</b></td>
 *      <td rowspan="2">K[5]</td>
 *  </tr>
 *  <tr><td><b>send</b></td>
 *      <td rowspan="2">IV[6]</td><td rowspan="2">P[6]</td>
 *      <td rowspan="2"></td><td rowspan="2">H(P[6])</td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2">V[6]</td>
 *  </tr>
 *  <tr><td rowspan="2"><b>peer6</b></td><td><b>recv</b></td>
 *      <td rowspan="2">K[6]</td>
 *  </tr>
 *  <tr><td><b>send</b></td>
 *      <td rowspan="2">IV[7]</td><td rowspan="2">P[7]</td>
 *      <td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2"></td><td rowspan="2">H(P[7])</td><td rowspan="2"></td><td rowspan="2"></td>
 *      <td rowspan="2">V[7]</td>
 *  </tr>
 *  <tr><td rowspan="2"><b>peer7</b></td><td><b>recv</b></td>
 *      <td rowspan="2">K[7]</td>
 *  </tr>
 *  <tr><td><b>send</b></td>
 *      <td>IV[8]</td><td>P[8]</td>
 *      <td></td><td></td><td></td><td></td><td>H(P[8])</td><td></td><td></td><td></td>
 *      <td>V[8]</td>
 *  </tr>
 * </table>
 *
 * <p>In the above, P[8] is the same as the original data being passed through the
 * tunnel, and V[8] is the SHA256 of eH[0-8] as seen on peer7 after decryption.  For
 * cells in the matrix "higher up" than the hash, their value is derived by encrypting
 * the cell below it with the key for the peer below it, using the end of the column 
 * to the left of it as the IV.  For cells in the matrix "lower down" than the hash, 
 * they're equal to the cell above them, decrypted by the current peer's key, using 
 * the end of the previous encrypted block on that row.</p>
 * 
 * <p>With this randomized matrix of checksum blocks, each peer will be able to find
 * the hash of the payload, or if it is not there, know that the message is corrupt.
 * The entanglement by using CTR mode increases the difficulty in tagging the 
 * checksum blocks themselves, but it is still possible for that tagging to go 
 * briefly undetected if the columns after the tagged data have already been used
 * to check the payload at a peer.  In any case, the tunnel endpoint (peer 7) knows
 * for certain whether any of the checksum blocks have been tagged, as that would
 * corrupt the verification block (V[8]).</p>
 *
 * <p>The IV[0] is a random 16 byte value, and IV[i] is the first 16 bytes of 
 * H(D(IV[i-1], K[i-1])).  We don't use the same IV along the path, as that would
 * allow trivial collusion, and we use the hash of the decrypted value to propogate 
 * the IV so as to hamper key leakage.</p>
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
    
    static final int HOPS = 8;
    static final int IV_SIZE = 16;
    private static final byte EMPTY[] = new byte[0];
    private static final int COLUMNS = HOPS;
    private static final int HASH_ROWS = HOPS + 1;
    
    public GatewayMessage(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(GatewayMessage.class);
        initialize();
    }
    
    private void initialize() {
        _iv = new byte[HOPS][IV_SIZE];
        _eIV = new byte[HOPS][IV_SIZE];
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
        
        for (int i = 0; i < HOPS - 1; i++) {
            SessionKey key = cfg.getSessionKey(i);
            
            // decrypt, since we're simulating what the participants do
            _context.aes().decryptBlock(_iv[i], 0, key, _iv[i+1], 0);
            Hash h = _context.sha().calculateHash(_iv[i+1]);
            System.arraycopy(h.getData(), 0, _iv[i+1], 0, IV_SIZE);
        }
        
        if (_log.shouldLog(Log.DEBUG)) {
            for (int i = 0; i < HOPS; i++)
                _log.debug("_iv[" + i + "] = " + Base64.encode(_iv[i]));
        }
    }
    
    /** 
     * Encrypt the payload and IV blocks, overwriting _iv and _payload, 
     * populating _H[] with the SHA256(payload) along the way and placing
     * the last 16 bytes of the encrypted payload into eIV[] at each step.
     *
     */
    private final void encryptPayload(GatewayTunnelConfig cfg) {
        int numBlocks = (_payload != null ? _payload.length / 16 : 0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("# payload blocks: " + numBlocks);
        
        for (int i = HOPS - 1; i >= 0; i--) {
            SessionKey key = cfg.getSessionKey(i);
            
            if ( (_payload != null) && (_payload.length > 0) ) {
                // set _H[i] = SHA256 of the payload seen at the i'th peer after decryption
                Hash h = _context.sha().calculateHash(_payload); 
                System.arraycopy(h.getData(), 0, _H[i], 0, Hash.HASH_LENGTH);
            
                // first block, use the IV
                DataHelper.xor(_iv[i], 0, _payload, 0, _payload, 0, IV_SIZE);
                _context.aes().encryptBlock(_payload, 0, key, _payload, 0);
                
                for (int j = 1; j < numBlocks; j++) {
                    // subsequent blocks, use the prev block as IV (aka CTR mode)
                    DataHelper.xor(_payload, (j-1)*IV_SIZE, _payload, j*IV_SIZE, _payload, j*IV_SIZE, IV_SIZE);
                    _context.aes().encryptBlock(_payload, j*IV_SIZE, key, _payload, j*IV_SIZE);
                }
                
                System.arraycopy(_payload, _payload.length - IV_SIZE, _eIV[i], 0, IV_SIZE);
            } else {
                Hash h = _context.sha().calculateHash(EMPTY); 
                System.arraycopy(h.getData(), 0, _H[i], 0, Hash.HASH_LENGTH);
                
                // nothing to encrypt... pass on the IV to the checksum blocks
                System.arraycopy(_iv, 0, _eIV[i], 0, IV_SIZE);
            }
        }

        if (_log.shouldLog(Log.DEBUG)) {
            for (int i = 0; i < HOPS; i++)
                _log.debug("_eIV["+ i + "] = " + Base64.encode(_eIV[i]));
            for (int i = 0; i < HOPS; i++)
                _log.debug("_H["+ i + "] = " + Base64.encode(_H[i]));
        }
    }
    
    /**
     * Fill in the _eH[column][step+1] matrix with the encrypted _H values so
     * that at each step, exactly one column will contain the _H value for
     * that step in the clear.  _eH[column][0] will contain what is sent from
     * the gateway to the first hop.  The encryption uses the _eIV for each 
     * step so that a plain AES/CTR decrypt of the entire message will expose
     * the layer.  _eH[column][_order[i]+1] == _H[_order[i]]
     */
    private final void encryptChecksumBlocks(GatewayTunnelConfig cfg) {
        for (int column = 0; column < COLUMNS; column++) {
            // which _H[hash] value are we rendering in this column?
            int hash = _order[column];
            // fill in the cleartext version for this column
            System.arraycopy(_H[hash], 0, _eH[column][hash+1], 0, Hash.HASH_LENGTH);
            
            // now fill in the "earlier" _eH[column][row] values for earlier hops 
            // by encrypting _eH[column][row+1] with the peer's key, using the end 
            // of the previous column (or _eIV[row]) as the IV
            for (int row = hash; row >= 0; row--) {
                SessionKey key = cfg.getSessionKey(row);
                // first half
                if (column == 0) {
                    DataHelper.xor(_eIV[row], 0, _eH[column][row+1], 0, _eH[column][row], 0, IV_SIZE);
                } else {
                    DataHelper.xor(_eH[column-1][row], IV_SIZE, _eH[column][row+1], 0, _eH[column][row], 0, IV_SIZE);
                }
                _context.aes().encryptBlock(_eH[column][row], 0, key, _eH[column][row], 0);
                
                // second half
                DataHelper.xor(_eH[column][row], 0, _eH[column][row+1], IV_SIZE, _eH[column][row], IV_SIZE, IV_SIZE);
                _context.aes().encryptBlock(_eH[column][row], IV_SIZE, key, _eH[column][row], IV_SIZE);
            }
            
            // fill in the "later" rows by encrypting the previous rows with the 
            // appropriate key, using the end of the previous column (or _eIV[row])
            // as the IV
            for (int row = hash + 1; row < HASH_ROWS; row++) {
                // row is the one we are *writing* to
                SessionKey key = cfg.getSessionKey(row-1);
                
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
     * Build the _V hash as the SHA256 of _eH[*][8], then encrypt it on top of
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
        
        for (int i = HOPS - 1; i >= 0; i--) {
            SessionKey key = cfg.getSessionKey(i);
            // xor the last block of the encrypted payload with the first block of _V to
            // continue the CTR operation
            DataHelper.xor(_V, 0, _eH[HOPS-1][i], IV_SIZE, _V, 0, IV_SIZE);
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
            boolean ok = DataHelper.eq(_eH[column][peer+1], 0, message, off, Hash.HASH_LENGTH);
            if (log.shouldLog(Log.DEBUG))
                log.debug("checksum[" + column + "][" + (peer+1) + "] matches?  " + ok);
            
            off += Hash.HASH_LENGTH;
            match = match && ok;
        }
        
        return match;
    }
}
