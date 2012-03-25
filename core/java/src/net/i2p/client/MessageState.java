package net.i2p.client;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.Log;

/**
 * Contains the state of a payload message being sent to a peer
 *
 */
class MessageState {
    private I2PAppContext _context;
    private final static Log _log = new Log(MessageState.class);
    private long _nonce;
    private String _prefix;
    private MessageId _id;
    private final Set _receivedStatus;
    private SessionKey _key;
    private SessionKey _newKey;
    private Set _tags;
    private Destination _to;
    private boolean _cancelled;
    private long _created;

    private static long __stateId = 0;
    private long _stateId;
    
    public MessageState(I2PAppContext ctx, long nonce, String prefix) {
        _stateId = ++__stateId;
        _context = ctx;
        _nonce = nonce;
        _prefix = prefix + "[" + _stateId + "]: ";
        _receivedStatus = new HashSet();
        _created = ctx.clock().now();
        //ctx.statManager().createRateStat("i2cp.checkStatusTime", "how long it takes to go through the states", "i2cp", new long[] { 60*1000 });
    }

    public void receive(int status) {
        synchronized (_receivedStatus) {
            _receivedStatus.add(Integer.valueOf(status));
            _receivedStatus.notifyAll();
        }
    }

    public void setMessageId(MessageId id) {
        _id = id;
    }

    public MessageId getMessageId() {
        return _id;
    }

    public long getNonce() {
        return _nonce;
    }

    public void setKey(SessionKey key) {
        if (_log.shouldLog(Log.DEBUG)) 
            _log.debug(_prefix + "Setting key [" + _key + "] to [" + key + "]");
        _key = key;
    }

    public SessionKey getKey() {
        return _key;
    }

    public void setNewKey(SessionKey key) {
        _newKey = key;
    }

    public SessionKey getNewKey() {
        return _newKey;
    }

    public void setTags(Set tags) {
        _tags = tags;
    }

    public Set getTags() {
        return _tags;
    }

    public void setTo(Destination dest) {
        _to = dest;
    }

    public Destination getTo() {
        return _to;
    }

    public long getElapsed() {
        return _context.clock().now() - _created;
    }

    public void waitFor(int status, long expiration) {
        //long checkTime = -1;
        boolean found = false;
        while (!found) {
            if (_cancelled) return;
            long timeToWait = expiration - _context.clock().now();
            if (timeToWait <= 0) {
                if (_log.shouldLog(Log.WARN)) 
                    _log.warn(_prefix + "Expired waiting for the status [" + status + "]");
                return;
            }
            found = false;
            synchronized (_receivedStatus) {
                //long beforeCheck = _context.clock().now();
                if (locked_isSuccess(status) || locked_isFailure(status)) {
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug(_prefix + "Received a confirm (one way or the other)");
                    found = true;
                }
                //checkTime = _context.clock().now() - beforeCheck;
                if (!found) {
                    if (timeToWait > 5000) {
                        timeToWait = 5000;
                    }
                    try {
                        _receivedStatus.wait(timeToWait);
                    } catch (InterruptedException ie) { // nop
                    }
                }
            }
            //if (found) 
            //    _context.statManager().addRateData("i2cp.checkStatusTime", checkTime, 0);
        }
    }

    private boolean locked_isSuccess(int wantedStatus) {
        boolean rv = false;

        if (_log.shouldLog(Log.DEBUG)) 
            _log.debug(_prefix + "isSuccess(" + wantedStatus + "): " + _receivedStatus);
        for (Iterator iter = _receivedStatus.iterator(); iter.hasNext();) {
            Integer val = (Integer) iter.next();
            int recv = val.intValue();
            switch (recv) {
                case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE:
                    if (_log.shouldLog(Log.WARN))
                         _log.warn(_prefix + "Received best effort failure after " + getElapsed() + " from "
                                   + toString());
                    rv = false;
                    break;
                case MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE:
                    if (_log.shouldLog(Log.WARN))
                         _log.warn(_prefix + "Received guaranteed failure after " + getElapsed() + " from "
                                   + toString());
                    rv = false;
                    break;
                case MessageStatusMessage.STATUS_SEND_ACCEPTED:
                    if (wantedStatus == MessageStatusMessage.STATUS_SEND_ACCEPTED) {
                        return true; // if we're only looking for accepted, take it directly (don't let any GUARANTEED_* override it)
                    }
                    // ignore accepted, as we want something better
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(_prefix + "Got accepted, but we're waiting for more from " + toString());
                    continue;
                case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_SUCCESS:
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(_prefix + "Received best effort success after " + getElapsed()
                                   + " from " + toString());
                    if (wantedStatus == recv) {
                        rv = true;
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(_prefix + "Not guaranteed success, but best effort after "
                                       + getElapsed() + " will do... from " + toString());
                        rv = true;
                    }
                    break;
                case MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS:
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(_prefix + "Received guaranteed success after " + getElapsed() + " from "
                                   + toString());
                    // even if we're waiting for best effort success, guaranteed is good enough
                    rv = true;
                    break;
                case -1:
                    continue;
                default:
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug(_prefix + "Received something else [" + recv + "]...");
            }
        }
        return rv;
    }

    private boolean locked_isFailure(int wantedStatus) {
        boolean rv = false;

        if (_log.shouldLog(Log.DEBUG)) 
            _log.debug(_prefix + "isFailure(" + wantedStatus + "): " + _receivedStatus);
        
        for (Iterator iter = _receivedStatus.iterator(); iter.hasNext();) {
            Integer val = (Integer) iter.next();
            int recv = val.intValue();
            switch (recv) {
                case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE:
                    if (_log.shouldLog(Log.DEBUG))
                        _log.warn(_prefix + "Received best effort failure after " + getElapsed() + " from "
                                  + toString());
                    rv = true;
                    break;
                case MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE:
                    if (_log.shouldLog(Log.DEBUG))
                        _log.warn(_prefix + "Received guaranteed failure after " + getElapsed() + " from "
                                  + toString());
                    rv = true;
                    break;
                case MessageStatusMessage.STATUS_SEND_ACCEPTED:
                    if (wantedStatus == MessageStatusMessage.STATUS_SEND_ACCEPTED) {
                        rv = false;
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(_prefix + "Got accepted, but we're waiting for more from "
                                       + toString());
                        continue;
                        // ignore accepted, as we want something better
                    }
                    break;
                case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_SUCCESS:
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(_prefix + "Received best effort success after " + getElapsed()
                                   + " from " + toString());
                    if (wantedStatus == recv) {
                        rv = false;
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(_prefix + "Not guaranteed success, but best effort after "
                                       + getElapsed() + " will do... from " + toString());
                        rv = false;
                    }
                    break;
                case MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS:
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(_prefix + "Received guaranteed success after " + getElapsed() + " from "
                                   + toString());
                    // even if we're waiting for best effort success, guaranteed is good enough
                    rv = false;
                    break;
                case -1:
                    continue;
                default:
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug(_prefix + "Received something else [" + recv + "]...");
            }
        }
        return rv;
    }

    /** true if the given status (or an equivilant) was received */
    public boolean received(int status) {
        synchronized (_receivedStatus) {
            return locked_isSuccess(status);
        }
    }

    public void cancel() {
        _cancelled = true;
        synchronized (_receivedStatus) {
            _receivedStatus.notifyAll();
        }
    }
}
