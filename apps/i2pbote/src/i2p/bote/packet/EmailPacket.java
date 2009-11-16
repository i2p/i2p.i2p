package i2p.bote.packet;

import i2p.bote.EmailDestination;
import i2p.bote.EmailIdentity;
import i2p.bote.packet.dht.DhtStorablePacket;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import net.i2p.I2PAppContext;
import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

@TypeCode('E')
public class EmailPacket extends DhtStorablePacket {
    private static final int PADDED_SIZE = PrivateKey.KEYSIZE_BYTES;   // TODO is this a good choice?
    
    private ElGamalAESEngine cryptoEngine = I2PAppContext.getGlobalContext().elGamalAESEngine();
    private SessionKeyManager sessionKeyManager = I2PAppContext.getGlobalContext().sessionKeyManager();
    private Log log = new Log(EmailPacket.class);
    private Hash dhtKey;
    private UniqueId deletionKeyPlain;
    // Begin encrypted fields
    private UniqueId deletionKeyEncrypted;
    private UniqueId messageId;
	private int fragmentIndex;
	private int numFragments;
    private byte[] content;
    private byte[] encryptedData;   // all encrypted fields: Encrypted Deletion Key, Message ID, Fragment Index, Number of Fragments, and Content.

	/**
	 * Creates an <code>EmailPacket</code> from raw datagram data.
	 * To read the encrypted parts of the packet, <code>decrypt</code> must be called first.
     * @param data
	 */
	public EmailPacket(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.get() != getPacketTypeCode())
            log.error("Wrong type code for EmailPacket. Expected <" + getPacketTypeCode() + ">, got <" + (char)data[0] + ">");
        
        dhtKey = readHash(buffer);
        deletionKeyPlain = new UniqueId(buffer);
        
        int encryptedLength = buffer.getShort();   // length of the encrypted part of the packet
        encryptedData = new byte[encryptedLength];
        buffer.get(encryptedData);
	}
	
	/**
	 * Creates a <code>EmailPacket</code> from a <code>byte</code> array that contains MIME data.
	 * The public key of <code>emailDestination</code> is used for encryption.
	 * @param content
     * @param deletionKeyPlain
     * @param deletionKeyEncrypted
	 * @param messageId
	 * @param fragmentIndex
	 * @param numFragments
	 * @param emailDestination
	 */
	public EmailPacket(byte[] content, UniqueId deletionKeyPlain, UniqueId deletionKeyEncrypted, UniqueId messageId, int fragmentIndex, int numFragments, EmailDestination emailDestination) {
        this.deletionKeyPlain = deletionKeyPlain;
        this.deletionKeyEncrypted = deletionKeyEncrypted;
        this.messageId = messageId;
        this.fragmentIndex = fragmentIndex;
        this.numFragments = numFragments;
        this.content = content;
        
        dhtKey = generateRandomHash();
        
        // make an array with all data that gets encrypted
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(byteStream);
        try {
            deletionKeyEncrypted.writeTo(dataStream);
            messageId.writeTo(dataStream);
            dataStream.writeShort(fragmentIndex);
            dataStream.writeShort(numFragments);
            dataStream.writeShort(content.length);
            dataStream.write(content);
            
            encryptedData = encrypt(byteStream.toByteArray(), emailDestination);
            verify();
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream/DataOutputStream.", e);
        }
	}
	
	private Hash generateRandomHash() {
	    RandomSource randomSource = RandomSource.getInstance();
	    
	    byte[] bytes = new byte[Hash.HASH_LENGTH];
	    for (int i=0; i<bytes.length; i++)
	        bytes[i] = (byte)randomSource.nextInt(256);
	    
	    return new Hash(bytes);
	}
    
	/**
	 * Decrypts the encrypted part of the packet with the private key of <code>identity</code>.
	 * @param identity
	 * @throws DataFormatException 
	 */
	public void decrypt(EmailIdentity identity) throws DataFormatException {
	    byte[] decryptedData = decrypt(encryptedData, identity);
	    ByteBuffer buffer = ByteBuffer.wrap(decryptedData);
	    
        deletionKeyEncrypted = new UniqueId(buffer);
        messageId = new UniqueId(buffer);
        fragmentIndex = buffer.getShort();
        numFragments = buffer.getShort();
        
        int contentLength = buffer.getShort();
        content = new byte[contentLength];
        buffer.get(content);
        
        verify();
	}
	
    /**
     * Decrypts data with an email identity's private key.
     * @param data
     * @param identity
     * @return The decrypted data
     */
	private byte[] decrypt(byte[] data, EmailIdentity identity) throws DataFormatException {
        PrivateKey privateKey = identity.getPrivateEncryptionKey();
        return cryptoEngine.decrypt(data, privateKey);
	}
	
    /**
     * Encrypts data with an email destination's public key.
     * @param data
     * @param emailDestination
     * @return The encrypted data
     */
    public byte[] encrypt(byte[] data, EmailDestination emailDestination) {
        PublicKey publicKey = emailDestination.getPublicEncryptionKey();
        SessionKey sessionKey = sessionKeyManager.createSession(publicKey);
        return cryptoEngine.encrypt(data, publicKey, sessionKey, PADDED_SIZE);
    }
    
    @Override
    public Hash getDhtKey() {
        return dhtKey;
    }
	
    /**
     * Returns the value of the "plaintext deletion key" field.
     * Storage nodes set this to all zero bytes when the packet is retrieved.
     * @return
     */
    public UniqueId getPlaintextDeletionKey() {
        return deletionKeyPlain;
    }
    
    /**
     * Returns the value of the "encrypted deletion key" field.
     * Storage nodes set this to all zero bytes when the packet is retrieved.
     * @return
     */
    public UniqueId getEncryptedDeletionKey() {
        return deletionKeyEncrypted;
    }
    
	/**
     * Returns the index of the fragment in the {@link Email} this packet belongs to.
	 */
	public int getFragmentIndex() {
	    return fragmentIndex;
	}
	
	/**
	 * Returns the number of fragments in the {@link Email} this packet belongs to.
	 * @return
	 */
	public int getNumFragments() {
	    return numFragments;
	}
	
	public void setContent(byte[] content) {
		this.content = content;
	}
	
    public byte[] getContent() {
        return content;
    }
    
	@Override
	public byte[] toByteArray() {
	    ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream();
	    DataOutputStream dataStream = new DataOutputStream(byteArrayStream);

        try {
            dataStream.write((byte)getPacketTypeCode());
            dataStream.write(dhtKey.toByteArray());
            dataStream.write(deletionKeyPlain.toByteArray());
            dataStream.writeShort(encryptedData.length);
            dataStream.write(encryptedData);
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
		return byteArrayStream.toByteArray();
	}

	public void setNumFragments(int numFragments) {
	    this.numFragments = numFragments;
	}
	
	// Return the number of bytes in the packet.
	// TODO just return content.length+CONST so we don't call toByteArray every time
	public int getSize() {
	    return toByteArray().length;
	}
	
	public UniqueId getMessageId() {
	    return messageId;
	}
    
    private void verify() {
        if (fragmentIndex<0 || fragmentIndex>=numFragments || numFragments<1)
            log.error("Illegal values: fragmentIndex=" + fragmentIndex + " numFragments="+numFragments);
        // TODO more sanity checks?
    }
    
    @Override
    public String toString() {
        // TODO needs improvement
        String str = super.toString() + " fragIdx=" + fragmentIndex + " numFrags=" + numFragments;
        if (content == null)
            str += " content=null";
        else
            str += " content length=" + content.length + " bytes";
        return str;
    }
}