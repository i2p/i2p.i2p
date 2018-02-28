package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.SessionKey;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.Addresses;
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
    private final String _introHosts[];
    private final InetAddress _introAddresses[];
    private final int _introPorts[];
    private final byte[] _introKeys[];
    private final long _introTags[];
    private final long _introExps[];
    private final int _mtu;
    
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
    /** @since 0.9.30 */
    public static final String PROP_INTRO_EXP_PREFIX = "iexp";
    static final int MAX_INTRODUCERS = 3;
    private static final String[] PROP_INTRO_HOST;
    private static final String[] PROP_INTRO_PORT;
    private static final String[] PROP_INTRO_IKEY;
    private static final String[] PROP_INTRO_TAG;
    private static final String[] PROP_INTRO_EXP;
    static {
        // object churn
        PROP_INTRO_HOST = new String[MAX_INTRODUCERS];
        PROP_INTRO_PORT = new String[MAX_INTRODUCERS];
        PROP_INTRO_IKEY = new String[MAX_INTRODUCERS];
        PROP_INTRO_TAG = new String[MAX_INTRODUCERS];
        PROP_INTRO_EXP = new String[MAX_INTRODUCERS];
        for (int i = 0; i < MAX_INTRODUCERS; i++) {
            PROP_INTRO_HOST[i] = PROP_INTRO_HOST_PREFIX + i;
            PROP_INTRO_PORT[i] = PROP_INTRO_PORT_PREFIX + i;
            PROP_INTRO_IKEY[i] = PROP_INTRO_KEY_PREFIX + i;
            PROP_INTRO_TAG[i] = PROP_INTRO_TAG_PREFIX + i;
            PROP_INTRO_EXP[i] = PROP_INTRO_EXP_PREFIX + i;
        }
    }

    public UDPAddress(RouterAddress addr) {
        if (addr == null) {
            _host = null;
            _port = 0;
            _introKey = null;
            _introHosts = null;
            _introAddresses = null;
            _introPorts = null;
            _introKeys = null;
            _introTags = null;
            _introExps = null;
            _mtu = 0;
            return;
        }
        _host = addr.getHost();
        _port = addr.getPort();

        int cmtu = 0;
        try { 
            String mtu = addr.getOption(PROP_MTU);
            if (mtu != null) {
                boolean isIPv6 = _host != null && _host.contains(":");
                cmtu = MTU.rectify(isIPv6, Integer.parseInt(mtu));
            }
        } catch (NumberFormatException nfe) {}
        _mtu = cmtu;

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
        
        byte[][] cintroKeys = null;
        long[] cintroTags = null;
        int[] cintroPorts = null;
        String[] cintroHosts = null;
        InetAddress[] cintroAddresses = null;
        long[] cintroExps = null;
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
            // expiration is optional
            long exp = 0;
            t = addr.getOption(PROP_INTRO_EXP[i]);
            if (t != null) {
                try {
                    exp = Long.parseLong(t) * 1000L;
                } catch (NumberFormatException nfe) {}
            }

            if (cintroHosts == null) {
                cintroHosts = new String[i+1];
                cintroPorts = new int[i+1];
                cintroAddresses = new InetAddress[i+1];
                cintroKeys = new byte[i+1][];
                cintroTags = new long[i+1];
                cintroExps = new long[i+1];
            }
            cintroHosts[i] = host;
            cintroPorts[i] = p;
            cintroKeys[i] = ikey;
            cintroTags[i] = tag;
            cintroExps[i] = exp;
        }
        
        int numOK = 0;
        if (cintroHosts != null) {
            // Validate the intro parameters, and shrink the
            // introAddresses array if they aren't all valid,
            // since we use the length for the valid count.
            // We don't bother shrinking the other arrays,
            // we just remove the invalid entries.
            for (int i = 0; i < cintroHosts.length; i++) {
                if ( (cintroKeys[i] != null) && 
                     (cintroPorts[i] > 0) &&
                     (cintroTags[i] > 0) &&
                     (cintroHosts[i] != null) )
                    numOK++;
            }
            if (numOK != cintroHosts.length) {
                int cur = 0;
                for (int i = 0; i < cintroHosts.length; i++) {
                    if ( (cintroKeys[i] != null) && 
                         (cintroPorts[i] > 0) &&
                         (cintroTags[i] > 0) &&
                         (cintroHosts[i] != null) ) {
                        if (cur != i) {
                            // just shift these down
                            cintroHosts[cur] = cintroHosts[i];
                            cintroPorts[cur] = cintroPorts[i];
                            cintroTags[cur] = cintroTags[i];
                            cintroKeys[cur] = cintroKeys[i];
                            cintroExps[cur] = cintroExps[i];
                        }
                        cur++;
                    }
                }
                cintroAddresses = new InetAddress[numOK];
            }
        }
        _introKeys = cintroKeys;
        _introTags = cintroTags;
        _introPorts = cintroPorts;
        _introHosts = cintroHosts;
        _introAddresses = cintroAddresses;
        _introExps = cintroExps;
    }
    
    public String getHost() { return _host; }

    /**
     *  As of 0.9.32, will NOT resolve host names.
     *
     *  @return InetAddress or null
     */
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

    /**
     *  As of 0.9.32, will NOT resolve host names.
     *
     *  @throws NullPointerException if getIntroducerCount() == 0
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return null if invalid
     */
    InetAddress getIntroducerHost(int i) { 
        if (_introAddresses[i] == null)
            _introAddresses[i] = getByName(_introHosts[i]);
        return _introAddresses[i];
    }

    /**
     *  @throws NullPointerException if getIntroducerCount() == 0
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return greater than zero
     */
    int getIntroducerPort(int i) { return _introPorts[i]; }

    /**
     *  @throws NullPointerException if getIntroducerCount() == 0
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return non-null
     */
    byte[] getIntroducerKey(int i) { return _introKeys[i]; }

    /**
     *  @throws NullPointerException if getIntroducerCount() == 0
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return greater than zero
     */
    long getIntroducerTag(int i) { return _introTags[i]; }

    /**
     *  @throws NullPointerException if getIntroducerCount() == 0
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return ms since epoch, zero if unset
     *  @since 0.9.30
     */
    long getIntroducerExpiration(int i) { return _introExps[i]; }
        
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
     *  Caches numeric IPs only.
     *  As of 0.9.32, will NOT resolve host names.
     *
     *  Unlike InetAddress.getByName(), we do NOT allow numeric IPs
     *  of the form d.d.d, d.d, or d, as these are almost certainly mistakes.
     *
     *  @param host literal IPv4 or IPv6; if null or hostname, returns null
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
            if (Addresses.isIPAddress(host)) {
                try {
                    rv = InetAddress.getByName(host);
                    synchronized (_inetAddressCache) {
                        _inetAddressCache.put(host, rv);
                    }
                } catch (UnknownHostException uhe) {}
            }
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
