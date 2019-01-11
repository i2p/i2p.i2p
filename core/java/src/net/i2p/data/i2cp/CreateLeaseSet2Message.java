package net.i2p.data.i2cp;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.MetaLeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;

/**
 * Like CreateLeaseSetMessage, but supports both old
 * and new LeaseSet types, including LS2, Meta, and Encrypted.
 *
 * For LS2:
 * Same as CreateLeaseSetMessage, but has a netdb type before
 * the LeaseSet. SigningPrivateKey and PrivateKey(s) are
 * serialized after the LeaseSet, not before, so we can
 * infer the types from the LeaseSet.
 *
 * For Meta LS:
 * SigningPrivateKey and PrivateKey are not present.
 *
 * For Encrypted LS:
 * TODO
 *
 * @since 0.9.38
 */
public class CreateLeaseSet2Message extends CreateLeaseSetMessage {
    public final static int MESSAGE_TYPE = 40;

    // only used if more than one key, otherwise null
    private List<PrivateKey> _privateKeys;

    public CreateLeaseSet2Message() {
        super();
    }

    /**
     *  This returns all the keys. getPrivateKey() returns the first one.
     *  @return not a copy, do not modify, null if none
     */
    public List<PrivateKey> getPrivateKeys() {
        if (_privateKeys != null)
            return _privateKeys;
        PrivateKey pk = getPrivateKey();
        if (pk != null)
            return Collections.singletonList(pk);
        return null;
    }

    /**
     *  Add a private key.
     */
    public void addPrivateKey(PrivateKey key) {
        PrivateKey pk = getPrivateKey();
        if (pk == null) {
            setPrivateKey(key);
        } else {
            if (_privateKeys == null) {
                _privateKeys = new ArrayList<PrivateKey>(4);
                _privateKeys.add(pk);
            }
            _privateKeys.add(key);
        }
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            int type = in.read();
            if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
                _leaseSet = new LeaseSet();
            } else if (type == DatabaseEntry.KEY_TYPE_LS2) {
                _leaseSet = new LeaseSet2();
            } else if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                _leaseSet = new EncryptedLeaseSet();
            } else if (type == DatabaseEntry.KEY_TYPE_META_LS2) {
                _leaseSet = new MetaLeaseSet();
            } else if (type == -1) {
                throw new EOFException("EOF reading LS type");
            } else {
                throw new I2CPMessageException("Unsupported Leaseset type: " + type);
            }
            _leaseSet.readBytes(in);
            if (type != DatabaseEntry.KEY_TYPE_META_LS2) {
                // In CLSM this is the type of the dest, but revocation is unimplemented.
                // In CLS2M this is the type of the signature (which may be different than the
                // type of the dest if it's an offline signature)
                // and is needed by the session tag manager.
                SigType stype = _leaseSet.getSignature().getType();
                if (stype == null)
                    throw new I2CPMessageException("Unsupported sig type");
                _signingPrivateKey = new SigningPrivateKey(stype);
                _signingPrivateKey.readBytes(in);
                if (type == DatabaseEntry.KEY_TYPE_LS2 ||
                    type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                    LeaseSet2 ls2 = (LeaseSet2) _leaseSet;
                    // get one PrivateKey for each PublicKey
                    // TODO decrypt an encrypted LS so we can get the keys
                    List<PublicKey> pks = ls2.getEncryptionKeys();
                    if (pks == null)
                        throw new I2CPMessageException("TODO decrypt");
                    for (PublicKey pk : pks) {
                        EncType etype = pk.getType();
                        if (etype == null)
                            throw new I2CPMessageException("Unsupported encryption type");
                        PrivateKey priv = new PrivateKey(etype);
                        priv.readBytes(in);
                        addPrivateKey(priv);
                    }
                } else {
                    EncType etype = _leaseSet.getEncryptionKey().getType();
                    if (etype == null)
                        throw new I2CPMessageException("Unsupported encryption type");
                    _privateKey = new PrivateKey(etype);
                    _privateKey.readBytes(in);
                }
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error reading the CreateLeaseSetMessage", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionId == null || _leaseSet == null ||
            (_leaseSet.getType() != DatabaseEntry.KEY_TYPE_META_LS2 && (_signingPrivateKey == null || _privateKey == null)))
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        int size = 4 // sessionId
                 + 1 // type
                 + _leaseSet.size()
                 + _signingPrivateKey.length()
                 + _privateKey.length();
        ByteArrayOutputStream os = new ByteArrayOutputStream(size);
        try {
            _sessionId.writeBytes(os);
            os.write(_leaseSet.getType());
            _leaseSet.writeBytes(os);
            if (_leaseSet.getType() != DatabaseEntry.KEY_TYPE_META_LS2) {
                _signingPrivateKey.writeBytes(os);
                _privateKey.writeBytes(os);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[CreateLeaseSet2Message: ");
        buf.append("\n\tLeaseSet: ").append(getLeaseSet());
        buf.append("\n\tSigningPrivateKey: ").append(getSigningPrivateKey());
        buf.append("\n\tPrivateKey: ").append(getPrivateKey());
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("]");
        return buf.toString();
    }
}
