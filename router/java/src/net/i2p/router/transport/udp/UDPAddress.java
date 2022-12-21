package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
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
    private final Hash _introHashes[];
    private final int _mtu;
    // could be both
    private final boolean _isIPv4, _isIPv6;
    
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
    /** @since 0.9.55 for SSU2 */
    public static final String PROP_INTRO_HASH_PREFIX = "ih";
    static final int MAX_INTRODUCERS = 3;
    private static final String[] PROP_INTRO_HOST;
    private static final String[] PROP_INTRO_PORT;
    private static final String[] PROP_INTRO_IKEY;
    static final String[] PROP_INTRO_TAG;
    private static final String[] PROP_INTRO_EXP;
    private static final String[] PROP_INTRO_HASH;
    static {
        // object churn
        PROP_INTRO_HOST = new String[MAX_INTRODUCERS];
        PROP_INTRO_PORT = new String[MAX_INTRODUCERS];
        PROP_INTRO_IKEY = new String[MAX_INTRODUCERS];
        PROP_INTRO_TAG = new String[MAX_INTRODUCERS];
        PROP_INTRO_EXP = new String[MAX_INTRODUCERS];
        PROP_INTRO_HASH = new String[MAX_INTRODUCERS];
        for (int i = 0; i < MAX_INTRODUCERS; i++) {
            PROP_INTRO_HOST[i] = PROP_INTRO_HOST_PREFIX + i;
            PROP_INTRO_PORT[i] = PROP_INTRO_PORT_PREFIX + i;
            PROP_INTRO_IKEY[i] = PROP_INTRO_KEY_PREFIX + i;
            PROP_INTRO_TAG[i] = PROP_INTRO_TAG_PREFIX + i;
            PROP_INTRO_EXP[i] = PROP_INTRO_EXP_PREFIX + i;
            PROP_INTRO_HASH[i] = PROP_INTRO_HASH_PREFIX + i;
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
            _introHashes = null;
            _mtu = 0;
            _isIPv4 = false;
            _isIPv6 = false;
            return;
        }
        _host = addr.getHost();
        _port = addr.getPort();
        String caps = addr.getOption(PROP_CAPACITY);
        _isIPv4 = (_host != null && _host.contains(".")) || (caps != null && caps.contains("4"));
        _isIPv6 = (_host != null && _host.contains(":")) || (caps != null && caps.contains("6"));

        final boolean ssu2only = addr.getTransportStyle().equals("SSU2");
        int cmtu = 0;
        try { 
            String mtu = addr.getOption(PROP_MTU);
            if (mtu != null) {
                int imtu = Integer.parseInt(mtu);
                boolean isIPv6 = _host != null && _host.contains(":");
                if (isIPv6 && imtu > 1420) {
                    // fix for brokered tunnels with too big MTU
                    if (imtu > 1472 && _host.startsWith("2001:470:"))
                        imtu = 1472;
                    else if (_host.startsWith("2a06:a004:"))
                        imtu = 1420;
                }
                if (ssu2only) {
                    // 1280 min is not enforced here, so that it may be
                    // rejected in OES2 constructor and IES2.gotRI()
                    cmtu = Math.min(imtu, PeerState2.MAX_MTU);
                } else {
                    cmtu = MTU.rectify(isIPv6, imtu);
                }
            } else if (_host != null) {
                if (_host.startsWith("2001:470:"))
                    cmtu = 1472;
                else if (_host.startsWith("2a06:a004:"))
                    cmtu = 1420;
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
        Hash[] cintroHashes = null;
        final boolean ssu2enable = ssu2only || "2".equals(addr.getOption("v"));
        for (int i = MAX_INTRODUCERS - 1; i >= 0; i--) {
            // This is the only one required for SSU 1 and 2
            String t = addr.getOption(PROP_INTRO_TAG[i]);
            if (t == null) continue;
            long tag;
            try {
                tag = Long.parseLong(t);
                if (tag <= 0) continue;
            } catch (NumberFormatException nfe) {
                continue;
            }

            // SSU2 only
            Hash hash = null;
            if (ssu2enable) {
                String shash = addr.getOption(PROP_INTRO_HASH[i]);
                if (shash != null) {
                    byte[] bhash = Base64.decode(shash);
                    if (bhash != null && bhash.length == Hash.HASH_LENGTH) {
                        hash = Hash.create(bhash);
                    }
                }
            }

            // following 3 for SSU 1 only, won't be present for SSU2 address
            String host;
            int p;
            byte[] ikey;
            if (ssu2only) {
                if (hash == null)
                    continue;
                host = null;
                ikey = null;
                p = 0;
            } else {
                if (hash != null) {
                    // SSU 2
                    host = null;
                    ikey = null;
                    p = 0;
                } else {
                    // SSU 1
                    host = addr.getOption(PROP_INTRO_HOST[i]);
                    if (host == null) continue;
                    String port = addr.getOption(PROP_INTRO_PORT[i]);
                    if (port == null) continue;
                    String k = addr.getOption(PROP_INTRO_IKEY[i]);
                    if (k == null) continue;
                    ikey = Base64.decode(k);
                    if ( (ikey == null) || (ikey.length != SessionKey.KEYSIZE_BYTES) )
                        continue;
                    try {
                        p = Integer.parseInt(port);
                        if (!TransportUtil.isValidPort(p)) continue;
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                }
            }
            // expiration is optional
            long exp = 0;
            t = addr.getOption(PROP_INTRO_EXP[i]);
            if (t != null) {
                try {
                    exp = Long.parseLong(t) * 1000L;
                } catch (NumberFormatException nfe) {}
            }

            if (cintroTags == null) {
                if (!ssu2only) {
                    cintroHosts = new String[i+1];
                    cintroPorts = new int[i+1];
                    cintroAddresses = new InetAddress[i+1];
                    cintroKeys = new byte[i+1][];
                }
                cintroTags = new long[i+1];
                cintroExps = new long[i+1];
                if (ssu2enable)
                    cintroHashes = new Hash[i+1];
            }
            if (!ssu2only) {
                cintroHosts[i] = host;
                cintroPorts[i] = p;
                cintroKeys[i] = ikey;
            }
            cintroTags[i] = tag;
            cintroExps[i] = exp;
            if (ssu2enable)
                cintroHashes[i] = hash;
        }

        int numOK = 0;
        if (cintroTags != null) {
            // Validate the intro parameters, and shrink the
            // introTags array if they aren't all valid,
            // since we use the length for the valid count.
            // We don't bother shrinking the other arrays,
            // we just remove the invalid entries.
            for (int i = 0; i < cintroTags.length; i++) {
                if (cintroTags[i] > 0 &&
                    ((cintroHashes != null && cintroHashes[i] != null) ||
                     (cintroKeys != null && cintroKeys[i] != null &&
                      cintroPorts[i] > 0 &&
                      cintroHosts[i] != null)))
                    numOK++;
            }
            if (numOK != cintroTags.length) {
                int cur = 0;
                for (int i = 0; i < cintroTags.length; i++) {
                    if (cintroTags[i] > 0 &&
                        ((cintroHashes != null && cintroHashes[i] != null) ||
                         (cintroKeys != null && cintroKeys[i] != null &&
                          cintroPorts[i] > 0 &&
                          cintroHosts[i] != null))) {
                        if (cur != i) {
                            // just shift these down
                            if (cintroKeys != null) {
                                cintroKeys[cur] = cintroKeys[i];
                                cintroHosts[cur] = cintroHosts[i];
                                cintroPorts[cur] = cintroPorts[i];
                            }
                            cintroTags[cur] = cintroTags[i];
                            cintroExps[cur] = cintroExps[i];
                        }
                        cur++;
                    }
                }
                cintroTags = Arrays.copyOfRange(cintroTags, 0, numOK);
            }
        }
        _introKeys = cintroKeys;
        _introTags = cintroTags;
        _introPorts = cintroPorts;
        _introHosts = cintroHosts;
        _introAddresses = cintroAddresses;
        _introExps = cintroExps;
        _introHashes = cintroHashes;
    }
    
    public String getHost() { return _host; }

    /**
     *  As of 0.9.32, will NOT resolve hostnames.
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
    
    int getIntroducerCount() { return (_introTags == null ? 0 : _introTags.length); }

    /**
     *  As of 0.9.32, will NOT resolve hostnames.
     *
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return null if invalid or for SSU2
     */
    InetAddress getIntroducerHost(int i) { 
        if (_introAddresses == null)
            return null;
        if (_introAddresses[i] == null)
            _introAddresses[i] = getByName(_introHosts[i]);
        return _introAddresses[i];
    }

    /**
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return greater than zero or zero for SSU2
     */
    int getIntroducerPort(int i) { return _introPorts != null ? _introPorts[i] : 0; }

    /**
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return null if no keys or for SSU2
     */
    byte[] getIntroducerKey(int i) { return _introKeys != null ? _introKeys[i] : null; }

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
     *  @throws ArrayIndexOutOfBoundsException if i &lt; 0 or i &gt;= getIntroducerCount()
     *  @return null if no keys or for SSU1
     *  @since 0.9.55
     */
    Hash getIntroducerHash(int i) { return _introHashes != null ? _introHashes[i] : null; }

    /**
     *  @since 0.9.55
     */
    boolean isIPv4() { return _isIPv4; }

    /**
     *  @since 0.9.55
     */
    boolean isIPv6() { return _isIPv6; }
        
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
     *  As of 0.9.32, will NOT resolve hostnames.
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

/*
    public static void main(String[] args) {
        net.i2p.util.OrderedProperties opts = new net.i2p.util.OrderedProperties();
        opts.setProperty("caps", "B6");
        opts.setProperty("i", "lkjlierjsdkljglksdjlkgj~jifxg-fFhdp-~HDLJo4=");
        opts.setProperty("iexp0", "1");
        opts.setProperty("iexp1", "6");
        opts.setProperty("iexp2", "5");
        opts.setProperty("ih2", "kjshfkjshfkjshfkjsdhfkjsdfs6XYi9HbyYO4OllX0=");
        opts.setProperty("ihost0", "9999:8888:1:6:0:0:0:0");
        opts.setProperty("ihost1", "1.2.3.4");
        opts.setProperty("ikey0", "3lksjdflksjflksdjfzLrlABjJf5RcKyG2zSm-qUNqQ=");
        opts.setProperty("ikey1", "lskjflksjflksdjfQobejJ~Y2QgPNliBhWfDZ3f0icA=");
        opts.setProperty("iport0", "11114");
        opts.setProperty("iport1", "11118");
        opts.setProperty("itag0", "3");
        opts.setProperty("itag1", "5");
        opts.setProperty("itag2", "1");
        opts.setProperty("key", "ioerutoieutoieruotiuertoi8fABtTLXZaSVyE1STk=");
        opts.setProperty("s", "iouwtoiuwoiutkkjsdlkjfiuwer2Zou3ad60Kgx1cD4=");
        opts.setProperty("v", "2");
        RouterAddress ra = new RouterAddress("SSU", opts, 5);
        UDPAddress ua = new UDPAddress(ra);
        System.out.println("Introducer count is " + ua.getIntroducerCount());
    }
*/
}
