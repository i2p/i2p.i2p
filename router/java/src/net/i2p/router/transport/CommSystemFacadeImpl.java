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
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.RouterAddress;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.tcp.TCPTransport;
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
    
    public int countActivePeers() { return _manager.countActivePeers(); }
    
    public List getBids(OutNetMessage msg) {
        return _manager.getBids(msg);
    }
    
    public void processMessage(OutNetMessage msg) {	
        GetBidsJob j = new GetBidsJob(_context, this, msg);
        j.runJob();
    }
    
    public void renderStatusHTML(OutputStream out) throws IOException { 
        _manager.renderStatusHTML(out); 
    }
    
    public Set createAddresses() {
        Set addresses = new HashSet();
        RouterAddress addr = createTCPAddress();
        if (addr != null)
            addresses.add(addr);
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating addresses: " + addresses);
        return addresses;
    }
    
    private final static String PROP_I2NP_TCP_HOSTNAME = "i2np.tcp.hostname";
    private final static String PROP_I2NP_TCP_PORT = "i2np.tcp.port";
    
    private RouterAddress createTCPAddress() {
        RouterAddress addr = new RouterAddress();
        addr.setCost(10);
        addr.setExpiration(null);
        Properties props = new Properties();
        String name = _context.router().getConfigSetting(PROP_I2NP_TCP_HOSTNAME);
        String port = _context.router().getConfigSetting(PROP_I2NP_TCP_PORT);
        if ( (name == null) || (port == null) ) {
            _log.info("TCP Host/Port not specified in config file - skipping TCP transport");
            return null;
        } else {
            _log.info("Creating TCP address on " + name + ":" + port);
        }
        props.setProperty("host", name);
        props.setProperty("port", port);
        addr.setOptions(props);
        addr.setTransportStyle(TCPTransport.STYLE);
        return addr;
    }
}
