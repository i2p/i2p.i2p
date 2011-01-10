package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

import net.i2p.data.Base64;
import net.i2p.data.RouterAddress;
import net.i2p.data.SessionKey;

/**
 * basic helper to parse out peer info from a udp address
 * FIXME public for ConfigNetHelper
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
    
    @Override
    public String toString() {
        StringBuilder rv = new StringBuilder(64);
        if (_introHosts != null) {
            for (int i = 0; i < _introHosts.length; i++) {
                rv.append("ssu://");
                rv.append(_introTags[i]).append('@');
                rv.append(_introHosts[i]).append(':').append(_introPorts[i]);
                //rv.append('/').append(Base64.encode(_introKeys[i]));
                if (i + 1 < _introKeys.length)
                    rv.append(", ");
            }
        } else {
            if ( (_host != null) && (_port > 0) )
                rv.append("ssu://").append(_host).append(':').append(_port);//.append('/').append(Base64.encode(_introKey));
            else
                rv.append("ssu://autodetect.not.yet.complete:").append(_port);
        }
        return rv.toString();
    }
    
    private void parse(RouterAddress addr) {
        if (addr == null) return;
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
        
        int numOK = 0;
        if (_introHosts != null) {
            for (int i = 0; i < _introHosts.length; i++) {
                if ( (_introKeys[i] != null) && 
                     (_introPorts[i] > 0) &&
                     (_introTags[i] > 0) &&
                     (_introHosts[i] != null) )
                    numOK++;
            }
            if (numOK != _introHosts.length) {
                String hosts[] = new String[numOK];
                int ports[] = new int[numOK];
                long tags[] = new long[numOK];
                byte keys[][] = new byte[numOK][];
                int cur = 0;
                for (int i = 0; i < _introHosts.length; i++) {
                    if ( (_introKeys[i] != null) && 
                         (_introPorts[i] > 0) &&
                         (_introTags[i] > 0) &&
                         (_introHosts[i] != null) ) {
                        hosts[cur] = _introHosts[i];
                        ports[cur] = _introPorts[i];
                        tags[cur] = _introTags[i];
                        keys[cur] = _introKeys[i];
                    }
                }
                _introKeys = keys;
                _introTags = tags;
                _introPorts = ports;
                _introHosts = hosts;
                _introAddresses = new InetAddress[numOK];
            }
        }
    }
    
    public String getHost() { return _host; }
    InetAddress getHostAddress() {
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
    byte[] getIntroKey() { return _introKey; }
    
    int getIntroducerCount() { return (_introAddresses == null ? 0 : _introAddresses.length); }
    InetAddress getIntroducerHost(int i) { 
        if (_introAddresses[i] == null) {
            try {
                _introAddresses[i] = InetAddress.getByName(_introHosts[i]);
            } catch (UnknownHostException uhe) {
                _introAddresses[i] = null;
            }
        }
        return _introAddresses[i];
    }
    int getIntroducerPort(int i) { return _introPorts[i]; }
    byte[] getIntroducerKey(int i) { return _introKeys[i]; }
    long getIntroducerTag(int i) { return _introTags[i]; }
        
}
