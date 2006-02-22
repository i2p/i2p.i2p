package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.tcp.TCPTransport;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.util.Log;

public class TransportManager implements TransportEventListener {
    private Log _log;
    private List _transports;
    private RouterContext _context;

    private final static String PROP_DISABLE_TCP = "i2np.tcp.disable";
    private final static String PROP_ENABLE_UDP = "i2np.udp.enable";
    private static final String DEFAULT_ENABLE_UDP = "true";
    
    public TransportManager(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(TransportManager.class);
        _transports = new ArrayList();
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

    static final boolean ALLOW_TCP = true;
    
    private void configTransports() {
        String disableTCP = _context.router().getConfigSetting(PROP_DISABLE_TCP);
        // Unless overridden by constant or explicit config property, start TCP tranport
        if ( !ALLOW_TCP || ((disableTCP != null) && (Boolean.TRUE.toString().equalsIgnoreCase(disableTCP))) ) {
            _log.info("Explicitly disabling the TCP transport!");
        } else {
            Transport t = new TCPTransport(_context);
            t.setListener(this);
            _transports.add(t);
        }
        String enableUDP = _context.router().getConfigSetting(PROP_ENABLE_UDP);
        if (enableUDP == null)
            enableUDP = DEFAULT_ENABLE_UDP;
        if ("true".equalsIgnoreCase(enableUDP)) {
            UDPTransport udp = new UDPTransport(_context);
            udp.setListener(this);
            _transports.add(udp);
        }
    }
    
    public void startListening() {
        configTransports();
        _log.debug("Starting up the transport manager");
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            RouterAddress addr = t.startListening();
            _log.debug("Transport " + i + " (" + t.getStyle() + ") started");
        }
        _log.debug("Done start listening on transports");
        _context.router().rebuildRouterInfo();
    }
    
    public void restart() {
        stopListening();
        try { Thread.sleep(1*1000); } catch (InterruptedException ie) {}
        startListening();
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
    
    public int countActivePeers() { 
        int peers = 0;
        for (int i = 0; i < _transports.size(); i++) {
            peers += ((Transport)_transports.get(i)).countActivePeers();
        }
        return peers;
    }
    
    public short getReachabilityStatus() { 
        if (_transports.size() <= 0) return CommSystemFacade.STATUS_UNKNOWN;
        short status[] = new short[_transports.size()];
        for (int i = 0; i < _transports.size(); i++) {
            status[i] = ((Transport)_transports.get(i)).getReachabilityStatus();
        }
        // the values for the statuses are increasing for their 'badness'
        Arrays.sort(status);
        return status[0];
    }

    public void recheckReachability() { 
        for (int i = 0; i < _transports.size(); i++)
            ((Transport)_transports.get(i)).recheckReachability();
    }

    
    
    Map getAddresses() {
        Map rv = new HashMap(_transports.size());
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (t.getCurrentAddress() != null)
                rv.put(t.getStyle(), t.getCurrentAddress());
        }
        return rv;
    }
    
    public TransportBid getBid(OutNetMessage msg) {
        List bids = getBids(msg);
        if ( (bids == null) || (bids.size() <= 0) )
            return null;
        else
            return (TransportBid)bids.get(0);
    }
    public List getBids(OutNetMessage msg) {
        if (msg == null)
            throw new IllegalArgumentException("Null message?  no bidding on a null outNetMessage!");
        if (_context.router().getRouterInfo().equals(msg.getTarget()))
            throw new IllegalArgumentException("WTF, bids for a message bound to ourselves?");

        List rv = new ArrayList(_transports.size());
        Set failedTransports = msg.getFailedTransports();
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (failedTransports.contains(t.getStyle())) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Skipping transport " + t.getStyle() + " as it already failed");
                continue;
            }
            // we always want to try all transports, in case there is a faster bidirectional one
            // already connected (e.g. peer only has a public PHTTP address, but they've connected
            // to us via TCP, send via TCP)
            TransportBid bid = t.bid(msg.getTarget(), msg.getMessageSize());
            if (bid != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Transport " + t.getStyle() + " bid: " + bid);
                rv.add(bid);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Transport " + t.getStyle() + " did not produce a bid");
            }
        }
        return rv;
    }
    
    public TransportBid getNextBid(OutNetMessage msg) {
        Set failedTransports = msg.getFailedTransports();
        TransportBid rv = null;
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (failedTransports.contains(t.getStyle())) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Skipping transport " + t.getStyle() + " as it already failed");
                continue;
            }
            // we always want to try all transports, in case there is a faster bidirectional one
            // already connected (e.g. peer only has a public PHTTP address, but they've connected
            // to us via TCP, send via TCP)
            TransportBid bid = t.bid(msg.getTarget(), msg.getMessageSize());
            if (bid != null) {
                if ( (rv == null) || (rv.getLatencyMs() > bid.getLatencyMs()) )
                    rv = bid;    
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Transport " + t.getStyle() + " bid: " + bid + " currently winning? " + (rv == bid) 
                               + " (winning latency: " + rv.getLatencyMs() + " / " + rv + ")");
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Transport " + t.getStyle() + " did not produce a bid");
            }
        }
        return rv;
    }
    
    public void messageReceived(I2NPMessage message, RouterIdentity fromRouter, Hash fromRouterHash) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("I2NPMessage received: " + message.getClass().getName(), new Exception("Where did I come from again?"));
        try {
            int num = _context.inNetMessagePool().add(message, fromRouter, fromRouterHash);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Added to in pool: "+ num);
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving message", iae);
        }
    }
    
    public List getMostRecentErrorMessages() { 
        List rv = new ArrayList(16);
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            rv.addAll(t.getMostRecentErrorMessages());
        }
        return rv;
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        TreeMap transports = new TreeMap();
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            transports.put(t.getStyle(), t);
        }
        for (Iterator iter = transports.values().iterator(); iter.hasNext(); ) {
            Transport t= (Transport)iter.next();
            t.renderStatusHTML(out);
        }
        StringBuffer buf = new StringBuffer(4*1024);
        buf.append("Listening on: <br /><pre>\n");
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (t.getCurrentAddress() != null)
                buf.append(t.getCurrentAddress()).append("\n\n");
        }
        buf.append("</pre>\n");
        out.write(buf.toString());
        out.flush();
    }
}
