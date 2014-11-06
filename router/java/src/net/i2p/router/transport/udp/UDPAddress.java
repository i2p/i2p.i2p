package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.SessionKey;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.LHMCache;
import net.i2p.util.SystemVersion;

/**
 * basic helper to parse out peer info from a udp address
 */
class UDPAddress {
    private final String _host;
    private InetAddress _hostAddress;
    private final int _port;
    private final byte[] _introKey;
    private String _introHosts[];
    private InetAddress _introAddresses[];
    private int _introPorts[];
    private byte[] _introKeys[];
    private long _introTags[];
    private int _mtu;
    
    public static final String PROP_PORT = RouterAddress.PROP_PORT;
    public static final String PROP_HOST = RouterAddress.PROP_HOST;
    public static final String PROP_INTRO_KEY = "key";
    public static final String PROP_MTU = "mtu";
    
    public static final String PROP_CAPACITY = "caps";
    public static final char CAPACITY_TESTING = 'B';
    public static final char CAPACITY_INTRODUCER = 'C';
    
    public static final String PROP_INTRO_HOST_PREFIX = "ihost";
    public static final String PROP_INTRO_PORT_PREFIX = "iport";
    public static final String PROP_INTRO_KEY_PREFIX = "ikey";
    public static final String PROP_INTRO_TAG_PREFIX = "itag";
    static final int MAX_INTRODUCERS = 3;
    private static final String[] PROP_INTRO_HOST;
    private static final String[] PROP_INTRO_PORT;
    private static final String[] PROP_INTRO_IKEY;
    private static final String[] PROP_INTRO_TAG;
    static {
        // object churn
        PROP_INTRO_HOST = new String[MAX_INTRODUCERS];
        PROP_INTRO_PORT = new String[MAX_INTRODUCERS];
        PROP_INTRO_IKEY = new String[MAX_INTRODUCERS];
        PROP_INTRO_TAG = new String[MAX_INTRODUCERS];
        for (int i = 0; i < MAX_INTRODUCERS; i++) {
            PROP_INTRO_HOST[i] = PROP_INTRO_HOST_PREFIX + i;
            PROP_INTRO_PORT[i] = PROP_INTRO_PORT_PREFIX + i;
            PROP_INTRO_IKEY[i] = PROP_INTRO_KEY_PREFIX + i;
            PROP_INTRO_TAG[i] = PROP_INTRO_TAG_PREFIX + i;
        }
    }

    public UDPAddress(RouterAddress addr) {
        // TODO make everything final
        if (addr == null) {
            _host = null;
            _port = 0;
            _introKey = null;
            return;
        }
        _host = addr.getOption(PROP_HOST);
        _port = addr.getPort();
        try { 
            String mtu = addr.getOption(PROP_MTU);
            if (mtu != null) {
                boolean isIPv6 = _host != null && _host.contains(":");
                _mtu = MTU.rectify(isIPv6, Integer.parseInt(mtu));
            }
        } catch (NumberFormatException nfe) {}
        String key = addr.getOption(PROP_INTRO_KEY);
        if (key != null) {
            byte[] ik = Base64.decode(key.trim());
            if (ik != null && ik.length == SessionKey.KEYSIZE_BYTES)
                _introKey = ik;
            else
                _introKey = null;
        } else {
            _introKey = null;
        }
        
        for (int i = MAX_INTRODUCERS - 1; i >= 0; i--) {
            String host = addr.getOption(PROP_INTRO_HOST[i]);
            if (host == null) continue;
            String port = addr.getOption(PROP_INTRO_PORT[i]);
            if (port == null) continue;
            String k = addr.getOption(PROP_INTRO_IKEY[i]);
            if (k == null) continue;
            byte ikey[] = Base64.decode(k);
            if ( (ikey == null) || (ikey.length != SessionKey.KEYSIZE_BYTES) )
                continue;
            String t = addr.getOption(PROP_INTRO_TAG[i]);
            if (t == null) continue;
            int p;
            try { 
                p = Integer.parseInt(port); 
                if (!TransportUtil.isValidPort(p)) continue;
            } catch (NumberFormatException nfe) {
                continue;
            }
            long tag;
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
        if (_hostAddress == null)
            _hostAddress = getByName(_host);
        return _hostAddress;
    }

    /**
     *  @return 0 if unset or invalid
     */
    public int getPort() { return _port; }

    /**
     *  @return shouldn't be null but will be if invalid
     */
    byte[] getIntroKey() { return _introKey; }
    
    int getIntroducerCount() { return (_introAddresses == null ? 0 : _introAddresses.length); }

    InetAddress getIntroducerHost(int i) { 
        if (_introAddresses[i] == null)
            _introAddresses[i] = getByName(_introHosts[i]);
        return _introAddresses[i];
    }

    int getIntroducerPort(int i) { return _introPorts[i]; }

    byte[] getIntroducerKey(int i) { return _introKeys[i]; }

    long getIntroducerTag(int i) { return _introTags[i]; }
        
    /**
     *  @return 0 if unset or invalid; recitified via MTU.rectify()
     *  @since 0.9.2
     */
    int getMTU() {
        return _mtu;
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

    ////////////////
    // cache copied from Addresses.java but caching InetAddress instead of byte[]


    /**
     *  Textual IP to InetAddress, because InetAddress.getByName() is slow.
     *
     *  @since IPv6
     */
    private static final Map<String, InetAddress> _inetAddressCache;

    static {
        long maxMemory = SystemVersion.getMaxMemory();
        long min = 128;
        long max = 2048;
        // 512 nominal for 128 MB
        int size = (int) Math.max(min, Math.min(max, 1 + (maxMemory / (256*1024))));
        _inetAddressCache = new LHMCache<String, InetAddress>(size);
    }

    /**
     *  Caching version of InetAddress.getByName(host), which is slow.
     *  Caches numeric host names only.
     *  Will resolve but not cache DNS host names.
     *
     *  Unlike InetAddress.getByName(), we do NOT allow numeric IPs
     *  of the form d.d.d, d.d, or d, as these are almost certainly mistakes.
     *
     *  @param host DNS or IPv4 or IPv6 host name; if null returns null
     *  @return InetAddress or null
     *  @since IPv6
     */
    private static InetAddress getByName(String host) {
        if (host == null)
            return null;
        InetAddress rv;
        synchronized (_inetAddressCache) {
            rv = _inetAddressCache.get(host);
        }
        if (rv == null) {
            try {
                boolean isIPv4 = host.replaceAll("[0-9\\.]", "").length() == 0;
                if (isIPv4 && host.replaceAll("[0-9]", "").length() != 3)
                    return null;
                rv = InetAddress.getByName(host);
                if (isIPv4 ||
                    host.replaceAll("[0-9a-fA-F:]", "").length() == 0) {
                    synchronized (_inetAddressCache) {
                        _inetAddressCache.put(host, rv);
                    }
                }
            } catch (UnknownHostException uhe) {}
        }
        return rv;
    }

    /**
     *  @since IPv6
     */
    static void clearCache() {
        synchronized(_inetAddressCache) {
            _inetAddressCache.clear();
        }
    }
}
