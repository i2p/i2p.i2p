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
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.KeyManager;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.MessageHistory;
import net.i2p.router.Router;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Unencrypt a garlic message and handle each of the cloves - locally destined
 * messages are tossed into the inbound network message pool so they're handled 
 * as if they arrived locally.  Other instructions are not yet implemented (but
 * need to be. soon)
 *
 */
public class HandleGarlicMessageJob extends JobImpl {
    private final static Log _log = new Log(HandleGarlicMessageJob.class);
    private GarlicMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private static Map _cloves; // map of clove Id --> Expiration of cloves we've already seen
    
    private final static int FORWARD_PRIORITY = 50;
    
    public HandleGarlicMessageJob(GarlicMessage msg, RouterIdentity from, Hash fromHash) {
	super();
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("New handle garlicMessageJob called w/ message from [" + from + "]", new Exception("Debug"));
	_message = msg;
	_from = from;
	_fromHash = fromHash;
	_cloves = new HashMap();
    }
    
    public String getName() { return "Handle Inbound Garlic Message"; }
    public void runJob() {
	CloveSet set = GarlicMessageParser.getInstance().getGarlicCloves(_message, KeyManager.getInstance().getPrivateKey());
	if (set == null) {
	    Set keys = KeyManager.getInstance().getAllKeys();
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Decryption with the router's key failed, now try with the " + keys.size() + " leaseSet keys");
	    // our router key failed, which means that it was either encrypted wrong 
	    // or it was encrypted to a LeaseSet's PublicKey
	    for (Iterator iter = keys.iterator(); iter.hasNext();) {
		LeaseSetKeys lskeys = (LeaseSetKeys)iter.next();
		set = GarlicMessageParser.getInstance().getGarlicCloves(_message, lskeys.getDecryptionKey());
		if (set != null) {
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Decrypted garlic message with lease set key for destination " + lskeys.getDestination().calculateHash().toBase64() + " SUCCEEDED: " + set);
		    break;
		} else {
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Decrypting garlic message with lease set key for destination " + lskeys.getDestination().calculateHash().toBase64() + " failed");
		}
	    }
	} else {
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Decrypted clove set found " + set.getCloveCount() + " cloves: " + set);
	}
	if (set != null) {
	    for (int i = 0; i < set.getCloveCount(); i++) {
		GarlicClove clove = set.getClove(i);
		handleClove(clove);
	    }
	} else {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("CloveMessageParser failed to decrypt the message [" + _message.getUniqueId() + "] to us when received from [" + _fromHash + "] / [" + _from + "]", new Exception("Decrypt garlic failed"));
	    MessageHistory.getInstance().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Garlic could not be decrypted");
	}
    }
    
    private static boolean isKnown(long cloveId) {
	boolean known = false;
	synchronized (_cloves) {
	    known = _cloves.containsKey(new Long(cloveId));
	}
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("isKnown("+cloveId+"): " + known);
	return known;
    }
    
    private static void cleanupCloves() {
	// this should be in its own thread perhaps?  and maybe _cloves should be
	// synced to disk?
	List toRemove = new ArrayList(32);
	long now = Clock.getInstance().now();
	synchronized (_cloves) {
	    for (Iterator iter = _cloves.keySet().iterator(); iter.hasNext();) {
		Long id = (Long)iter.next();
		Date exp = (Date)_cloves.get(id);
		if (exp == null) continue; // wtf, not sure how this can happen yet, but i've seen it.  grr.
		if (now > exp.getTime())
		    toRemove.add(id);
	    }
	    for (int i = 0; i < toRemove.size(); i++)
		_cloves.remove(toRemove.get(i));
	}
    }
    
    private static boolean isValid(GarlicClove clove) {
	if (isKnown(clove.getCloveId())) {
	    _log.error("Duplicate garlic clove received - replay attack in progress? [cloveId = " + 
	               clove.getCloveId() + " expiration = " + clove.getExpiration());
	    return false;
	} else {
	    _log.debug("Clove " + clove.getCloveId() + " expiring on " + clove.getExpiration() + " is not known");
	}
	long now = Clock.getInstance().now();
	if (clove.getExpiration().getTime() < now) {
	    if (clove.getExpiration().getTime() < now + Router.CLOCK_FUDGE_FACTOR) {
		_log.warn("Expired garlic received, but within our fudge factor [" + clove.getExpiration() + "]");
	    } else {
		if (_log.shouldLog(Log.DEBUG))
		    _log.error("Expired garlic clove received - replay attack in progress? [cloveId = " + 
		               clove.getCloveId() + " expiration = " + clove.getExpiration() + " now = " + (new Date(Clock.getInstance().now())));
		return false;
	    }
	    
	}
	synchronized (_cloves) {
	    _cloves.put(new Long(clove.getCloveId()), clove.getExpiration());
	}
	cleanupCloves();
	return true;
    }
    
    private void handleClove(GarlicClove clove) {
	if (!isValid(clove)) {
	    if (_log.shouldLog(Log.DEBUG))
		_log.warn("Invalid clove " + clove);
	    return;
	} 
	boolean requestAck = (clove.getSourceRouteBlockAction() == GarlicClove.ACTION_STATUS);
	long sendExpiration = clove.getExpiration().getTime();
	MessageHandler.getInstance().handleMessage(clove.getInstructions(), clove.getData(), requestAck, clove.getSourceRouteBlock(), 
	                                           clove.getCloveId(), _from, _fromHash, sendExpiration, FORWARD_PRIORITY);
    }
    
    public void dropped() {
	MessageHistory.getInstance().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
