package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.SourceRouteReplyMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.KeyManager;
import net.i2p.router.Router;
import net.i2p.router.MessageHistory;
import net.i2p.util.Log;
import net.i2p.util.Clock;

/**
 * Handle a source route reply - decrypt the instructions and forward the message 
 * accordingly
 *
 */
public class HandleSourceRouteReplyMessageJob extends JobImpl {
    private final static Log _log = new Log(HandleSourceRouteReplyMessageJob.class);
    private SourceRouteReplyMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private static Map _seenMessages; // Long msgId --> Date seen
    
    public final static int PRIORITY = 150;
    
    public HandleSourceRouteReplyMessageJob(SourceRouteReplyMessage msg, RouterIdentity from, Hash fromHash) {
	super();
	_message = msg;
	_from = from;
	_fromHash = fromHash;
	_seenMessages = new HashMap();
    }
    
    public String getName() { return "Handle Source Route Reply Message"; }
    public void runJob() {
	try {
	    long before = Clock.getInstance().now();
	    _message.decryptHeader(KeyManager.getInstance().getPrivateKey());
	    long after = Clock.getInstance().now();
	    if ( (after-before) > 1000) {
		_log.warn("Took more than a second (" + (after-before) + ") to decrypt the sourceRoute header");
	    } else {
		_log.debug("Took LESS than a second (" + (after-before) + ") to decrypt the sourceRoute header");
	    }
	} catch (DataFormatException dfe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error decrypting the source route message's header (message " + _message.getUniqueId() + ")", dfe);
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Message header could not be decrypted: " + _message, getAddedBy());
	    MessageHistory.getInstance().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Source route message header could not be decrypted");
	    return;
	}
	
	if (!isValid()) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error validating source route message, dropping: " + _message);
	    return;
	}
	
	DeliveryInstructions instructions = _message.getDecryptedInstructions();
	
	long now = Clock.getInstance().now();
	long expiration = _message.getDecryptedExpiration();
	// if its expiring really soon, jack the expiration 30 seconds
	if (expiration < now+10*1000)
	    expiration = now + 60*1000;
	
	boolean requestAck = false;
	MessageHandler.getInstance().handleMessage(instructions, _message.getMessage(), requestAck, null, 
	                                           _message.getDecryptedMessageId(), _from, _fromHash, expiration, PRIORITY);
    }
    
    private boolean isValid() {
        long now = Clock.getInstance().now();
	if (_message.getDecryptedExpiration() < now) {
		if (_message.getDecryptedExpiration() < now + Router.CLOCK_FUDGE_FACTOR) {
			_log.info("Expired message received, but within our fudge factor");
		} else {
		    _log.error("Source route reply message expired.  Replay attack? msgId = " + _message.getDecryptedMessageId() + " expiration = " + new Date(_message.getDecryptedExpiration()));
		    return false;
		}
	}
	if (!isValidMessageId(_message.getDecryptedMessageId(), _message.getDecryptedExpiration())) {
	    _log.error("Source route reply message already received!  Replay attack?  msgId = " + _message.getDecryptedMessageId() + " expiration = " + new Date(_message.getDecryptedExpiration()));
	    return false;
	}
	return true;
    }
    
    private static boolean isValidMessageId(long msgId, long expiration) {
	synchronized (_seenMessages) {
	    if (_seenMessages.containsKey(new Long(msgId)))
		return false;
	    
	
	    _seenMessages.put(new Long(msgId), new Date(expiration));
	}
	// essentially random
	if ((msgId % 10) == 0) {
	    cleanupMessages();
	}
	return true;
    }
    
    private static void cleanupMessages() {
	// this should be in its own thread perhaps, or job?  and maybe _seenMessages should be
	// synced to disk?
	List toRemove = new ArrayList(32);
	long now = Clock.getInstance().now()-Router.CLOCK_FUDGE_FACTOR;
	synchronized (_seenMessages) {
	    for (Iterator iter = _seenMessages.keySet().iterator(); iter.hasNext();) {
		Long id = (Long)iter.next();
		Date exp = (Date)_seenMessages.get(id);
		if (now > exp.getTime())
		    toRemove.add(id);
	    }
	    for (int i = 0; i < toRemove.size(); i++)
		_seenMessages.remove(toRemove.get(i));
	}
    }

    public void dropped() {
	MessageHistory.getInstance().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
