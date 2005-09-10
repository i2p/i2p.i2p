package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

import net.i2p.data.Base64;
import net.i2p.data.RouterAddress;
import net.i2p.data.SessionKey;

/**
 * basic helper to parse out peer info from a udp address
 */
public class UDPAddress {
    private String _host;
    private InetAddress _hostAddress;
    private int _port;
    private byte[] _introKey;
    private String _introHosts[];
    private InetAddress _introAddresses[];
    private int _introPorts[];
    private byte[] _introKeys[];
    private long _introTags[];
    
    public static final String PROP_PORT = "port";
    public static final String PROP_HOST = "host";
    public static final String PROP_INTRO_KEY = "key";
    
    public static final String PROP_CAPACITY = "caps";
    public static final char CAPACITY_TESTING = 'B';
    public static final char CAPACITY_INTRODUCER = 'C';
    
    public static final String PROP_INTRO_HOST_PREFIX = "ihost";
    public static final String PROP_INTRO_PORT_PREFIX = "iport";
    public static final String PROP_INTRO_KEY_PREFIX = "ikey";
    public static final String PROP_INTRO_TAG_PREFIX = "itag";
    static final int MAX_INTRODUCERS = 3;

    public UDPAddress(RouterAddress addr) {
        parse(addr);
    }
    
    public String toString() {
        StringBuffer rv = new StringBuffer(64);
        rv.append("[SSU ");
        if (_host != null)
            rv.append("host: ").append(_host).append(' ');
        if (_port > 0)
            rv.append("port: ").append(_port).append(' ');
        if (_introKey != null)
            rv.append("key: ").append(Base64.encode(_introKey)).append(' ');
        if (_introHosts != null) {
            for (int i = 0; i < _introHosts.length; i++) {
                rv.append("intro[" + i + "]: ").append(_introHosts[i]);
                rv.append(':').append(_introPorts[i]);
                rv.append('/').append(Base64.encode(_introKeys[i])).append(' ');
            }
        }   
        return rv.toString();
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
        
        for (int i = MAX_INTRODUCERS; i >= 0; i--) {
            String host = opts.getProperty(PROP_INTRO_HOST_PREFIX + i);
            if (host == null) continue;
            String port = opts.getProperty(PROP_INTRO_PORT_PREFIX + i);
            if (port == null) continue;
            String k = opts.getProperty(PROP_INTRO_KEY_PREFIX + i);
            if (k == null) continue;
            byte ikey[] = Base64.decode(k);
            if ( (ikey == null) || (ikey.length != SessionKey.KEYSIZE_BYTES) )
                continue;
            String t = opts.getProperty(PROP_INTRO_TAG_PREFIX + i);
            if (t == null) continue;
            int p = -1;
            try { 
                p = Integer.parseInt(port); 
                if (p <= 0) continue;
            } catch (NumberFormatException nfe) {
                continue;
            }
            long tag = -1;
            try {
                tag = Long.parseLong(t);
                if (tag <= 0) continue;
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (_introHosts == null) {
                _introHosts = new String[i+1];
                _introPorts = new int[i+1];
                _introAddresses = new InetAddress[i+1];
                _introKeys = new byte[i+1][];
                _introTags = new long[i+1];
            }
            _introHosts[i] = host;
            _introPorts[i] = p;
            _introKeys[i] = ikey;
            _introTags[i] = tag;
        }
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
    
    public int getIntroducerCount() { return (_introAddresses == null ? 0 : _introAddresses.length); }
    public InetAddress getIntroducerHost(int i) { 
        if (_introAddresses[i] == null) {
            try {
                _introAddresses[i] = InetAddress.getByName(_introHosts[i]);
            } catch (UnknownHostException uhe) {
                _introAddresses[i] = null;
            }
        }
        return _introAddresses[i];
    }
    public int getIntroducerPort(int i) { return _introPorts[i]; }
    public byte[] getIntroducerKey(int i) { return _introKeys[i]; }
    public long getIntroducerTag(int i) { return _introTags[i]; }
        
}
