package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

//import net.i2p.router.message.ProcessOutboundClientMessageJob;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.i2p.router.message.OutboundClientMessageJob;
import net.i2p.util.Log;

/**
 * Manage all of the inbound and outbound client messages maintained by the router.
 * The ClientManager subsystem fetches messages from this for locally deliverable
 * messages and adds in remotely deliverable messages.  Remotely deliverable messages
 * are picked up by interested jobs and processed and transformed into an OutNetMessage
 * to be eventually placed in the OutNetMessagePool.
 *
 */
public class ClientMessagePool {
    private final static Log _log = new Log(ClientMessagePool.class);
    private static ClientMessagePool _instance = new ClientMessagePool();
    public static final ClientMessagePool getInstance() { return _instance; }
    private List _inMessages;
    private List _outMessages;
    
    private ClientMessagePool() {
	_inMessages = new ArrayList();
	_outMessages = new ArrayList();
    }
  
    /**
     * Add a new message to the pool.  The message can either be locally or 
     * remotely destined.
     *
     */
    public void add(ClientMessage msg) {
	if ( (ClientManagerFacade.getInstance().isLocal(msg.getDestination())) ||
	     (ClientManagerFacade.getInstance().isLocal(msg.getDestinationHash())) ) {
	    _log.debug("Adding message for local delivery");
	    ClientManagerFacade.getInstance().messageReceived(msg);
	    //synchronized (_inMessages) {
	    //	_inMessages.add(msg);
	    //}
	} else {
	    _log.debug("Adding message for remote delivery");
	    //JobQueue.getInstance().addJob(new ProcessOutboundClientMessageJob(msg));
	    JobQueue.getInstance().addJob(new OutboundClientMessageJob(msg));
	    //synchronized (_outMessages) {
	    //	_outMessages.add(msg);
	    //}
	}
    }
    
    /**
     * Retrieve the next locally destined message, or null if none are available.
     *
     */
    public ClientMessage getNextLocal() {
	synchronized (_inMessages) {
	    if (_inMessages.size() <= 0) return null;
	    return (ClientMessage)_inMessages.remove(0);
	}
    }
    
    /**
     * Retrieve the next remotely destined message, or null if none are available.
     *
     */
    public ClientMessage getNextRemote() {
	synchronized (_outMessages) {
	    if (_outMessages.size() <= 0) return null;
	    return (ClientMessage)_outMessages.remove(0);
	}
    }
    
    /**
     * Determine how many locally bound messages are in the pool
     *
     */
    public int getLocalCount() {
	synchronized (_inMessages) {
	    return _inMessages.size(); 
	}
    }
    
    /**
     * Determine how many remotely bound messages are in the pool.
     *
     */
    public int getRemoteCount() {
	synchronized (_outMessages) {
	    return _outMessages.size();
	}
    }
    
    public void dumpPoolInfo() {
	StringBuffer buf = new StringBuffer();
	buf.append("\nDumping Client Message Pool.  Local messages: ").append(getLocalCount()).append(" Remote messages: ").append(getRemoteCount()).append("\n");
	buf.append("Inbound messages\n");
	buf.append("----------------------------\n");
	synchronized (_inMessages) {
	    for (Iterator iter = _inMessages.iterator(); iter.hasNext();) {
		ClientMessage msg = (ClientMessage)iter.next();
		buf.append(msg).append("\n\n");
	    }
	}
	buf.append("Outbound messages\n");
	buf.append("----------------------------\n");
	synchronized (_outMessages) {
	    for (Iterator iter = _outMessages.iterator(); iter.hasNext();) {
		ClientMessage msg = (ClientMessage)iter.next();
		buf.append(msg).append("\n\n");
	    }
	}
	_log.debug(buf.toString());
    }
}
