package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Check to see the message is a reply from the peer regarding the current 
 * store
 *
 */
class StoreMessageSelector implements MessageSelector {
    private Log _log;
    private Hash _peer;
    private long _storeJobId;
    private long _waitingForId;
    private long _expiration;
    private boolean _found;
    public StoreMessageSelector(RouterContext ctx, long storeJobId, RouterInfo peer, long waitingForId, 
                                long expiration) {
        _log = ctx.logManager().getLog(StoreMessageSelector.class);
        _peer = peer.getIdentity().getHash();
        _storeJobId = storeJobId;
        _found = false;
        _waitingForId = waitingForId;
        _expiration = expiration;
    }

    public boolean continueMatching() { return !_found; }
    public long getExpiration() { return _expiration; }
    public boolean isMatch(I2NPMessage message) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(_storeJobId + ": isMatch("+message.getClass().getName() + ") [want deliveryStatusMessage from " 
                       + _peer + "]");
        if (message instanceof DeliveryStatusMessage) {
            DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
            if (msg.getMessageId() == _waitingForId) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(_storeJobId + ": Found match for the key we're waiting for: " + _waitingForId);
                _found = true;
                return true;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(_storeJobId + ": DeliveryStatusMessage of a key we're not looking for");
                return false;
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(_storeJobId + ": Not a DeliveryStatusMessage");
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder rv = new StringBuilder(64);
        rv.append("Waiting for netDb confirm from ").append(_peer.toBase64()).append(", found? ");
        rv.append(_found).append(" waiting for ").append(_waitingForId);
        return rv.toString();
    }
}

