package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

import net.i2p.data.Base64;
import net.i2p.data.RouterAddress;

/**
 * basic helper to parse out peer info from a udp address
 */
public class UDPAddress {
    private String _host;
    private InetAddress _hostAddress;
    private int _port;
    private byte[] _introKey;
    
    public static final String PROP_PORT = "port";
    public static final String PROP_HOST = "host";
    public static final String PROP_INTRO_KEY = "key";
    
    public static final String PROP_CAPACITY = "caps";
    public static final char CAPACITY_TESTING = 'B';
    public static final char CAPACITY_INTRODUCER = 'C';

    public UDPAddress(RouterAddress addr) {
        parse(addr);
    }
    
    private void parse(RouterAddress addr) {
        Properties opts = addr.getOptions();
        _host = opts.getProperty(PROP_HOST);
        if (_host != null) _host = _host.trim();
        try { 
            String port = opts.getProperty(PROP_PORT);
            if (port != null)
                _port = Integer.parseInt(port);
        } catch (NumberFormatException nfe) {
            _port = -1;
        }
        String key = opts.getProperty(PROP_INTRO_KEY);
        if (key != null)
            _introKey = Base64.decode(key.trim());
    }
    
    public String getHost() { return _host; }
    public InetAddress getHostAddress() {
        if (_hostAddress == null) {
            try {
                _hostAddress = InetAddress.getByName(_host);
            } catch (UnknownHostException uhe) {
                _hostAddress = null;
            }
        }
        return _hostAddress;
    }
    public int getPort() { return _port; }
    public byte[] getIntroKey() { return _introKey; }
}
