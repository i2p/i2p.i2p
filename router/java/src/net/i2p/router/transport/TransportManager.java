package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2np.DatabaseFindNearestMessage;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.InNetMessage;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.transport.phttp.PHTTPTransport;
import net.i2p.router.transport.tcp.TCPTransport;
import net.i2p.util.Clock;
import net.i2p.util.Log;

public class TransportManager implements TransportEventListener {
    private static final Log _log = new Log(TransportManager.class);
    private List _transports;
    private List _addresses;
    private SigningPrivateKey _myIdentitySigningKey;

    private final static String PROP_DISABLE_TCP = "i2np.tcp.disable";
    
    public TransportManager(RouterInfo routerInfo, SigningPrivateKey routerSigningKey) {
	_myIdentitySigningKey = routerSigningKey;
	_transports = new ArrayList();
	_addresses = new ArrayList();
    }
    
    public void addTransport(Transport transport) {
	if (transport == null) return;
	_transports.add(transport);
	transport.setListener(this);
    }
    
    public void removeTransport(Transport transport) {
	if (transport == null) return;
	_transports.remove(transport);
	transport.setListener(null);
    }

    private void configTransports() {
	RouterIdentity ident = Router.getInstance().getRouterInfo().getIdentity();
	Set addresses = CommSystemFacade.getInstance().createAddresses();
	RouterAddress tcpAddr = null;
	RouterAddress phttpAddr = null;
	for (Iterator iter = addresses.iterator(); iter.hasNext();) {
	    RouterAddress addr = (RouterAddress)iter.next();
	    if (TCPTransport.STYLE.equals(addr.getTransportStyle())) {
		tcpAddr = addr;
	    }
	    if (PHTTPTransport.STYLE.equals(addr.getTransportStyle())) {
		phttpAddr = addr;
	    }
	}
	   
	String disableTCP = Router.getInstance().getConfigSetting(PROP_DISABLE_TCP);
	if ( (disableTCP != null) && (Boolean.TRUE.toString().equalsIgnoreCase(disableTCP)) ) {
	    _log.info("Explicitly disabling the TCP transport!");
	} else {
	    Transport t = new TCPTransport(ident, _myIdentitySigningKey, tcpAddr);
	    t.setListener(this);
	    _transports.add(t);
	}
	Transport t = new PHTTPTransport(ident, _myIdentitySigningKey, phttpAddr);
	t.setListener(this);
	_transports.add(t);
    }
    
    public void startListening() {
	configTransports();
	_log.debug("Starting up the transport manager");
	for (int i = 0; i < _transports.size(); i++) {
	    Transport t = (Transport)_transports.get(i);
	    RouterAddress addr = t.startListening();
	    if (addr != null) _addresses.add(addr);
	    _log.debug("Transport " + i + " (" + t.getStyle() + ") started");
	}
	_log.debug("Done start listening on transports");
    }
    
    public void stopListening() {
	for (int i = 0; i < _transports.size(); i++) {
	    ((Transport)_transports.get(i)).stopListening();
	}
	_transports.clear();
    }
    
    private boolean isSupported(Set addresses, Transport t) {
	for (Iterator iter = addresses.iterator(); iter.hasNext(); ) {
	    RouterAddress addr = (RouterAddress)iter.next();
	    if (addr.getTransportStyle().equals(t.getStyle()))
		return true;
	}
	return false;
    }
    
    public List getBids(OutNetMessage msg) {
	if (msg == null)
	    throw new IllegalArgumentException("Null message?  no bidding on a null outNetMessage!");
	if (Router.getInstance().getRouterInfo().equals(msg.getTarget()))
	    throw new IllegalArgumentException("WTF, bids for a message bound to ourselves?");
	
	HashSet bids = new HashSet();
	
	Set addrs = msg.getTarget().getAddresses();
	Set failedTransports = msg.getFailedTransports();
	for (int i = 0; i < _transports.size(); i++) {
	    Transport t = (Transport)_transports.get(i);
	    if (failedTransports.contains(t.getStyle())) {
		_log.debug("Skipping transport " + t.getStyle() + " as it already failed");
		continue;
	    }
	    // we always want to try all transports, in case there is a faster bidirectional one
	    // already connected (e.g. peer only has a public PHTTP address, but they've connected
	    // to us via TCP, send via TCP)
	    if (true || isSupported(addrs, t)) { 
		TransportBid bid = t.bid(msg.getTarget(), msg.getMessageSize());
		if (bid != null) {
		    bids.add(bid);
		    _log.debug("Transport " + t.getStyle() + " bid: " + bid);
		} else {
		    _log.debug("Transport " + t.getStyle() + " did not produce a bid");
		}
	    }
	}
	List ordered = orderBids(bids, msg);
	long delay = Clock.getInstance().now() - msg.getCreated();
	if (ordered.size() > 0) {
	    _log.debug("Winning bid: " + ((TransportBid)ordered.get(0)).getTransport().getStyle());
	    if (delay > 5*1000) {
		_log.info("Took too long to find this bid (" + delay + "ms)");
	    } else {
		_log.debug("Took a while to find this bid (" + delay + "ms)");
	    }
	} else {
	    _log.info("NO WINNING BIDS!  peer: " + msg.getTarget());
	    if (delay > 5*1000) {
		_log.info("Took too long to fail (" + delay + "ms)");
	    } else {
		_log.debug("Took a while to fail (" + delay + "ms)");
	    }
	}
	return ordered;
    }
    
    private List orderBids(HashSet bids, OutNetMessage msg) {
	// db messages should go as fast as possible, while the others
	// should use as little bandwidth as possible.  
	switch (msg.getMessage().getType()) {
	    case DatabaseFindNearestMessage.MESSAGE_TYPE:
	    case DatabaseLookupMessage.MESSAGE_TYPE:
	    case DatabaseSearchReplyMessage.MESSAGE_TYPE:
	    case DatabaseStoreMessage.MESSAGE_TYPE:
		_log.debug("Ordering by fastest");
		return orderByFastest(bids, msg);
	    default:
		_log.debug("Ordering by bandwidth");
		return orderByBandwidth(bids, msg);
	}
    }
    
    private int getCost(RouterInfo target, String transportStyle) {
	for (Iterator iter = target.getAddresses().iterator(); iter.hasNext();) {
	    RouterAddress addr = (RouterAddress)iter.next();
	    if (addr.getTransportStyle().equals(transportStyle))
		return addr.getCost();
	}
	return 1;
    }
    
    private List orderByFastest(HashSet bids, OutNetMessage msg) {
	Map ordered = new TreeMap();
	for (Iterator iter = bids.iterator(); iter.hasNext(); ) {
	    TransportBid bid = (TransportBid)iter.next();
	    int cur = bid.getLatencyMs();
	    int cost = getCost(msg.getTarget(), bid.getTransport().getStyle());
	    _log.debug("Bid latency: " + (cur*cost) + " for transport " + bid.getTransport().getStyle());
	    while (ordered.containsKey(new Integer(cur*cost)))
		cur++;
	    ordered.put(new Integer(cur*cost), bid);
	}
	List bidList = new ArrayList(ordered.size());
	for (Iterator iter = ordered.keySet().iterator(); iter.hasNext(); ) {
	    Object k = iter.next();
	    bidList.add(ordered.get(k));
	}
	return bidList;
    }
    private List orderByBandwidth(HashSet bids, OutNetMessage msg) {
	Map ordered = new TreeMap();
	for (Iterator iter = bids.iterator(); iter.hasNext(); ) {
	    TransportBid bid = (TransportBid)iter.next();
	    int cur = bid.getBandwidthBytes();
	    int cost = getCost(msg.getTarget(), bid.getTransport().getStyle());
	    _log.debug("Bid size: " + (cur*cost) + " for transport " + bid.getTransport().getStyle());
	    while (ordered.containsKey(new Integer(cur*cost)))
		cur++;
	    ordered.put(new Integer(cur*cost), bid);
	}
	List bidList = new ArrayList(ordered.size());
	for (Iterator iter = ordered.keySet().iterator(); iter.hasNext(); ) {
	    Object k = iter.next();
	    bidList.add(ordered.get(k));
	}
	return bidList;
    }
    
    public void messageReceived(I2NPMessage message, RouterIdentity fromRouter, Hash fromRouterHash) {
	_log.debug("I2NPMessage received: " + message.getClass().getName(), new Exception("Where did I come from again?"));
	InNetMessage msg = new InNetMessage();
	msg.setFromRouter(fromRouter);
	msg.setFromRouterHash(fromRouterHash);
	msg.setMessage(message);
	int num = InNetMessagePool.getInstance().add(msg);
	_log.debug("Added to in pool: "+ num);
    }
    
    public String renderStatusHTML() {
	StringBuffer buf = new StringBuffer();
	buf.append("<h2>Transport Manager</h2>\n");
	buf.append("Listening on: <br /><pre>\n");
	for (Iterator iter = _addresses.iterator(); iter.hasNext(); ) {
	    RouterAddress addr = (RouterAddress)iter.next();
	    buf.append(addr.toString()).append("\n\n");
	}
	buf.append("</pre>\n");
	buf.append("<ul>\n");
	for (Iterator iter = _transports.iterator(); iter.hasNext(); ) {
	    Transport t = (Transport)iter.next();
	    String str = t.renderStatusHTML();
	    if (str != null)
		buf.append("<li>").append(str).append("</li>\n");
	}
	buf.append("</ul>\n");
	return buf.toString();
    }
}
