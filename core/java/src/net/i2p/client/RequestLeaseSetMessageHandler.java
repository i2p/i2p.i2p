package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashMap;
import java.util.Map;

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.util.Log;

/**
 * Handle I2CP RequestLeaseSetMessage from the router by granting all leases
 *
 * @author jrandom
 */
class RequestLeaseSetMessageHandler extends HandlerImpl {
    private final static Log _log = new Log(RequestLeaseSetMessageHandler.class);
    private Map _existingLeaseSets;

    public RequestLeaseSetMessageHandler() {
        super(RequestLeaseSetMessage.MESSAGE_TYPE);
        _existingLeaseSets = new HashMap(32);
    }

    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        _log.debug("Handle message " + message);
        RequestLeaseSetMessage msg = (RequestLeaseSetMessage) message;
        LeaseSet leaseSet = new LeaseSet();
        for (int i = 0; i < msg.getEndpoints(); i++) {
            Lease lease = new Lease();
            lease.setRouterIdentity(msg.getRouter(i));
            lease.setTunnelId(msg.getTunnelId(i));
            lease.setEndDate(msg.getEndDate());
            //lease.setStartDate(msg.getStartDate());
            leaseSet.addLease(lease);
        }
        // also, if this session is connected to multiple routers, include other leases here
        leaseSet.setDestination(session.getMyDestination());

        // reuse the old keys for the client
        LeaseInfo li = null;
        synchronized (_existingLeaseSets) {
            if (_existingLeaseSets.containsKey(session.getMyDestination()))
                li = (LeaseInfo) _existingLeaseSets.get(session.getMyDestination());
        }
        if (li == null) {
            li = new LeaseInfo(session.getMyDestination());
            synchronized (_existingLeaseSets) {
                _existingLeaseSets.put(session.getMyDestination(), li);
            }
            _log.debug("Creating new leaseInfo keys", new Exception("new leaseInfo keys"));
        } else {
            _log.debug("Caching the old leaseInfo keys", new Exception("cached!  w00t"));
        }

        leaseSet.setEncryptionKey(li.getPublicKey());
        leaseSet.setSigningKey(li.getSigningPublicKey());
        try {
            leaseSet.sign(session.getPrivateKey());
            session.getProducer().createLeaseSet(session, leaseSet, li.getSigningPrivateKey(), li.getPrivateKey());
            session.setLeaseSet(leaseSet);
        } catch (DataFormatException dfe) {
            session.propogateError("Error signing the leaseSet", dfe);
        } catch (I2PSessionException ise) {
            session.propogateError("Error sending the signed leaseSet", ise);
        }
    }

    private static class LeaseInfo {
        private PublicKey _pubKey;
        private PrivateKey _privKey;
        private SigningPublicKey _signingPubKey;
        private SigningPrivateKey _signingPrivKey;
        private Destination _dest;

        public LeaseInfo(Destination dest) {
            _dest = dest;
            Object encKeys[] = KeyGenerator.getInstance().generatePKIKeypair();
            Object signKeys[] = KeyGenerator.getInstance().generateSigningKeypair();
            _pubKey = (PublicKey) encKeys[0];
            _privKey = (PrivateKey) encKeys[1];
            _signingPubKey = (SigningPublicKey) signKeys[0];
            _signingPrivKey = (SigningPrivateKey) signKeys[1];
        }

        public PublicKey getPublicKey() {
            return _pubKey;
        }

        public PrivateKey getPrivateKey() {
            return _privKey;
        }

        public SigningPublicKey getSigningPublicKey() {
            return _signingPubKey;
        }

        public SigningPrivateKey getSigningPrivateKey() {
            return _signingPrivKey;
        }

        public int hashCode() {
            return DataHelper.hashCode(_pubKey) + 7 * DataHelper.hashCode(_privKey) + 7 * 7
                   * DataHelper.hashCode(_signingPubKey) + 7 * 7 * 7 * DataHelper.hashCode(_signingPrivKey);
        }

        public boolean equals(Object obj) {
            if ((obj == null) || !(obj instanceof LeaseInfo)) return false;
            LeaseInfo li = (LeaseInfo) obj;
            return DataHelper.eq(_pubKey, li.getPublicKey()) && DataHelper.eq(_privKey, li.getPrivateKey())
                   && DataHelper.eq(_signingPubKey, li.getSigningPublicKey())
                   && DataHelper.eq(_signingPrivKey, li.getSigningPrivateKey());
        }
    }
}