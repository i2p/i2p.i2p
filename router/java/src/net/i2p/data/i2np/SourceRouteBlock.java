package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.util.Log;
import net.i2p.I2PAppContext;


/**
 * Defines a single hop of a source routed message, as usable for building a
 * SourceRouteReplyMessage 
 *
 * @author jrandom
 */
public class SourceRouteBlock extends DataStructureImpl {
    private final static Log _log = new Log(SourceRouteBlock.class);
    private Hash _router;
    private byte[] _data;
    private SessionKey _key;
    private byte[] _tag;
    private DeliveryInstructions _decryptedInstructions;
    private long _decryptedMessageId;
    private Certificate _decryptedCertificate;
    private long _decryptedExpiration;
    
    public SourceRouteBlock() { 
        setRouter(null);
        setData(null);
        setKey(null);
        setTag((byte[])null);
        _decryptedInstructions = null;
        _decryptedMessageId = -1;
        _decryptedCertificate = null;
        _decryptedExpiration = -1;
    }
    
    /**
     * Get the router through which replies using this source route block must 
     * be sent (as the getData() is encrypted for their eyes only)
     *
     */
    public Hash getRouter() { return _router; }
    public void setRouter(Hash router) { _router= router; }
    /**
     * Get the encrypted header.  After decryption (via ElGamal+AES as defined
     * in the data structures spec), this array contains:
     *  DeliveryInstructions
     *  4 byte Integer for a message ID
     *  Certificate
     *  Date of expiration for replies
     *
     */
    public byte[] getData() { return _data; }
    private void setData(byte data[]) { _data = data; }
    /**
     * Retrieve the session key which may be used in conjunction with the tag 
     * to encrypt a garlic message and send it as a reply to this message.
     * The encryption would follow scenario 2 of the ElGamal+AES encryption method 
     * defined in the data structures spec.  
     *
     */
    public SessionKey getKey() { return _key; }
    public void setKey(SessionKey key) { _key = key; }
    /**
     * Get the tag made available for use in conjunction with the getKey() to
     * ElGamal+AES encrypt a garlic message without knowing the public key to
     * which the message is destined
     *
     */
    public byte[] getTag() { return _tag; }
    public void setTag(SessionTag tag) { setTag(tag.getData()); }
    public void setTag(byte tag[]) {
        if ( (tag != null) && (tag.length != SessionTag.BYTE_LENGTH) )
            throw new IllegalArgumentException("Tag must be either null or 32 bytes");
        _tag = tag;
    }

    /**
     * After decryptData, this contains the delivery instructions for this block
     */
    public DeliveryInstructions getDecryptedInstructions() { return _decryptedInstructions; }
    /**
     * After decryptData, this contains the message ID to be used with this block
     */
    public long getDecryptedMessageId() { return _decryptedMessageId; }
    /**
     * After decryptData, this contains the Certificate 'paying' for the forwarding according to
     * this block
     */
    public Certificate getDecryptedCertificate() { return _decryptedCertificate; }
    /**
     * After decryptData, this contains the date after which this block should not be forwarded
     */
    public long getDecryptedExpiration() { return _decryptedExpiration; }
    
    /**
     * Set the raw data with the formatted and encrypted options specified
     *
     * @param instructions Where a message bearing this block should be sent
     * @param messageId    ID of the message for this block (not repeatable)
     * @param expiration   date after which this block expires
     * @param replyThrough Encryption key of the router to whom this block is specified (not
     *                     the router specified in the delivery instructions!)
     * 
     * @throws DataFormatException if the data is invalid or could not be encrypted
     */
    public void setData(I2PAppContext ctx, DeliveryInstructions instructions, 
                        long messageId, Certificate cert, long expiration, 
                        PublicKey replyThrough) throws DataFormatException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64);

            _decryptedInstructions = instructions;
            _decryptedMessageId = messageId;
            _decryptedCertificate = cert;
            _decryptedExpiration = expiration;

            instructions.writeBytes(baos);
            DataHelper.writeLong(baos, 4, messageId);
            cert.writeBytes(baos);
            DataHelper.writeDate(baos, new Date(expiration));

            int paddedSize = 256;
            SessionKey sessKey = null;
            SessionTag tag = null;
            if (instructions.getDelayRequested()) {
                // always use a new key if we're delaying, since the reply block may not be used within the 
                // window of a session
                sessKey = ctx.keyGenerator().generateSessionKey();
                tag = null;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Delay requested - creating a new session key");
            } else {
                sessKey = ctx.sessionKeyManager().getCurrentKey(replyThrough);
                if (sessKey == null) { 
                    sessKey = ctx.keyGenerator().generateSessionKey();
                    tag = null;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("No delay requested, but no session key is known");
                } else {
                    tag = ctx.sessionKeyManager().consumeNextAvailableTag(replyThrough, sessKey);
                }
            }
            byte encData[] = ctx.elGamalAESEngine().encrypt(baos.toByteArray(), replyThrough, 
                                                            sessKey, null, tag, paddedSize);
            setData(encData);
        } catch (IOException ioe) {
            throw new DataFormatException("Error writing out the source route block data", ioe);
        } catch (DataFormatException dfe) {
            throw new DataFormatException("Error writing out the source route block data", dfe);
        }
    }
    
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _router = new Hash();
        _router.readBytes(in);
        int size = (int)DataHelper.readLong(in, 2);
        _data = new byte[size];
        int read = read(in, _data);
        if (read != _data.length)
            throw new DataFormatException("Incorrect # of bytes read for source route block: " + read);
        _key = new SessionKey();
        _key.readBytes(in);
        _tag = new byte[32];
        read = read(in, _tag);
        if (read != _tag.length)
            throw new DataFormatException("Incorrect # of bytes read for session tag: " + read);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ( (_router == null) || (_data == null) || (_key == null) || (_tag == null) || (_tag.length != 32) ) 
            throw new DataFormatException("Insufficient data to write");
        _router.writeBytes(out);
        DataHelper.writeLong(out, 2, _data.length);
        out.write(_data);
        _key.writeBytes(out);
        out.write(_tag);
    }
    
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof SourceRouteBlock))
            return false;
        SourceRouteBlock block = (SourceRouteBlock)obj;
        return DataHelper.eq(getRouter(), block.getRouter()) &&
               DataHelper.eq(getData(), block.getData()) &&
               DataHelper.eq(getKey(), block.getKey()) &&
               DataHelper.eq(getTag(), block.getTag());
    }
    
    public int hashCode() {
        return DataHelper.hashCode(getRouter()) + 
               DataHelper.hashCode(getData()) +
               DataHelper.hashCode(getKey()) +
               DataHelper.hashCode(getTag());
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("[SourceRouteBlock: ");
        buf.append("\n\tRouter: ").append(getRouter());
        buf.append("\n\tData: ").append(DataHelper.toString(getData(), getData().length));
        buf.append("\n\tTag: ").append(DataHelper.toString(getTag(), (getTag() != null ? getTag().length : 0)));
        buf.append("\n\tKey: ").append(getKey());
        buf.append("]");
        return buf.toString();
    }
}
