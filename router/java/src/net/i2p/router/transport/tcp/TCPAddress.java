package net.i2p.router.transport.tcp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.data.RouterAddress;
import net.i2p.util.Log;

/**
 * Wrap up an address 
 */
public class TCPAddress {
    private final static Log _log = new Log(TCPAddress.class);
    private int _port;
    private String _host;
    private InetAddress _addr;
    /** Port number used in RouterAddress definitions */
    public final static String PROP_PORT = "port";
    /** Host name used in RouterAddress definitions */
    public final static String PROP_HOST = "host";
    
    public TCPAddress(String host, int port) {
        try {
            if (host != null) {
                InetAddress iaddr = InetAddress.getByName(host);
                _host = iaddr.getHostAddress();
                _addr = iaddr;
            }
            _port = port;
        } catch (UnknownHostException uhe) {
            _host = null;
            _port = -1;
            _addr = null;
        }
    }
    
    public TCPAddress() {
        _host = null;
        _port = -1;
        _addr = null;
    }
    
    public TCPAddress(InetAddress addr, int port) {
        if (addr != null)
            _host = addr.getHostAddress();
        _addr = addr;
        _port = port;
    }
    
    public TCPAddress(RouterAddress addr) {
        if (addr == null) {
            _host = null;
            _port = -1;
            return;
        }
        String host = addr.getOptions().getProperty(PROP_HOST);
        try {
            InetAddress iaddr = InetAddress.getByName(host);
            _host = iaddr.getHostAddress();
            _addr = iaddr;
            
            String port = addr.getOptions().getProperty(PROP_PORT);
            if ( (port != null) && (port.trim().length() > 0) ) {
                try {
                    _port = Integer.parseInt(port);
                } catch (NumberFormatException nfe) {
                    _log.error("Invalid port [" + port + "]", nfe);
                    _port = -1;
                }
            } else {
                _port = -1;
            }
        } catch (UnknownHostException uhe) {
            _host = null;
            _port = -1;
        }
    }
    
    public RouterAddress toRouterAddress() {
        if ( (_host == null) || (_port <= 0) ) 
            return null;
        
        RouterAddress addr = new RouterAddress();
        
        addr.setCost(10);
        addr.setExpiration(null);
        
        Properties props = new Properties();
        props.setProperty(PROP_HOST, _host);
        props.setProperty(PROP_PORT, ""+_port);
        
        addr.setOptions(props);
        addr.setTransportStyle(TCPTransport.STYLE);
        return addr;
    }
    
    public String getHost() { return _host; }
    public void setHost(String host) { _host = host; }
    public InetAddress getAddress() { return _addr; }
    public void setAddress(InetAddress addr) { _addr = addr; }
    public int getPort() { return _port; }
    public void setPort(int port) { _port = port; }
    
    public boolean isPubliclyRoutable() {
        if (_host == null) return false;
        try {
            InetAddress addr = InetAddress.getByName(_host);
            byte quad[] = addr.getAddress();
            if (quad.length != 4) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Refusing IPv6 address (" + _host + " / " + addr.getHostAddress() + ") "
                               + " since not all peers support it, and we don't support restricted routes");
                return false;
            }
            if (quad[0] == (int)127) return false;
            if (quad[0] == (int)10) return false; 
            if ( (quad[0] == (int)172) && (quad[1] >= (int)16) && (quad[1] <= (int)31) ) return false;
            if ( (quad[0] == (int)192) && (quad[1] == (int)168) ) return false;
            if (quad[0] >= (int)224) return false; // no multicast
            return true; // or at least possible to be true
        } catch (Throwable t) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error checking routability", t);
            return false;
        }
    }
    
    public String toString() { return _host + ":" + _port; }
    
    public int hashCode() {
        int rv = 0;
        rv += _port;
        if (_addr != null) 
            rv += _addr.getHostAddress().hashCode();
        else
            if (_host != null) rv += _host.hashCode();
        return rv;
    }
    
    public boolean equals(Object val) {
        if ( (val != null) && (val instanceof TCPAddress) ) {
            TCPAddress addr = (TCPAddress)val;
            if ( (_addr != null) && (_addr.getHostAddress() != null) 
                 && (addr.getAddress() != null) && (addr.getAddress().getHostAddress() != null) ) {
                return DataHelper.eq(getAddress().getHostAddress(), addr.getAddress().getHostAddress())
                       && (getPort() == addr.getPort());
            } else {
                return DataHelper.eq(getHost(), addr.getHost())
                       && (getPort() == addr.getPort());
            }
        } 
        return false;
    }
}
