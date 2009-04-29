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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.ntcp.NTCPAddress;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.udp.UDPAddress;
import net.i2p.util.Log;

public class CommSystemFacadeImpl extends CommSystemFacade {
    private Log _log;
    private RouterContext _context;
    private TransportManager _manager;
    
    public CommSystemFacadeImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(CommSystemFacadeImpl.class);
        _manager = null;
    }
    
    public void startup() {
        _log.info("Starting up the comm system");
        _manager = new TransportManager(_context);
        _manager.startListening();
    }
    
    public void shutdown() {
        if (_manager != null)
            _manager.stopListening();
    }
    
    public void restart() {
        if (_manager == null)
            startup();
        else
            _manager.restart();
    }
    
    @Override
    public int countActivePeers() { return (_manager == null ? 0 : _manager.countActivePeers()); }
    @Override
    public int countActiveSendPeers() { return (_manager == null ? 0 : _manager.countActiveSendPeers()); } 
    @Override
    public boolean haveCapacity() { return (_manager == null ? false : _manager.haveCapacity()); } 
    
    /**
     * Framed average clock skew of connected peers in seconds, or null if we cannot answer.
     * Average is calculated over the middle "percentToInclude" peers.
     */
    @Override
    public Long getFramedAveragePeerClockSkew(int percentToInclude) {
        if (_manager == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Returning null for framed averege peer clock skew (no transport manager)!");
            return null;
        }
        Vector skews = _manager.getClockSkews();
        if (skews == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Returning null for framed average peer clock skew (no data)!");
            return null;
        }
        if (skews.size() < 20) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Returning null for framed average peer clock skew (only " + skews.size() + " peers)!");
            return null;
        }
        // Going to calculate, sort them
        Collections.sort(skews);
        // Calculate frame size
        int frameSize = (skews.size() * percentToInclude / 100);
        int first = (skews.size() / 2) - (frameSize / 2);
        int last = (skews.size() / 2) + (frameSize / 2);
        // Sum skew values
        long sum = 0;
        for (int i = first; i < last; i++) {
            long value = ((Long) (skews.get(i))).longValue();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding clock skew " + i + " valued " + value + " s.");
            sum = sum + value;
        }
        // Calculate average
        Long framedAverageClockSkew = new Long(sum / frameSize);
        if (_log.shouldLog(Log.INFO))
            _log.info("Our framed average peer clock skew is " + framedAverageClockSkew + " s.");
        return framedAverageClockSkew;
    }
    
    public List getBids(OutNetMessage msg) {
        return _manager.getBids(msg);
    }
    public TransportBid getBid(OutNetMessage msg) {
        return _manager.getBid(msg);
    }
    public TransportBid getNextBid(OutNetMessage msg) {
        return _manager.getNextBid(msg);
    }
    int getTransportCount() { return _manager.getTransportCount(); }
    
    public void processMessage(OutNetMessage msg) {	
        //GetBidsJob j = new GetBidsJob(_context, this, msg);
        //j.runJob();
        GetBidsJob.getBids(_context, this, msg);
    }
    
    @Override
    public boolean isBacklogged(Hash dest) { 
        return _manager.isBacklogged(dest); 
    }
    
    @Override
    public boolean isEstablished(Hash dest) { 
        return _manager.isEstablished(dest); 
    }
    
    @Override
    public boolean wasUnreachable(Hash dest) { 
        return _manager.wasUnreachable(dest); 
    }
    
    public byte[] getIP(Hash dest) { 
        return _manager.getIP(dest); 
    }
    
    @Override
    public List getMostRecentErrorMessages() { 
        return _manager.getMostRecentErrorMessages(); 
    }

    @Override
    public short getReachabilityStatus() { 
        if (_manager == null) return CommSystemFacade.STATUS_UNKNOWN;
        if (_context.router().isHidden()) return CommSystemFacade.STATUS_OK;
        return _manager.getReachabilityStatus(); 
    }
    @Override
    public void recheckReachability() { _manager.recheckReachability(); }

    @Override
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { 
        _manager.renderStatusHTML(out, urlBase, sortFlags); 
    }
    
    @Override
    public Set createAddresses() {
        Map addresses = null;
        boolean newCreated = false;
        
        if (_manager != null) {
            addresses = _manager.getAddresses();
        } else {
            addresses = new HashMap(1);
            newCreated = true;
        }
        
        if (!addresses.containsKey(NTCPTransport.STYLE)) {
            RouterAddress addr = createNTCPAddress(_context);
            if (_log.shouldLog(Log.INFO))
                _log.info("NTCP address: " + addr);
            if (addr != null)
                addresses.put(NTCPTransport.STYLE, addr);
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating addresses: " + addresses + " isNew? " + newCreated, new Exception("creator"));
        return new HashSet(addresses.values());
    }
    
    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
    public final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";
    
    public static RouterAddress createNTCPAddress(RouterContext ctx) {
        if (!TransportManager.enableNTCP(ctx)) return null;
        RouterAddress addr = new RouterAddress();
        addr.setCost(10);
        addr.setExpiration(null);
        Properties props = new Properties();
        String name = ctx.router().getConfigSetting(PROP_I2NP_NTCP_HOSTNAME);
        String port = ctx.router().getConfigSetting(PROP_I2NP_NTCP_PORT);
        /*
        boolean isNew = false;
        if (name == null) {
            name = "localhost";
            isNew = true;
        }
        if (port == null) {
            port = String.valueOf(ctx.random().nextInt(10240)+1024);
            isNew = true;
        }
         */
        if ( (name == null) || (port == null) || (name.trim().length() <= 0) || ("null".equals(name)) )
            return null;
        try {
            int p = Integer.parseInt(port);
            if ( (p <= 0) || (p > 64*1024) )
                return null;
        } catch (NumberFormatException nfe) {
            return null;
        }
        props.setProperty(NTCPAddress.PROP_HOST, name);
        props.setProperty(NTCPAddress.PROP_PORT, port);
        addr.setOptions(props);
        addr.setTransportStyle(NTCPTransport.STYLE);
        //if (isNew) {
            if (false) return null;
            ctx.router().setConfigSetting(PROP_I2NP_NTCP_HOSTNAME, name);
            ctx.router().setConfigSetting(PROP_I2NP_NTCP_PORT, port);
            ctx.router().saveConfig();
        //}
        return addr;
    }

    /**
     * UDP changed addresses, tell NTCP and restart
     */
    @Override
    public void notifyReplaceAddress(RouterAddress UDPAddr) {
        if (UDPAddr == null)
            return;
        NTCPTransport t = (NTCPTransport) _manager.getTransport(NTCPTransport.STYLE);
        if (t == null)
            return;
        Properties UDPProps = UDPAddr.getOptions();
        if (UDPProps == null)
            return;
        Properties newProps;
        RouterAddress oldAddr = t.getCurrentAddress();
        //_log.warn("Changing NTCP Address? was " + oldAddr);
        RouterAddress newAddr = oldAddr;
        if (newAddr == null) {
            newAddr = new RouterAddress();
            newAddr.setCost(10);
            newAddr.setExpiration(null);
            newAddr.setTransportStyle(NTCPTransport.STYLE);
            newProps = new Properties();
        } else {
            newProps = newAddr.getOptions();
            if (newProps == null)
                newProps = new Properties();
        }

        boolean changed = false;
        String oport = newProps.getProperty(NTCPAddress.PROP_PORT);
        String enabled = _context.getProperty(PROP_I2NP_NTCP_AUTO_PORT, "false");
        if ( (enabled != null) && ("true".equalsIgnoreCase(enabled)) ) {
            String nport = UDPProps.getProperty(UDPAddress.PROP_PORT);
            if (nport == null || nport.length() <= 0)
                return;
            if (oport == null || ! oport.equals(nport)) {
                newProps.setProperty(NTCPAddress.PROP_PORT, nport);
                changed = true;
            }
        } else if (oport == null || oport.length() <= 0) {
            return;
        }

        String ohost = newProps.getProperty(NTCPAddress.PROP_HOST);
        enabled = _context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "false");
        if ( (enabled != null) && ("true".equalsIgnoreCase(enabled)) ) {
            String nhost = UDPProps.getProperty(UDPAddress.PROP_HOST);
            if (nhost == null || nhost.length() <= 0)
                return;
            if (ohost == null || ! ohost.equalsIgnoreCase(nhost)) {
                newProps.setProperty(NTCPAddress.PROP_HOST, nhost);
                changed = true;
            }
        } else if (ohost == null || ohost.length() <= 0) {
            return;
        }

        if (!changed) {
            //_log.warn("No change to NTCP Address");
            return;
        }

        // stopListening stops the pumper, readers, and writers, so required even if
        // oldAddr == null since startListening starts them all again
        _log.warn("Halting NTCP to change address");
        t.stopListening();
        newAddr.setOptions(newProps);
        // Wait for NTCP Pumper to stop so we don't end up with two...
        while (t.isAlive()) {
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        }
        t.restartListening(newAddr);
        _log.warn("Changed NTCP Address and started up, address is now " + newAddr);
        return;     	
    }
    
}
