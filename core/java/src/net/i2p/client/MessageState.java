package net.i2p.client;

import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;

import net.i2p.data.SessionKey;
import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.util.Clock;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Contains the state of a payload message being sent to a peer
 *
 */
class MessageState {
    private final static Log _log = new Log(MessageState.class);
    private long _nonce;
    private MessageId _id;
    private Set _receivedStatus;
    private SessionKey _key;
    private SessionKey _newKey;
    private Set _tags;
    private Destination _to;
    private boolean _cancelled;
    private long _created;
    private Object _lock = new Object();
    public MessageState(long nonce) {
	_nonce = nonce;
	_id = null;
	_receivedStatus = new HashSet();
	_cancelled = false;
	_key = null;
	_newKey = null;
	_tags = null;
	_to = null;
	_created = Clock.getInstance().now();
    }
    public void receive(int status) {
	synchronized (_receivedStatus) {
	    _receivedStatus.add(new Integer(status));
	}
	synchronized (_lock) {
	    _lock.notifyAll();
	}
    }

    public void setMessageId(MessageId id) { _id = id; }
    public MessageId getMessageId() { return _id; }
    public long getNonce() { return _nonce; }
    public void setKey(SessionKey key) { 
	if (_log.shouldLog(Log.DEBUG)) _log.debug("Setting key [" + _key + "] to [" + key + "]");
	_key = key; 
    }
    public SessionKey getKey() { return _key; }
    public void setNewKey(SessionKey key) { _newKey = key; }
    public SessionKey getNewKey() { return _newKey; }
    public void setTags(Set tags) { _tags = tags; }
    public Set getTags() { return _tags; }
    public void setTo(Destination dest) { _to = dest; }
    public Destination getTo() { return _to; }

    public long getElapsed() { return Clock.getInstance().now() - _created; }

    public void waitFor(int status, long expiration) {
	while (true) {
	    if (_cancelled) return;
	    long timeToWait = expiration - Clock.getInstance().now();		
	    if (timeToWait <= 0) {
		if (_log.shouldLog(Log.WARN)) _log.warn("Expired waiting for the status [" + status + "]");
		return;
	    }
	    if (isSuccess(status) || isFailure(status)) {
		if (_log.shouldLog(Log.DEBUG)) _log.debug("Received a confirm (one way or the other)");
		return;
	    }
	    if (timeToWait > 5000) {
		timeToWait = 5000;
	    } 
	    synchronized (_lock) {
		try {
		    _lock.wait(timeToWait);
		} catch (InterruptedException ie) {}
	    }
	}
    }

    private boolean isSuccess(int wantedStatus) {
	List received = null;
	synchronized (_receivedStatus) {
	    received = new ArrayList(_receivedStatus);
	    //_receivedStatus.clear();
	}
	
	boolean rv = false;
	
	if (_log.shouldLog(Log.DEBUG)) _log.debug("isSuccess(" + wantedStatus + "): " + received);
	for (Iterator iter = received.iterator(); iter.hasNext(); ) {
	    Integer val = (Integer)iter.next();
	    int recv = val.intValue();
	    switch (recv) {
		case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE:
		    if (_log.shouldLog(Log.WARN)) _log.warn("Received best effort failure after " + getElapsed() + " from " + this.toString());
		    rv = false;
		    break;
		case MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE:
		    if (_log.shouldLog(Log.WARN)) _log.warn("Received guaranteed failure after " + getElapsed() + " from " + this.toString());
		    rv = false;
		    break;
		case MessageStatusMessage.STATUS_SEND_ACCEPTED:
		    if (wantedStatus == MessageStatusMessage.STATUS_SEND_ACCEPTED) {
			return true; // if we're only looking for accepted, take it directly (don't let any GUARANTEED_* override it)
		    } else {
			if (_log.shouldLog(Log.DEBUG)) _log.debug("Got accepted, but we're waiting for more from " + this.toString());
			continue;
			// ignore accepted, as we want something better
		    }
		case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_SUCCESS:
		    if (_log.shouldLog(Log.DEBUG)) _log.debug("Received best effort success after " + getElapsed() + " from " + this.toString());
		    if (wantedStatus == recv) {
			rv = true;
		    } else {
			if (_log.shouldLog(Log.DEBUG)) _log.debug("Not guaranteed success, but best effort after " + getElapsed() + " will do... from " + this.toString());
			rv = true;
		    }
		    break;
		case MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS:
		    if (_log.shouldLog(Log.DEBUG)) _log.debug("Received guaranteed success after " + getElapsed() + " from " + this.toString());
		    // even if we're waiting for best effort success, guaranteed is good enough
		    rv = true;
		    break;
		case -1:
		    continue;
		default:
		    if (_log.shouldLog(Log.DEBUG)) _log.debug("Received something else [" + recv + "]...");
	    }
	}
	return rv;
    }
    private boolean isFailure(int wantedStatus) {
	List received = null;
	synchronized (_receivedStatus) {
	    received = new ArrayList(_receivedStatus);
	    //_receivedStatus.clear();
	}
	boolean rv = false;
	
	if (_log.shouldLog(Log.DEBUG)) _log.debug("isFailure(" + wantedStatus + "): " + received);
	for (Iterator iter = received.iterator(); iter.hasNext(); ) {
	    Integer val = (Integer)iter.next();
	    int recv = val.intValue();
	    switch (recv) {
		case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE:
		    if (_log.shouldLog(Log.DEBUG)) _log.warn("Received best effort failure after " + getElapsed() + " from " + this.toString());
		    rv = true;
		    break;
		case MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE:
		    if (_log.shouldLog(Log.DEBUG)) _log.warn("Received guaranteed failure after " + getElapsed() + " from " + this.toString());
		    rv = true;
		    break;
		case MessageStatusMessage.STATUS_SEND_ACCEPTED:
		    if (wantedStatus == MessageStatusMessage.STATUS_SEND_ACCEPTED) {
			rv = false;
		    } else {
			if (_log.shouldLog(Log.DEBUG)) _log.debug("Got accepted, but we're waiting for more from " + this.toString());
			continue;
			// ignore accepted, as we want something better
		    }
		    break;
		case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_SUCCESS:
		    if (_log.shouldLog(Log.DEBUG)) _log.debug("Received best effort success after " + getElapsed() + " from " + this.toString());
		    if (wantedStatus == recv) {
			rv = false;
		    } else {
			if (_log.shouldLog(Log.DEBUG)) _log.debug("Not guaranteed success, but best effort after " + getElapsed() + " will do... from " + this.toString());
			rv = false;
		    }
		    break;
		case MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS:
		    if (_log.shouldLog(Log.DEBUG)) _log.debug("Received guaranteed success after " + getElapsed() + " from " + this.toString());
		    // even if we're waiting for best effort success, guaranteed is good enough
		    rv = false;
		    break;
		case -1:
		    continue;
		default:
		    if (_log.shouldLog(Log.DEBUG)) _log.debug("Received something else [" + recv + "]...");
	    }
	}
	return rv;
    }

    /** true if the given status (or an equivilant) was received */
    public boolean received(int status) {
	return isSuccess(status);
    }
    public void cancel() {
	_cancelled = true;
	synchronized (_lock) {
	    _lock.notifyAll();
	}
    }
}