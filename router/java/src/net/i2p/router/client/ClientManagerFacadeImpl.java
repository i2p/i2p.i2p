package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.Job;
import net.i2p.router.Router;
import net.i2p.util.Log;

/**
 * Base impl of the client facade
 *
 * @author jrandom
 */
public class ClientManagerFacadeImpl extends ClientManagerFacade {
    private final static Log _log = new Log(ClientManagerFacadeImpl.class);
    private ClientManager _manager; 
    public final static String PROP_CLIENT_PORT = "i2cp.port";
    public final static int DEFAULT_PORT = 7654;
    
    public ClientManagerFacadeImpl() {
	_manager = null;
	_log.debug("Client manager facade created");
    }
    
    public void startup() {
	_log.info("Starting up the client subsystem");
	String portStr = Router.getInstance().getConfigSetting(PROP_CLIENT_PORT);
	if (portStr != null) {
	    try {
		int port = Integer.parseInt(portStr);
		_manager = new ClientManager(port);
	    } catch (NumberFormatException nfe) {
		_log.error("Error setting the port: " + portStr + " is not valid", nfe);
		_manager = new ClientManager(DEFAULT_PORT);
	    }
	} else {
	    _manager = new ClientManager(DEFAULT_PORT);
	}
    }    
    
    public void shutdown() {
	if (_manager != null)
	    _manager.shutdown();
    }
    
    /**
     * Request that a particular client authorize the Leases contained in the 
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param set LeaseSet with requested leases - this object must be updated to contain the 
     *            signed version (as well as any changed/added/removed Leases)
     * @param timeout ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized
     * @param onFailedJob Job to run after the timeout passes without receiving authorization
     */
    public void requestLeaseSet(Destination dest, LeaseSet set, long timeout, Job onCreateJob, Job onFailedJob) {
	if (_manager != null)
	    _manager.requestLeaseSet(dest, set, timeout, onCreateJob, onFailedJob);
	else
	    _log.error("Null manager on requestLeaseSet!");
    }
    
    /**
     * Instruct the client (or all clients) that they are under attack.  This call
     * does not block.
     *
     * @param dest Destination under attack, or null if all destinations are affected
     * @param reason Why the router thinks that there is abusive behavior
     * @param severity How severe the abuse is, with 0 being not severe and 255 is the max
     */
    public void reportAbuse(Destination dest, String reason, int severity) {
	if (_manager != null)
	    _manager.reportAbuse(dest, reason, severity);
	else
	    _log.error("Null manager on reportAbuse!");
    }
    /**
     * Determine if the destination specified is managed locally.  This call
     * DOES block.
     * 
     * @param dest Destination to be checked
     */
    public boolean isLocal(Destination dest) {
	if (_manager != null)
	    return _manager.isLocal(dest);
	else {
	    _log.debug("Null manager on isLocal(dest)!");
	    return false;
	}
    }
    /**
     * Determine if the destination specified is managed locally.  This call
     * DOES block.
     * 
     * @param destHash Hash of Destination to be checked
     */
    public boolean isLocal(Hash destHash) {
	if (_manager != null)
	    return _manager.isLocal(destHash);
	else {
	    _log.debug("Null manager on isLocal(hash)!");
	    return false;
	}
    }
    
    public void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, boolean delivered) {
	if (_manager != null)
	    _manager.messageDeliveryStatusUpdate(fromDest, id, delivered);
	else
	    _log.error("Null manager on messageDeliveryStatusUpdate!");
    }
    
    public void messageReceived(ClientMessage msg) { 
	if (_manager != null)
	    _manager.messageReceived(msg); 
	else
	    _log.error("Null manager on messageReceived!");
    }
    
    /**
     * Return the client's current config, or null if not connected
     *
     */
    public SessionConfig getClientSessionConfig(Destination dest) {
	if (_manager != null)
	    return _manager.getClientSessionConfig(dest);
	else {
	    _log.error("Null manager on getClientSessionConfig!");
	    return null;
	}
    }
    
    public String renderStatusHTML() { 
	if (_manager != null)
	    return _manager.renderStatusHTML(); 
	else {
	    _log.error("Null manager on renderStatusHTML!");
	    return null;
	}
    }
}
