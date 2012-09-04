package net.i2p.router.transport.ntcp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterAddress;
import net.i2p.router.transport.TransportImpl;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Wrap up an address 
 */
public class NTCPAddress {
    private final int _port;
    private final String _host;
    //private InetAddress _addr;
    /** Port number used in RouterAddress definitions */
    public final static String PROP_PORT = RouterAddress.PROP_PORT;
    /** Host name used in RouterAddress definitions */
    public final static String PROP_HOST = RouterAddress.PROP_HOST;
    public static final int DEFAULT_COST = 10;
    
    public NTCPAddress(String host, int port) {
        if (host != null)
            _host = host.trim();
        else
            _host = null;
        _port = port;
    }
    
    /*
    public NTCPAddress() {
        _host = null;
        _port = -1;
       // _addr = null;
    }
    
    public NTCPAddress(InetAddress addr, int port) {
        if (addr != null)
            _host = addr.getHostAddress();
        _addr = addr;
        _port = port;
    }
     */
    
    public NTCPAddress(RouterAddress addr) {
        if (addr == null) {
            _host = null;
            _port = -1;
            return;
        }
        _host = addr.getOption(PROP_HOST);
        _port = addr.getPort();
    }
    
    public RouterAddress toRouterAddress() {
        if ( (_host == null) || (_port <= 0) ) 
            return null;
        
        RouterAddress addr = new RouterAddress();
        
        addr.setCost(DEFAULT_COST);
        //addr.setExpiration(null);
        
        Properties props = new Properties();
        props.setProperty(PROP_HOST, _host);
        props.setProperty(PROP_PORT, ""+_port);
        
        addr.setOptions(props);
        addr.setTransportStyle(NTCPTransport.STYLE);
        return addr;
    }
    
    public String getHost() { return _host; }
    //public void setHost(String host) { _host = host; }
    //public InetAddress getAddress() { return _addr; }
    //public void setAddress(InetAddress addr) { _addr = addr; }
    public int getPort() { return _port; }
    //public void setPort(int port) { _port = port; }
    
    public boolean isPubliclyRoutable() {
        return isPubliclyRoutable(_host);
    }

    public static boolean isPubliclyRoutable(String host) {
        if (host == null) return false;
        byte quad[] = Addresses.getIP(host);
        return TransportImpl.isPubliclyRoutable(quad);
    }
    
    @Override
    public String toString() { return _host + ":" + _port; }
    
    @Override
    public int hashCode() {
        int rv = _port;
        //if (_addr != null) 
        //    rv += _addr.getHostAddress().hashCode();
        //else
            if (_host != null) rv ^= _host.hashCode();
        return rv;
    }
    
    @Override
    public boolean equals(Object val) {
        if ( (val != null) && (val instanceof NTCPAddress) ) {
            NTCPAddress addr = (NTCPAddress)val;
            String hostname = null;
            if (addr.getHost() != null)
                hostname = addr.getHost().trim();
            String ourHost = getHost();
            if (ourHost != null)
                ourHost = ourHost.trim();
            return DataHelper.eq(hostname, ourHost) && getPort() == addr.getPort();
        } 
        return false;
    }
    
    public boolean equals(RouterAddress addr) {
        if (addr == null) return false;
        return ( (_host.equals(addr.getOption(PROP_HOST))) &&
                 (Integer.toString(_port).equals(addr.getOption(PROP_PORT))) );
    }
}
