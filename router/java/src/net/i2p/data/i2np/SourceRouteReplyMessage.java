package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.util.Log;

/**
 * Defines a message directed by a source route block to deliver a message to an
 * unknown location.
 *
 * @author jrandom
 */
public class SourceRouteReplyMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(SourceRouteReplyMessage.class);
    public final static int MESSAGE_TYPE = 13;
    private byte _encryptedHeader[];
    private I2NPMessage _message;
    private DeliveryInstructions _decryptedInstructions;
    private long _decryptedMessageId;
    private Certificate _decryptedCertificate;
    private long _decryptedExpiration;
    
    public SourceRouteReplyMessage() { 
	_encryptedHeader = null;
	_message = null;
	_decryptedInstructions = null;
	_decryptedMessageId = -1;
	_decryptedCertificate = null;
	_decryptedExpiration = -1;
    }
    
    /**
     * Retrieve the message being sent as a reply
     */
    public I2NPMessage getMessage() { return _message; }
    public void setMessage(I2NPMessage message) { _message = message; }
    
    public void setEncryptedHeader(byte header[]) { _encryptedHeader = header; }

    /**
     * After decryptHeader, this contains the delivery instructions for this block
     */
    public DeliveryInstructions getDecryptedInstructions() { return _decryptedInstructions; }
    /**
     * After decryptHeader, this contains the message ID to be used with this block
     */
    public long getDecryptedMessageId() { return _decryptedMessageId; }
    /**
     * After decryptHeader, this contains the Certificate 'paying' for the forwarding according to
     * this block
     */
    public Certificate getDecryptedCertificate() { return _decryptedCertificate; }
    /**
     * After decryptHeader, this contains the date after which this block should not be forwarded
     */
    public long getDecryptedExpiration() { return _decryptedExpiration; }
    
    /**
     * Decrypt the header and store it in the various getDecryptedXYZ() properties
     *
     * @throws DataFormatException if the decryption fails or if the data is somehow malformed
     */
    public void decryptHeader(PrivateKey key) throws DataFormatException {
	if ( (_encryptedHeader == null) || (_encryptedHeader.length <= 0) )
	    throw new DataFormatException("No header to decrypt");
	
	byte decr[] = ElGamalAESEngine.decrypt(_encryptedHeader, key);
	
	if (decr == null)
	    throw new DataFormatException("Decrypted data is null");
	
	try {
	    ByteArrayInputStream bais = new ByteArrayInputStream(decr);
    
	    _decryptedInstructions = new DeliveryInstructions();
	    _decryptedInstructions.readBytes(bais);
	    _decryptedMessageId = DataHelper.readLong(bais, 4);
	    _decryptedCertificate = new Certificate();
	    _decryptedCertificate.readBytes(bais);
	    _decryptedExpiration = DataHelper.readDate(bais).getTime();

	} catch (IOException ioe) {
	    throw new DataFormatException("Error reading the source route reply header", ioe);
	} catch (DataFormatException dfe) {
	    throw new DataFormatException("Error reading the source route reply header", dfe);
	}
    }
        
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
	if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
	    int headerSize = (int)DataHelper.readLong(in, 2);
	    _encryptedHeader = new byte[headerSize];
	    int read = read(in, _encryptedHeader);
	    if (read != headerSize)
		throw new DataFormatException("Not enough bytes to read the header (read = " + read + ", required = " + headerSize + ")");
	    _message = new I2NPMessageHandler().readMessage(in);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
	if ( (_encryptedHeader == null) || (_message == null) )
	    throw new I2NPMessageException("Not enough data to write out");
	
	ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
        try {
	    DataHelper.writeLong(os, 2, _encryptedHeader.length);
	    os.write(_encryptedHeader);
	    _message.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
	return DataHelper.hashCode(_encryptedHeader) +
	       DataHelper.hashCode(_message);
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof SourceRouteReplyMessage) ) {
            SourceRouteReplyMessage msg = (SourceRouteReplyMessage)object;
            return DataHelper.eq(_message,msg._message) && 
	           DataHelper.eq(_encryptedHeader,msg._encryptedHeader);
        } else {
            return false;
        }
    }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer();
        buf.append("[SourceRouteReplyMessage: ");
        buf.append("\n\tHeader: ").append(DataHelper.toString(_encryptedHeader, 64));
        buf.append("\n\tMessage: ").append(_message);
        buf.append("]");
        return buf.toString();
    }
}
