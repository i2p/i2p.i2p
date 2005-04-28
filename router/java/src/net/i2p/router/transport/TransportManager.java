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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
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

    private void configTransports() {
        String disableTCP = _context.router().getConfigSetting(PROP_DISABLE_TCP);
        if ( (disableTCP != null) && (Boolean.TRUE.toString().equalsIgnoreCase(disableTCP)) ) {
            _log.info("Explicitly disabling the TCP transport!");
        } else {
            Transport t = new TCPTransport(_context);
            t.setListener(this);
            _transports.add(t);
        }
        String enableUDP = _context.router().getConfigSetting(PROP_ENABLE_UDP);
        if ( (enableUDP != null) && (Boolean.valueOf(enableUDP).booleanValue())) {
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
    
    List getAddresses() {
        List rv = new ArrayList(_transports.size());
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            rv.addAll(t.getCurrentAddresses());
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
    
    public void messageReceived(I2NPMessage message, RouterIdentity fromRouter, Hash fromRouterHash) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("I2NPMessage received: " + message.getClass().getName(), new Exception("Where did I come from again?"));
        int num = _context.inNetMessagePool().add(message, fromRouter, fromRouterHash);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Added to in pool: "+ num);
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
        StringBuffer buf = new StringBuffer(8*1024);
        buf.append("<h2>Transport Manager</h2>\n");
        buf.append("Listening on: <br /><pre>\n");
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            for (Iterator iter = t.getCurrentAddresses().iterator(); iter.hasNext(); ) {
                RouterAddress addr = (RouterAddress)iter.next();
                buf.append(addr.toString()).append("\n\n");
            }   
        }
        buf.append("</pre>\n");
        out.write(buf.toString());
        for (Iterator iter = _transports.iterator(); iter.hasNext(); ) {
            Transport t = (Transport)iter.next();
            //String str = t.renderStatusHTML();
            //if (str != null)
            //    buf.append(str);
            t.renderStatusHTML(out);
        }
        //out.write(buf.toString());
        out.flush();
    }
}
