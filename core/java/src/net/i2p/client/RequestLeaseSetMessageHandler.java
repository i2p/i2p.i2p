package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyGenerator;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
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
    private final Map _existingLeaseSets;

    public RequestLeaseSetMessageHandler(I2PAppContext context) {
        super(context, RequestLeaseSetMessage.MESSAGE_TYPE);
        // not clear why there would ever be more than one
        _existingLeaseSets = new ConcurrentHashMap(4);
    }
    
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        RequestLeaseSetMessage msg = (RequestLeaseSetMessage) message;
        LeaseSet leaseSet = new LeaseSet();
        for (int i = 0; i < msg.getEndpoints(); i++) {
            Lease lease = new Lease();
            lease.setGateway(msg.getRouter(i));
            lease.setTunnelId(msg.getTunnelId(i));
            lease.setEndDate(msg.getEndDate());
            //lease.setStartDate(msg.getStartDate());
            leaseSet.addLease(lease);
        }
        // also, if this session is connected to multiple routers, include other leases here
        leaseSet.setDestination(session.getMyDestination());

        // reuse the old keys for the client
        LeaseInfo li = (LeaseInfo) _existingLeaseSets.get(session.getMyDestination());
        if (li == null) {
            li = new LeaseInfo(session.getMyDestination());
            _existingLeaseSets.put(session.getMyDestination(), li);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Creating new leaseInfo keys for "  
                           + session.getMyDestination().calculateHash().toBase64());
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Caching the old leaseInfo keys for " 
                           + session.getMyDestination().calculateHash().toBase64());
        }

        leaseSet.setEncryptionKey(li.getPublicKey());
        leaseSet.setSigningKey(li.getSigningPublicKey());
        boolean encrypt = Boolean.valueOf(session.getOptions().getProperty("i2cp.encryptLeaseSet")).booleanValue();
        String sk = session.getOptions().getProperty("i2cp.leaseSetKey");
        if (encrypt && sk != null) {
            SessionKey key = new SessionKey();
            try {
                key.fromBase64(sk);
                leaseSet.encrypt(key);
                _context.keyRing().put(session.getMyDestination().calculateHash(), key);
            } catch (DataFormatException dfe) {
                _log.error("Bad leaseset key: " + sk);
            }
        }
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
        
        @Override
        public int hashCode() {
            return DataHelper.hashCode(_pubKey) + 7 * DataHelper.hashCode(_privKey) + 7 * 7
                   * DataHelper.hashCode(_signingPubKey) + 7 * 7 * 7 * DataHelper.hashCode(_signingPrivKey);
        }
        
        @Override
        public boolean equals(Object obj) {
            if ((obj == null) || !(obj instanceof LeaseInfo)) return false;
            LeaseInfo li = (LeaseInfo) obj;
            return DataHelper.eq(_pubKey, li.getPublicKey()) && DataHelper.eq(_privKey, li.getPrivateKey())
                   && DataHelper.eq(_signingPubKey, li.getSigningPublicKey())
                   && DataHelper.eq(_signingPrivKey, li.getSigningPrivateKey());
        }
    }
}
