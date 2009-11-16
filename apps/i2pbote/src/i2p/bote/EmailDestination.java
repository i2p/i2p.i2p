package i2p.bote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Log;

/**
 * Uniquely identifies an email recipient. This implementation uses I2P keypairs.
 *
 * @author HungryHobo@mail.i2p
 */
public class EmailDestination {
    private Log log = new Log(EmailDestination.class);
    private PublicKey publicEncryptionKey;
    private SigningPublicKey publicSigningKey;
    
    /**
     * Creates a fresh <code>EmailDestination</code>.
     */
    public EmailDestination() {
        try {
            I2PClient i2pClient = I2PClientFactory.createClient();
            ByteArrayOutputStream arrayStream = new ByteArrayOutputStream();
            i2pClient.createDestination(arrayStream);
            byte[] destinationArray = arrayStream.toByteArray();
            I2PSession i2pSession = i2pClient.createSession(new ByteArrayInputStream(destinationArray), null);
            
            initKeys(i2pSession);
        }
        catch (Exception e) {
            log.error("Can't generate EmailDestination.", e);
        }
    }

    /**
     * Creates a <code>EmailDestination</code> using data read from a {@link ByteBuffer}.
     * @param buffer
     */
    public EmailDestination(ByteBuffer buffer) {
        byte[] encryptionKeyArray = new byte[PublicKey.KEYSIZE_BYTES];
        buffer.get(encryptionKeyArray);
        publicEncryptionKey = new PublicKey(encryptionKeyArray);
        
        byte[] signingKeyArray = new byte[SigningPublicKey.KEYSIZE_BYTES];
        buffer.get(signingKeyArray);
        publicSigningKey = new SigningPublicKey(signingKeyArray);
    }
    
    public EmailDestination(String base64Data) {
        try {
            base64Data += "AAAA";   // add a null certificate
            Destination i2pDestination = new Destination(base64Data);
            publicEncryptionKey = i2pDestination.getPublicKey();
            publicSigningKey = i2pDestination.getSigningPublicKey();
        } catch (DataFormatException e) {
            log.error("Can't generate EmailDestination.", e);
        }
    }
    
/*    public EmailDestination(byte[] data) {
        try {
            byte[] destinationPlusCert = addNullCertificate(data);
            ByteArrayInputStream byteStream = new ByteArrayInputStream(destinationPlusCert);
            I2PSession i2pSession = I2PClientFactory.createClient().createSession(byteStream, null);
            initKeys(i2pSession);
        }
        catch (I2PSessionException e) {
            log.error("Can't generate EmailDestination.", e);
        }
    }*/

/*    private byte[] addNullCertificate(byte[] data) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write(data);
            outputStream.write(new Certificate().toByteArray());
            // Add an extra zero byte so I2PSessionImpl.readDestination doesn't fall on its face. I believe this is a bug.
            outputStream.write(0);
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        return outputStream.toByteArray();
    }*/
    
    protected void initKeys(I2PSession i2pSession) {
        publicEncryptionKey = i2pSession.getMyDestination().getPublicKey();
        publicSigningKey = i2pSession.getMyDestination().getSigningPublicKey();
    }
    
    public PublicKey getPublicEncryptionKey() {
        return publicEncryptionKey;
    }

    public SigningPublicKey getPublicSigningKey() {
        return publicSigningKey;
    }
    
    private byte[] getKeysAsArray() {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try {
            writeTo(byteStream);
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        return byteStream.toByteArray();
    }
    
    private void writeTo(OutputStream outputStream) throws IOException {
        try {
            publicEncryptionKey.writeBytes(outputStream);
            publicSigningKey.writeBytes(outputStream);
        }
        catch (DataFormatException e) {
            log.error("Invalid encryption key or signing key.", e);
        }
    }
    
    public Hash getHash() {
        // TODO cache the hash value?
        return SHA256Generator.getInstance().calculateHash(getKeysAsArray());
    }
    
    /**
     * Returns the two public keys in Base64 representation.
     */
    public String getKey() {
        return Base64.encode(getKeysAsArray());
    }
    
    public String toBase64() {
        return getKey();
    }
    
    @Override
    public String toString() {
        return getKey();
    }
}