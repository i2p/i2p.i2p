package i2p.bote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Base64;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.util.Log;

public class EmailIdentity extends EmailDestination {
    private Log log = new Log(EmailIdentity.class);
    private PrivateKey privateEncryptionKey;
    private SigningPrivateKey privateSigningKey;
    private String publicName;
    private String description;   // optional
    private String emailAddress;   // optional

    /**
     * Creates a random <code>EmailIdentity</code>.
     */
    public EmailIdentity() {
        // key initialization happens in the super constructor, which calls initKeys
    }

    /**
     * Creates a <code>EmailIdentity</code> from a Base64-encoded string. The format is the same as
     * for Base64-encoded local I2P destinations, except there is no null certificate.
     * @param key
     */
    public EmailIdentity(String key) {
        try {
            key = key.substring(0, 512) + "AAAA" + key.substring(512);   // insert a null certificate for I2PClient.createSession()
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.decode(key));
            
            I2PClient i2pClient = I2PClientFactory.createClient();
            I2PSession i2pSession = i2pClient.createSession(inputStream, null);
            initKeys(i2pSession);
        }
        catch (I2PSessionException e) {
            log.error("Can't generate EmailIdentity.", e);
        }
    }
    
    public PrivateKey getPrivateEncryptionKey() {
        return privateEncryptionKey;
    }
    
    public SigningPrivateKey getPrivateSigningKey() {
        return privateSigningKey;
    }

    public void setPublicName(String publicName) {
        this.publicName = publicName;
    }

    public String getPublicName() {
        return publicName;
    }

    public void setDescription(String name) {
        this.description = name;
    }

    public String getDescription() {
        return description;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    protected void initKeys(I2PSession i2pSession) {
        super.initKeys(i2pSession);
        privateEncryptionKey = i2pSession.getDecryptionKey();
        privateSigningKey = i2pSession.getPrivateKey();
    }
    
    private byte[] getKeysAsArray() {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try {
            byteStream.write(getPublicEncryptionKey().getData());
            byteStream.write(getPublicSigningKey().getData());
            byteStream.write(getPrivateEncryptionKey().getData());
            byteStream.write(getPrivateSigningKey().getData());
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        return byteStream.toByteArray();
    }
    
    /**
     * Returns the two key pairs (public + private) as one Base64-encoded string.
     * @return
     */
    public String getFullKey() {
        return Base64.encode(getKeysAsArray());
    }
    
    @Override
    public String toString() {
        return getKey() + " address=<" + getEmailAddress() + "> identity name=<" + getDescription() + "> visible name=<" + getPublicName() + ">";
    }
    
/*    @Override
    public boolean equals(Object anotherObject) {
        if (anotherObject instanceof EmailIdentity) {
            EmailIdentity otherIdentity = (EmailIdentity)anotherObject;
            return Arrays.equals(getKeysAsArray(), otherIdentity.getKeysAsArray());
        }
        return false;
    }*/
}