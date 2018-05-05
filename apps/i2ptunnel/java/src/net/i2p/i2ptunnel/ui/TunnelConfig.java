package net.i2p.i2ptunnel.ui;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.data.SimpleDataStructure;
import net.i2p.i2ptunnel.I2PTunnelClientBase;
import net.i2p.i2ptunnel.I2PTunnelConnectClient;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.i2ptunnel.I2PTunnelHTTPServer;
import net.i2p.i2ptunnel.I2PTunnelIRCClient;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.PasswordManager;

/**
 * Helper class to generate a valid TunnelController configuration from provided
 * settings.
 *
 * This class is also used by Android.
 * Maintain as a stable API and take care not to break Android.
 *
 * @since 0.9.19 logic moved from IndexBean
 */
public class TunnelConfig {
    public static final String SHARED_CLIENT_NICKNAME = "shared clients";

    private static final String OPT = TunnelController.PFX_OPTION;

    protected final I2PAppContext _context;

    private String _type;
    private String _name;
    private String _description;
    private String _i2cpHost;
    private String _i2cpPort;
    private int _tunnelDepth = -1;
    private int _tunnelQuantity = -1;
    // -2 or higher is valid
    private int _tunnelVariance = -3;
    private int _tunnelBackupQuantity = -1;
    private int _tunnelDepthOut = -1;
    private int _tunnelQuantityOut = -1;
    private int _tunnelVarianceOut = -3;
    private int _tunnelBackupQuantityOut = -1;
    private boolean _connectDelay;
    private String _customOptions;
    private String _proxyList;
    private int _port = -1;
    private String _reachableBy;
    private String _targetDestination;
    private String _targetHost;
    private int _targetPort = -1;
    private String _spoofedHost;
    private String _privKeyFile;
    private String _profile;
    private boolean _startOnLoad;
    private boolean _sharedClient;
    private final Set<String> _booleanOptions;
    private final Map<String, String> _otherOptions;
    private String _newProxyUser;
    private String _newProxyPW;
    private Destination _dest;

    public TunnelConfig() {
        _context = I2PAppContext.getGlobalContext();
        _booleanOptions = new ConcurrentHashSet<String>(4);
        _otherOptions = new ConcurrentHashMap<String, String>(4);
    }

    /**
     * What type of tunnel (httpclient, ircclient, client, or server).  This is 
     * required when adding a new tunnel.
     *
     */
    public void setType(String type) { 
        _type = (type != null ? type.trim() : null);   
    }
    public String getType() {
        return _type;
    }

    /** Short name of the tunnel */
    public void setName(String name) { 
        _name = (name != null ? name.trim() : null);
    }
    /** one line description */
    public void setDescription(String description) { 
        // '#' will blow up DataHelper.storeProps()
        _description = (description != null ? description.replace('#', ' ').trim() : null);
    }
    /** I2CP host the router is on, ignored when in router context */
    public void setClientHost(String host) {
        _i2cpHost = (host != null ? host.trim() : null);
    }
    /** I2CP port the router is on, ignored when in router context */
    public void setClientPort(String port) {
        _i2cpPort = (port != null ? port.trim() : null);
    }

    /** how many hops to use for inbound tunnels
     *  In or both in/out
     */
    public void setTunnelDepth(int tunnelDepth) { 
        _tunnelDepth = tunnelDepth;
    }

    /** how many parallel inbound tunnels to use
     *  In or both in/out
     */
    public void setTunnelQuantity(int tunnelQuantity) { 
        _tunnelQuantity = tunnelQuantity;
    }

    /** how much randomisation to apply to the depth of tunnels
     *  In or both in/out
     */
    public void setTunnelVariance(int tunnelVariance) { 
        _tunnelVariance = tunnelVariance;
    }

    /** how many tunnels to hold in reserve to guard against failures
     *  In or both in/out
     */
    public void setTunnelBackupQuantity(int tunnelBackupQuantity) { 
        _tunnelBackupQuantity = tunnelBackupQuantity;
    }

    /** how many hops to use for outbound tunnels
     *  @since 0.9.33
     */
    public void setTunnelDepthOut(int tunnelDepth) { 
        _tunnelDepthOut = tunnelDepth;
    }

    /** how many parallel outbound tunnels to use
     *  @since 0.9.33
     */
    public void setTunnelQuantityOut(int tunnelQuantity) { 
        _tunnelQuantityOut = tunnelQuantity;
    }

    /** how much randomisation to apply to the depth of tunnels
     *  @since 0.9.33
     */
    public void setTunnelVarianceOut(int tunnelVariance) { 
        _tunnelVarianceOut = tunnelVariance;
    }

    /** how many tunnels to hold in reserve to guard against failures
     *  @since 0.9.33
     */
    public void setTunnelBackupQuantityOut(int tunnelBackupQuantity) { 
        _tunnelBackupQuantityOut = tunnelBackupQuantity;
    }

    /** what I2P session overrides should be used */
    public void setCustomOptions(String customOptions) { 
        _customOptions = (customOptions != null ? customOptions.trim() : null);
    }
    /** what HTTP outproxies should be used (httpclient specific) */
    public void setProxyList(String proxyList) { 
        _proxyList = (proxyList != null ? proxyList.trim() : null);
    }
    /** what port should this client/httpclient/ircclient listen on */
    public void setPort(int port) { 
        _port = port;
    }
    /** 
     * what interface should this client/httpclient/ircclient listen on
     */
    public void setReachableBy(String reachableBy) { 
        _reachableBy = (reachableBy != null ? reachableBy.trim() : null);
    }
    /** What peer does this client tunnel point at */
    public void setTargetDestination(String dest) { 
        _targetDestination = (dest != null ? dest.trim() : null);
    }
    /** What host does this server tunnel point at */
    public void setTargetHost(String host) { 
        _targetHost = (host != null ? host.trim() : null);
    }
    /** What port does this server tunnel point at */
    public void setTargetPort(int port) { 
        _targetPort = port;
    }
    /** What host does this http server tunnel spoof */
    public void setSpoofedHost(String host) { 
        _spoofedHost = (host != null ? host.trim() : null);
    }
    /** What filename is this server tunnel's private keys stored in */
    public void setPrivKeyFile(String file) { 
        _privKeyFile = (file != null ? file.trim() : null);
    }
    public String getPrivKeyFile() {
        return _privKeyFile;
    }

    /**
     *  What filename is this server tunnel's alternate private keys stored in
     *  @since 0.9.30
     */
    public void setAltPrivKeyFile(String file) { 
        if (file != null)
            _otherOptions.put(I2PTunnelServer.PROP_ALT_PKF, file.trim());
    }

    /**
     * If called with any value, we want this tunnel to start whenever it is
     * loaded (aka right now and whenever the router is started up)
     */
    public void setStartOnLoad(boolean val) {
        _startOnLoad = val;
    }
    public void setShared(boolean val) {
        _sharedClient = val;
    }
    public void setConnectDelay(boolean val) {
        _connectDelay = val;
    }
    public void setProfile(String profile) { 
        _profile = profile; 
    }

    public void setReduce(boolean val) {
        if (val)
            _booleanOptions.add("i2cp.reduceOnIdle");
        else
            _booleanOptions.remove("i2cp.reduceOnIdle");
    }
    public void setClose(boolean val) {
        if (val)
            _booleanOptions.add("i2cp.closeOnIdle");
        else
            _booleanOptions.remove("i2cp.closeOnIdle");
    }
    public void setEncrypt(boolean val) {
        if (val)
            _booleanOptions.add("i2cp.encryptLeaseSet");
        else
            _booleanOptions.remove("i2cp.encryptLeaseSet");
    }
    public void setDCC(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelIRCClient.PROP_DCC);
        else
            _booleanOptions.remove(I2PTunnelIRCClient.PROP_DCC);
    }
    public void setUseSSL(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelServer.PROP_USE_SSL);
        else
            _booleanOptions.remove(I2PTunnelServer.PROP_USE_SSL);
    }
    public void setRejectInproxy(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPServer.OPT_REJECT_INPROXY);
        else
            _booleanOptions.remove(I2PTunnelHTTPServer.OPT_REJECT_INPROXY);
    }

    /** @since 0.9.25 */
    public void setRejectReferer(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPServer.OPT_REJECT_REFERER);
        else
            _booleanOptions.remove(I2PTunnelHTTPServer.OPT_REJECT_REFERER);
    }

    /** @since 0.9.25 */
    public void setRejectUserAgents(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPServer.OPT_REJECT_USER_AGENTS);
        else
            _booleanOptions.remove(I2PTunnelHTTPServer.OPT_REJECT_USER_AGENTS);
    }

    /** @since 0.9.25 */
    public void setUserAgents(String val) {
        if (val != null)
            _otherOptions.put(I2PTunnelHTTPServer.OPT_USER_AGENTS, val.trim());
    }

    public void setUniqueLocal(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelServer.PROP_UNIQUE_LOCAL);
        else
            _booleanOptions.remove(I2PTunnelServer.PROP_UNIQUE_LOCAL);
    }

    protected static final String PROP_ENABLE_ACCESS_LIST = "i2cp.enableAccessList";
    protected static final String PROP_ENABLE_BLACKLIST = "i2cp.enableBlackList";

    /**
     * Controls how other tunnels are checked for access.
     * <p>
     * The list used for whitelisting/blacklisting can be set with
     * {@link #setAccessList(String)}.
     *
     * @param mode 0 for no control, 1 for whitelist, 2 for blacklist 
     */
    public void setAccessMode(int mode) {
        switch (mode) {
        case 1:
            _booleanOptions.add(PROP_ENABLE_ACCESS_LIST);
            _booleanOptions.remove(PROP_ENABLE_BLACKLIST);
            break;
        case 2:
            _booleanOptions.remove(PROP_ENABLE_ACCESS_LIST);
            _booleanOptions.add(PROP_ENABLE_BLACKLIST);
            break;
        default:
            _booleanOptions.remove(PROP_ENABLE_ACCESS_LIST);
            _booleanOptions.remove(PROP_ENABLE_BLACKLIST);
        }
    }

    public void setDelayOpen(boolean val) {
        if (val)
            _booleanOptions.add("i2cp.delayOpen");
        else
            _booleanOptions.remove("i2cp.delayOpen");
    }

    /**
     * Controls how ephemeral the I2P Destination of a client tunnel is.
     * <p>
     * If {@link #setClose(boolean)} is set to false then mode 1 == mode 0.
     * 
     * @param mode 0 for new dest on restart, 1 for new dest on resume from idle, 2 for persistent key
     */
    public void setNewDest(int mode) {
        switch (mode) {
        case 1:
            _booleanOptions.add("i2cp.newDestOnResume");
            _booleanOptions.remove("persistentClientKey");
            break;
        case 2:
            _booleanOptions.remove("i2cp.newDestOnResume");
            _booleanOptions.add("persistentClientKey");
            break;
        default:
            _booleanOptions.remove("i2cp.newDestOnResume");
            _booleanOptions.remove("persistentClientKey");
        }
    }

    public void setReduceTime(int val) {
        _otherOptions.put("i2cp.reduceIdleTime", Integer.toString(val * 60*1000));
    }
    public void setReduceCount(int val) {
        _otherOptions.put("i2cp.reduceQuantity", Integer.toString(val));
    }
    public void setEncryptKey(String val) {
        if (val != null)
            _otherOptions.put("i2cp.leaseSetKey", val.trim());
    }

    public void setAccessList(String val) {
        if (val != null)
            _otherOptions.put("i2cp.accessList", val.trim().replace("\r\n", ",").replace("\n", ",").replace(" ", ","));
    }

    public void setJumpList(String val) {
        if (val != null)
            _otherOptions.put(I2PTunnelHTTPClient.PROP_JUMP_SERVERS, val.trim().replace("\r\n", ",").replace("\n", ",").replace(" ", ","));
    }

    public void setCloseTime(int val) {
        _otherOptions.put("i2cp.closeIdleTime", Integer.toString(val * 60*1000));
    }

    public void setAllowUserAgent(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPClient.PROP_USER_AGENT);
        else
            _booleanOptions.remove(I2PTunnelHTTPClient.PROP_USER_AGENT);
    }
    public void setAllowReferer(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPClient.PROP_REFERER);
        else
            _booleanOptions.remove(I2PTunnelHTTPClient.PROP_REFERER);
    }
    public void setAllowAccept(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPClient.PROP_ACCEPT);
        else
            _booleanOptions.remove(I2PTunnelHTTPClient.PROP_ACCEPT);
    }
    public void setAllowInternalSSL(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPClient.PROP_INTERNAL_SSL);
        else
            _booleanOptions.remove(I2PTunnelHTTPClient.PROP_INTERNAL_SSL);
    }

    public void setMultihome(boolean val) {
        if (val)
            _booleanOptions.add("shouldBundleReplyInfo");
        else
            _booleanOptions.remove("shouldBundleReplyInfo");
    }

    /**
     * Sets whether authentication should be used for client proxy tunnels.
     * Supported authentication types: "basic", "digest".
     *
     * @param authType the authentication type, or "false" for no authentication
     */
    public void setProxyAuth(String authType) {
        if (authType != null)
            _otherOptions.put(I2PTunnelHTTPClientBase.PROP_AUTH, authType.trim());
    }
    
    public void setProxyUsername(String s) {
        if (s != null)
            _newProxyUser = s.trim();
    }

    public void setProxyPassword(String s) {
        if (s != null)
            _newProxyPW = s.trim();
    }

    /**
     * Sets whether authentication is required for any of the configured
     * outproxies.
     *
     * @param val true if authentication is required, false otherwise
     */
    public void setOutproxyAuth(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH);
        else
            _booleanOptions.remove(I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH);
    }

    public void setOutproxyUsername(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER, s.trim());
    }
    
    public void setOutproxyPassword(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW, s.trim());
    }

    public void setSslProxies(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPClient.PROP_SSL_OUTPROXIES, s.trim().replace(" ", ","));
    }

    public void setUseOutproxyPlugin(boolean val) {
        if (val)
            _booleanOptions.add(I2PTunnelHTTPClientBase.PROP_USE_OUTPROXY_PLUGIN);
        else
            _booleanOptions.remove(I2PTunnelHTTPClientBase.PROP_USE_OUTPROXY_PLUGIN);
    }
    
    /**
     * all of these are @since 0.8.3 (moved from IndexBean)
     */
    public static final String PROP_MAX_CONNS_MIN = TunnelController.PROP_MAX_CONNS_MIN;
    public static final String PROP_MAX_CONNS_HOUR = TunnelController.PROP_MAX_CONNS_HOUR;
    public static final String PROP_MAX_CONNS_DAY = TunnelController.PROP_MAX_CONNS_DAY;
    public static final String PROP_MAX_TOTAL_CONNS_MIN = TunnelController.PROP_MAX_TOTAL_CONNS_MIN;
    public static final String PROP_MAX_TOTAL_CONNS_HOUR = TunnelController.PROP_MAX_TOTAL_CONNS_HOUR;
    public static final String PROP_MAX_TOTAL_CONNS_DAY = TunnelController.PROP_MAX_TOTAL_CONNS_DAY;
    public static final String PROP_MAX_STREAMS = TunnelController.PROP_MAX_STREAMS;

    public void setLimitMinute(int val) {
        _otherOptions.put(PROP_MAX_CONNS_MIN, Integer.toString(val));
    }

    public void setLimitHour(int val) {
        _otherOptions.put(PROP_MAX_CONNS_HOUR, Integer.toString(val));
    }

    public void setLimitDay(int val) {
        _otherOptions.put(PROP_MAX_CONNS_DAY, Integer.toString(val));
    }

    public void setTotalMinute(int val) {
        _otherOptions.put(PROP_MAX_TOTAL_CONNS_MIN, Integer.toString(val));
    }

    public void setTotalHour(int val) {
        _otherOptions.put(PROP_MAX_TOTAL_CONNS_HOUR, Integer.toString(val));
    }

    public void setTotalDay(int val) {
        _otherOptions.put(PROP_MAX_TOTAL_CONNS_DAY, Integer.toString(val));
    }

    public void setMaxStreams(int val) {
        _otherOptions.put(PROP_MAX_STREAMS, Integer.toString(val));
    }

    /**
     * POST limits
     */
    public void setPostMax(int val) {
        _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_MAX, Integer.toString(val));
    }

    public void setPostTotalMax(int val) {
        _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_TOTAL_MAX, Integer.toString(val));
    }

    public void setPostCheckTime(int val) {
        _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_WINDOW, Integer.toString(val * 60));
    }

    public void setPostBanTime(int val) {
        _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_BAN_TIME, Integer.toString(val * 60));
    }

    public void setPostTotalBanTime(int val) {
        _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_TOTAL_BAN_TIME, Integer.toString(val * 60));
    }

    public void setSigType(String val) {
        if (val != null)
            _otherOptions.put(I2PClient.PROP_SIGTYPE, val.trim());
    }

    /**
     * Random keys
     */
    public void setInboundRandomKey(String s) {
        if (s != null)
            _otherOptions.put("inbound.randomKey", s.trim());
    }

    public void setOutboundRandomKey(String s) {
        if (s != null)
            _otherOptions.put("outbound.randomKey", s.trim());
    }

    public void setLeaseSetSigningPrivateKey(String s) {
        if (s != null)
            _otherOptions.put("i2cp.leaseSetSigningPrivateKey", s.trim());
    }

    public void setLeaseSetPrivateKey(String s) {
        if (s != null)
            _otherOptions.put("i2cp.leaseSetPrivateKey", s.trim());
    }

    /**
     * This is easier than requiring TunnelConfig to talk to
     * TunnelControllerGroup and TunnelController.
     *
     * @param dest the current Destination for this tunnel.
     */
    public void setDestination(Destination dest) {
        _dest = dest;
    }

    /**
     * Based on all provided data, create a set of configuration parameters 
     * suitable for use in a TunnelController.  This will replace (not add to)
     * any existing parameters, so this should return a comprehensive mapping.
     *
     */
    public Properties getConfig() {
        Properties config = new Properties();
        updateConfigGeneric(config);
        
        if ((TunnelController.isClient(_type) && !TunnelController.TYPE_STREAMR_CLIENT.equals(_type)) ||
            TunnelController.TYPE_STREAMR_SERVER.equals(_type)) {
            // streamrserver uses interface
            if (_reachableBy != null)
                config.setProperty(TunnelController.PROP_INTFC, _reachableBy);
            else
                config.setProperty(TunnelController.PROP_INTFC, "");
        } else {
            // streamrclient uses targetHost
            if (_targetHost != null)
                config.setProperty(TunnelController.PROP_TARGET_HOST, _targetHost);
        }

        if (TunnelController.isClient(_type)) {
            // generic client stuff
            if (_port >= 0)
                config.setProperty(TunnelController.PROP_LISTEN_PORT, Integer.toString(_port));
            config.setProperty(TunnelController.PROP_SHARED, _sharedClient + "");
            // see I2PTunnelHTTPClient
            if (TunnelController.TYPE_HTTP_CLIENT.equals(_type))
                _booleanOptions.add(I2PTunnelHTTPClient.PROP_SSL_SET);
            for (String p : _booleanClientOpts)
                config.setProperty(OPT + p, "" + _booleanOptions.contains(p));
            for (String p : _otherClientOpts) {
                if (_otherOptions.containsKey(p))
                    config.setProperty(OPT + p, _otherOptions.get(p));
            }
        } else {
            // generic server stuff
            if (_targetPort >= 0)
                config.setProperty(TunnelController.PROP_TARGET_PORT, Integer.toString(_targetPort));
            // see TunnelController.setConfig()
            _booleanOptions.add(TunnelController.PROP_LIMITS_SET);
            for (String p : _booleanServerOpts)
                config.setProperty(OPT + p, "" + _booleanOptions.contains(p));
            for (String p : _otherServerOpts) {
                if (_otherOptions.containsKey(p))
                    config.setProperty(OPT + p, _otherOptions.get(p));
            }
        }

        // override bundle setting set above
        if (!TunnelController.isClient(_type) &&
            !TunnelController.TYPE_HTTP_SERVER.equals(_type) &&
            !TunnelController.TYPE_STREAMR_SERVER.equals(_type)) {
            config.setProperty(TunnelController.OPT_BUNDLE_REPLY, "true");
        }

        // generic proxy stuff
        if (TunnelController.TYPE_HTTP_CLIENT.equals(_type) || TunnelController.TYPE_CONNECT.equals(_type) || 
            TunnelController.TYPE_SOCKS.equals(_type) ||TunnelController.TYPE_SOCKS_IRC.equals(_type)) {
            for (String p : _booleanProxyOpts)
                config.setProperty(OPT + p, "" + _booleanOptions.contains(p));
            if (_proxyList != null)
                config.setProperty(TunnelController.PROP_PROXIES, _proxyList);
        }

        // Proxy auth including migration to MD5
        if (TunnelController.TYPE_HTTP_CLIENT.equals(_type) || TunnelController.TYPE_CONNECT.equals(_type)) {
            // Migrate even if auth is disabled
            // go get the old from custom options that updateConfigGeneric() put in there
            String puser = OPT + I2PTunnelHTTPClientBase.PROP_USER;
            String user = config.getProperty(puser);
            String ppw = OPT + I2PTunnelHTTPClientBase.PROP_PW;
            String pw = config.getProperty(ppw);
            if (user != null && pw != null && user.length() > 0 && pw.length() > 0) {
                String pmd5 = OPT + I2PTunnelHTTPClientBase.PROP_PROXY_DIGEST_PREFIX +
                              user + I2PTunnelHTTPClientBase.PROP_PROXY_DIGEST_SUFFIX;
                if (config.getProperty(pmd5) == null) {
                    // not in there, migrate
                    String realm = _type.equals(TunnelController.TYPE_HTTP_CLIENT) ? I2PTunnelHTTPClient.AUTH_REALM
                                                              : I2PTunnelConnectClient.AUTH_REALM;
                    String hex = PasswordManager.md5Hex(realm, user, pw);
                    if (hex != null) {
                        config.setProperty(pmd5, hex);
                        config.remove(puser);
                        config.remove(ppw);
                    }
                }
            }
            // New user/password
            String auth = _otherOptions.get(I2PTunnelHTTPClientBase.PROP_AUTH);
            if (auth != null && !auth.equals("false")) {
                if (_newProxyUser != null && _newProxyPW != null &&
                    _newProxyUser.length() > 0 && _newProxyPW.length() > 0) {
                    String pmd5 = OPT + I2PTunnelHTTPClientBase.PROP_PROXY_DIGEST_PREFIX +
                                  _newProxyUser + I2PTunnelHTTPClientBase.PROP_PROXY_DIGEST_SUFFIX;
                    String realm = _type.equals(TunnelController.TYPE_HTTP_CLIENT) ? I2PTunnelHTTPClient.AUTH_REALM
                                                              : I2PTunnelConnectClient.AUTH_REALM;
                    String hex = PasswordManager.md5Hex(realm, _newProxyUser, _newProxyPW);
                    if (hex != null)
                        config.setProperty(pmd5, hex);
                }
            }
        }

        if (TunnelController.TYPE_IRC_CLIENT.equals(_type) ||
            TunnelController.TYPE_STD_CLIENT.equals(_type) ||
            TunnelController.TYPE_STREAMR_CLIENT.equals(_type)) {
            if (_targetDestination != null)
                config.setProperty(TunnelController.PROP_DEST, _targetDestination);
        } else if (TunnelController.TYPE_HTTP_SERVER.equals(_type) ||
                   TunnelController.TYPE_HTTP_BIDIR_SERVER.equals(_type)) {
            if (_spoofedHost != null)
                config.setProperty(TunnelController.PROP_SPOOFED_HOST, _spoofedHost);
            for (String p : _httpServerOpts)
                if (_otherOptions.containsKey(p))
                    config.setProperty(OPT + p, _otherOptions.get(p));
        }
        if (TunnelController.TYPE_HTTP_BIDIR_SERVER.equals(_type)) {
            if (_port >= 0)
                config.setProperty(TunnelController.PROP_LISTEN_PORT, Integer.toString(_port));
            if (_reachableBy != null)
                config.setProperty(TunnelController.PROP_INTFC, _reachableBy);
            else if (_targetHost != null)
                config.setProperty(TunnelController.PROP_INTFC, _targetHost);
            else
                config.setProperty(TunnelController.PROP_INTFC, "");
        }

        if (TunnelController.TYPE_IRC_CLIENT.equals(_type)) {
            boolean dcc = _booleanOptions.contains(I2PTunnelIRCClient.PROP_DCC);
            config.setProperty(OPT + I2PTunnelIRCClient.PROP_DCC,
                               "" + dcc);
            // add some sane server options since they aren't in the GUI (yet)
            if (dcc) {
                config.setProperty(OPT + PROP_MAX_CONNS_MIN, "3");
                config.setProperty(OPT + PROP_MAX_CONNS_HOUR, "10");
                config.setProperty(OPT + PROP_MAX_TOTAL_CONNS_MIN, "5");
                config.setProperty(OPT + PROP_MAX_TOTAL_CONNS_HOUR, "25");
            }
        }

        if (!TunnelController.isClient(_type) || _booleanOptions.contains("persistentClientKey")) {
            // As of 0.9.17, add a persistent random key if not present
            String p = OPT + "inbound.randomKey";
            if (!config.containsKey(p)) {
                byte[] rk = new byte[32];
                _context.random().nextBytes(rk);
                config.setProperty(p, Base64.encode(rk));
                p = OPT + "outbound.randomKey";
                _context.random().nextBytes(rk);
                config.setProperty(p, Base64.encode(rk));
            }
            // As of 0.9.18, add persistent leaseset keys if not present
            // but only if we know the sigtype
            p = OPT + "i2cp.leaseSetSigningPrivateKey";
            if (_dest != null && !config.containsKey(p)) {
                try {
                    SigType type = _dest.getSigType();
                    SimpleDataStructure keys[] = KeyGenerator.getInstance().generateSigningKeys(type);
                    config.setProperty(p, type.name() + ':' + keys[1].toBase64());
                    p = OPT + "i2cp.leaseSetPrivateKey";
                    keys = KeyGenerator.getInstance().generatePKIKeys();
                    config.setProperty(p, "ELGAMAL_2048:" + keys[1].toBase64());
                } catch (GeneralSecurityException gse) {
                    // so much for that
                }
            }
        }

        return config;
    }
    
    private static final String _noShowOpts[] = {
        "inbound.length", "outbound.length", "inbound.lengthVariance", "outbound.lengthVariance",
        "inbound.backupQuantity", "outbound.backupQuantity", "inbound.quantity", "outbound.quantity",
        "inbound.nickname", "outbound.nickname", "i2p.streaming.connectDelay", "i2p.streaming.maxWindowSize",
        I2PTunnelIRCClient.PROP_DCC
        };
    private static final String _booleanClientOpts[] = {
        "i2cp.reduceOnIdle", "i2cp.closeOnIdle", "i2cp.newDestOnResume", "persistentClientKey", "i2cp.delayOpen",
        I2PTunnelClientBase.PROP_USE_SSL,
        };
    private static final String _booleanProxyOpts[] = {
        I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH,
        I2PTunnelHTTPClientBase.PROP_USE_OUTPROXY_PLUGIN,
        I2PTunnelHTTPClient.PROP_USER_AGENT,
        I2PTunnelHTTPClient.PROP_REFERER,
        I2PTunnelHTTPClient.PROP_ACCEPT,
        I2PTunnelHTTPClient.PROP_INTERNAL_SSL,
        I2PTunnelHTTPClient.PROP_SSL_SET
        };
    private static final String _booleanServerOpts[] = {
        "i2cp.reduceOnIdle", "i2cp.encryptLeaseSet", PROP_ENABLE_ACCESS_LIST, PROP_ENABLE_BLACKLIST,
        I2PTunnelServer.PROP_USE_SSL,
        I2PTunnelHTTPServer.OPT_REJECT_INPROXY,
        I2PTunnelHTTPServer.OPT_REJECT_REFERER,
        I2PTunnelHTTPServer.OPT_REJECT_USER_AGENTS,
        I2PTunnelServer.PROP_UNIQUE_LOCAL,
        "shouldBundleReplyInfo",
        TunnelController.PROP_LIMITS_SET
        };
    private static final String _otherClientOpts[] = {
        "i2cp.reduceIdleTime", "i2cp.reduceQuantity", "i2cp.closeIdleTime",
        "outproxyUsername", "outproxyPassword",
        I2PTunnelHTTPClient.PROP_JUMP_SERVERS,
        I2PTunnelHTTPClientBase.PROP_AUTH,
        I2PClient.PROP_SIGTYPE,
        I2PTunnelHTTPClient.PROP_SSL_OUTPROXIES,
        // following are mostly server but could also be persistent client
        "inbound.randomKey", "outbound.randomKey", "i2cp.leaseSetSigningPrivateKey", "i2cp.leaseSetPrivateKey"
        };
    private static final String _otherServerOpts[] = {
        "i2cp.reduceIdleTime", "i2cp.reduceQuantity", "i2cp.leaseSetKey", "i2cp.accessList",
         PROP_MAX_CONNS_MIN, PROP_MAX_CONNS_HOUR, PROP_MAX_CONNS_DAY,
         PROP_MAX_TOTAL_CONNS_MIN, PROP_MAX_TOTAL_CONNS_HOUR, PROP_MAX_TOTAL_CONNS_DAY,
         PROP_MAX_STREAMS, I2PClient.PROP_SIGTYPE,
        "inbound.randomKey", "outbound.randomKey", "i2cp.leaseSetSigningPrivateKey", "i2cp.leaseSetPrivateKey",
         I2PTunnelServer.PROP_ALT_PKF
        };
    private static final String _httpServerOpts[] = {
        I2PTunnelHTTPServer.OPT_POST_WINDOW,
        I2PTunnelHTTPServer.OPT_POST_BAN_TIME,
        I2PTunnelHTTPServer.OPT_POST_TOTAL_BAN_TIME,
        I2PTunnelHTTPServer.OPT_POST_MAX,
        I2PTunnelHTTPServer.OPT_POST_TOTAL_MAX,
        I2PTunnelHTTPServer.OPT_USER_AGENTS
        };

    /**
     *  do NOT add these to noShoOpts, we must leave them in for HTTPClient and ConnectCLient
     *  so they will get migrated to MD5
     *  TODO migrate socks to MD5
     */
    private static final String _otherProxyOpts[] = {
        "proxyUsername", "proxyPassword"
        };

    public static final Set<String> _noShowSet = new HashSet<String>(128);
    public static final Set<String> _nonProxyNoShowSet = new HashSet<String>(4);
    static {
        _noShowSet.addAll(Arrays.asList(_noShowOpts));
        _noShowSet.addAll(Arrays.asList(_booleanClientOpts));
        _noShowSet.addAll(Arrays.asList(_booleanProxyOpts));
        _noShowSet.addAll(Arrays.asList(_booleanServerOpts));
        _noShowSet.addAll(Arrays.asList(_otherClientOpts));
        _noShowSet.addAll(Arrays.asList(_otherServerOpts));
        _noShowSet.addAll(Arrays.asList(_httpServerOpts));
        _nonProxyNoShowSet.addAll(Arrays.asList(_otherProxyOpts));
    }

    private void updateConfigGeneric(Properties config) {
        config.setProperty(TunnelController.PROP_TYPE, _type);
        if (_name != null)
            config.setProperty(TunnelController.PROP_NAME, _name);
        if (_description != null)
            config.setProperty(TunnelController.PROP_DESCR, _description);
        if (!_context.isRouterContext()) {
            if (_i2cpHost != null)
                config.setProperty(TunnelController.PROP_I2CP_HOST, _i2cpHost);
            if ( (_i2cpPort != null) && (_i2cpPort.trim().length() > 0) ) {
                config.setProperty(TunnelController.PROP_I2CP_PORT, _i2cpPort);
            } else {
                config.setProperty(TunnelController.PROP_I2CP_PORT, "7654");
            }
        }
        if (_privKeyFile != null)
            config.setProperty(TunnelController.PROP_FILE, _privKeyFile);
        
        if (_customOptions != null) {
            StringTokenizer tok = new StringTokenizer(_customOptions);
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int eq = pair.indexOf('=');
                if ( (eq <= 0) || (eq >= pair.length()) )
                    continue;
                String key = pair.substring(0, eq);
                if (_noShowSet.contains(key))
                    continue;
                // leave in for HTTP and Connect so it can get migrated to MD5
                // hide for SOCKS until migrated to MD5
                if ((!TunnelController.TYPE_HTTP_CLIENT.equals(_type)) &&
                    (!TunnelController.TYPE_CONNECT.equals(_type)) &&
                    _nonProxyNoShowSet.contains(key))
                    continue;
                String val = pair.substring(eq+1);
                config.setProperty(OPT + key, val);
            }
        }

        config.setProperty(TunnelController.PROP_START, _startOnLoad + "");

        updateTunnelQuantities(config);
        if (_connectDelay)
            config.setProperty("option.i2p.streaming.connectDelay", "1000");
        else
            config.setProperty("option.i2p.streaming.connectDelay", "0");
        if (TunnelController.isClient(_type) && _sharedClient) {
            config.setProperty("option.inbound.nickname", SHARED_CLIENT_NICKNAME);
            config.setProperty("option.outbound.nickname", SHARED_CLIENT_NICKNAME);
        } else if (_name != null) {
            config.setProperty("option.inbound.nickname", _name);
            config.setProperty("option.outbound.nickname", _name);
        }
        if ("interactive".equals(_profile))
            // This was 1 which doesn't make much sense
            // The real way to make it interactive is to make the streaming lib
            // MessageInputStream flush faster but there's no option for that yet,
            // Setting it to 16 instead of the default but not sure what good that is either.
            config.setProperty("option.i2p.streaming.maxWindowSize", "16");
        else
            config.remove("option.i2p.streaming.maxWindowSize");
    }

    /**
     * Update tunnel quantities for the provided config from this TunnelConfig.
     *
     * @param config the config to update.
     */
    public void updateTunnelQuantities(Properties config) {
        if (_tunnelQuantity >= 0) {
            config.setProperty("option.inbound.quantity", Integer.toString(_tunnelQuantity));
            if (_tunnelQuantityOut < 0)
                _tunnelQuantityOut = _tunnelQuantity;
            config.setProperty("option.outbound.quantity", Integer.toString(_tunnelQuantityOut));
        }
        if (_tunnelDepth >= 0) {
            config.setProperty("option.inbound.length", Integer.toString(_tunnelDepth));
            if (_tunnelDepthOut < 0)
                _tunnelDepthOut = _tunnelDepth;
            config.setProperty("option.outbound.length", Integer.toString(_tunnelDepthOut));
        }
        if (_tunnelVariance >= -2) {
            config.setProperty("option.inbound.lengthVariance", Integer.toString(_tunnelVariance));
            if (_tunnelVarianceOut < -2)
                _tunnelVarianceOut = _tunnelVariance;
            config.setProperty("option.outbound.lengthVariance", Integer.toString(_tunnelVarianceOut));
        }
        if (_tunnelBackupQuantity >= 0) {
            config.setProperty("option.inbound.backupQuantity", Integer.toString(_tunnelBackupQuantity));
            if (_tunnelBackupQuantityOut < 0)
                _tunnelBackupQuantityOut = _tunnelBackupQuantity;
            config.setProperty("option.outbound.backupQuantity", Integer.toString(_tunnelBackupQuantityOut));
        }
    }
}
