package net.i2p.data.i2cp;

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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;

/**
 * Defines the message a client sends to a router when authorizing
 * the LeaseSet
 *
 * @author jrandom
 */
public class CreateLeaseSetMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 4;
    private SessionId _sessionId;
    private LeaseSet _leaseSet;
    private SigningPrivateKey _signingPrivateKey;
    private PrivateKey _privateKey;

    public CreateLeaseSetMessage() {
    }

    public SessionId getSessionId() {
        return _sessionId;
    }

    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    public SigningPrivateKey getSigningPrivateKey() {
        return _signingPrivateKey;
    }

    public void setSigningPrivateKey(SigningPrivateKey key) {
        _signingPrivateKey = key;
    }

    public PrivateKey getPrivateKey() {
        return _privateKey;
    }

    public void setPrivateKey(PrivateKey privateKey) {
        _privateKey = privateKey;
    }

    public LeaseSet getLeaseSet() {
        return _leaseSet;
    }

    public void setLeaseSet(LeaseSet leaseSet) {
        _leaseSet = leaseSet;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _signingPrivateKey = new SigningPrivateKey();
            _signingPrivateKey.readBytes(in);
            _privateKey = new PrivateKey();
            _privateKey.readBytes(in);
            _leaseSet = new LeaseSet();
            _leaseSet.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error reading the CreateLeaseSetMessage", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ((_sessionId == null) || (_signingPrivateKey == null) || (_privateKey == null) || (_leaseSet == null))
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        int size = 4 // sessionId
                 + SigningPrivateKey.KEYSIZE_BYTES
                 + PrivateKey.KEYSIZE_BYTES
                 + _leaseSet.size();
        ByteArrayOutputStream os = new ByteArrayOutputStream(size);
        try {
            _sessionId.writeBytes(os);
            _signingPrivateKey.writeBytes(os);
            _privateKey.writeBytes(os);
            _leaseSet.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    /* FIXME missing hashCode() method FIXME */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof CreateLeaseSetMessage)) {
            CreateLeaseSetMessage msg = (CreateLeaseSetMessage) object;
            return DataHelper.eq(getSessionId(), msg.getSessionId())
                   && DataHelper.eq(getSigningPrivateKey(), msg.getSigningPrivateKey())
                   && DataHelper.eq(getPrivateKey(), msg.getPrivateKey())
                   && DataHelper.eq(getLeaseSet(), msg.getLeaseSet());
        }
        
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[CreateLeaseSetMessage: ");
        buf.append("\n\tLeaseSet: ").append(getLeaseSet());
        buf.append("\n\tSigningPrivateKey: ").append(getSigningPrivateKey());
        buf.append("\n\tPrivateKey: ").append(getPrivateKey());
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("]");
        return buf.toString();
    }
}
