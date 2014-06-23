package net.i2p.i2ptunnel.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.Outproxy;
import net.i2p.client.I2PClient;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.SessionKey;
import net.i2p.i2ptunnel.I2PTunnelConnectClient;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.i2ptunnel.I2PTunnelHTTPServer;
import net.i2p.i2ptunnel.I2PTunnelIRCClient;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
import net.i2p.util.SecureFile;

/**
 * Simple accessor for exposing tunnel info, but also an ugly form handler
 *
 * Warning - This class is not part of the i2ptunnel API,
 * it has been moved from the jar to the war.
 * Usage by classes outside of i2ptunnel.war is deprecated.
 */
public class IndexBean {
    protected final I2PAppContext _context;
    protected final Log _log;
    protected final TunnelControllerGroup _group;
    private final String _fatalError;
    private String _action;
    private int _tunnel;
    //private long _prevNonce;
    //private long _prevNonce2;
    private String _curNonce;
    //private long _nextNonce;

    private String _type;
    private String _name;
    private String _description;
    private String _i2cpHost;
    private String _i2cpPort;
    private String _tunnelDepth;
    private String _tunnelQuantity;
    private String _tunnelVariance;
    private String _tunnelBackupQuantity;
    private boolean _connectDelay;
    private String _customOptions;
    private String _proxyList;
    private String _port;
    private String _reachableBy;
    private String _targetDestination;
    private String _targetHost;
    private String _targetPort;
    private String _spoofedHost;
    private String _privKeyFile;
    private String _profile;
    private boolean _startOnLoad;
    private boolean _sharedClient;
    private boolean _removeConfirmed;
    private final Set<String> _booleanOptions;
    private final Map<String, String> _otherOptions;
    private int _hashCashValue;
    private int _certType;
    private String _certSigner;
    private String _newProxyUser;
    private String _newProxyPW;
    
    public static final int RUNNING = 1;
    public static final int STARTING = 2;
    public static final int NOT_RUNNING = 3;
    public static final int STANDBY = 4;
    
    //static final String PROP_NONCE = IndexBean.class.getName() + ".nonce";
    //static final String PROP_NONCE_OLD = PROP_NONCE + '2';
    /** 3 wasn't enough for some browsers. They are reloading the page for some reason - maybe HEAD? @since 0.8.1 */
    private static final int MAX_NONCES = 8;
    /** store nonces in a static FIFO instead of in System Properties @since 0.8.1 */
    private static final List<String> _nonces = new ArrayList<String>(MAX_NONCES + 1);

    static final String CLIENT_NICKNAME = "shared clients";
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    public static final String DEFAULT_THEME = "light";
    public static final String PROP_CSS_DISABLED = "routerconsole.css.disabled";
    public static final String PROP_JS_DISABLED = "routerconsole.javascript.disabled";
    private static final String PROP_PW_ENABLE = "routerconsole.auth.enable";
    private static final String OPT = TunnelController.PFX_OPTION;
    
    public IndexBean() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(IndexBean.class);
        TunnelControllerGroup tcg;
        String error;
        try {
            tcg = TunnelControllerGroup.getInstance();
            error = tcg == null ? _("Tunnels are not initialized yet, please reload in two minutes.")
                                : null;
        } catch (IllegalArgumentException iae) {
            tcg = null;
            error = iae.toString();
        }
        _group = tcg;
        _fatalError = error;
        _tunnel = -1;
        _curNonce = "-1";
        addNonce();
        _booleanOptions = new ConcurrentHashSet<String>(4);
        _otherOptions = new ConcurrentHashMap<String, String>(4);
    }
    
    /**
     *  @since 0.9.4
     */
    public boolean isInitialized() {
        return _group != null;
    }

    public static String getNextNonce() {
        synchronized (_nonces) {
            return _nonces.get(0);
        }
    }

    public void setNonce(String nonce) {
        if ( (nonce == null) || (nonce.trim().length() <= 0) ) return;
        _curNonce = nonce;
    }

    /** add a random nonce to the head of the queue @since 0.8.1 */
    private void addNonce() {
        String nextNonce = Long.toString(_context.random().nextLong());
        synchronized (_nonces) {
            _nonces.add(0, nextNonce);
            int sz = _nonces.size();
            if (sz > MAX_NONCES)
                _nonces.remove(sz - 1);
        }
    }

    /** do we know this nonce? @since 0.8.1 */
    private static boolean haveNonce(String nonce) {
        synchronized (_nonces) {
            return _nonces.contains(nonce);
        }
    }

    public void setAction(String action) {
        if ( (action == null) || (action.trim().length() <= 0) ) return;
        _action = action;
    }

    public void setTunnel(String tunnel) {
        if ( (tunnel == null) || (tunnel.trim().length() <= 0) ) return;
        try {
            _tunnel = Integer.parseInt(tunnel);
        } catch (NumberFormatException nfe) {
            _tunnel = -1;
        }
    }
    
    private String processAction() {
        if ( (_action == null) || (_action.trim().length() <= 0) || ("Cancel".equals(_action)))
            return "";
        if (_group == null)
            return "Error - tunnels are not initialized yet";
        // If passwords are turned on, all is assumed good
        if (!_context.getBooleanProperty(PROP_PW_ENABLE) &&
            !haveNonce(_curNonce))
            return _("Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.")
                   + ' ' +
                   _("If the problem persists, verify that you have cookies enabled in your browser.");
        if ("Stop all".equals(_action)) 
            return stopAll();
        else if ("Start all".equals(_action))
            return startAll();
        else if ("Restart all".equals(_action))
            return restartAll();
        else if ("Reload configuration".equals(_action))
            return reloadConfig();
        else if ("stop".equals(_action))
            return stop();
        else if ("start".equals(_action))
            return start();
        else if ("Save changes".equals(_action) || // IE workaround:
                (_action.toLowerCase(Locale.US).indexOf("s</span>ave") >= 0))
            return saveChanges();
        else if ("Delete this proxy".equals(_action) || // IE workaround:
                (_action.toLowerCase(Locale.US).indexOf("d</span>elete") >= 0))
            return deleteTunnel();
        else if ("Estimate".equals(_action))
            return PrivateKeyFile.estimateHashCashTime(_hashCashValue);
        else if ("Modify".equals(_action))
            return modifyDestination();
        else if ("Generate".equals(_action))
            return generateNewEncryptionKey();
        else
            return "Action " + _action + " unknown";
    }

    private String stopAll() {
        List<String> msgs = _group.stopAllControllers();
        return getMessages(msgs);
    }

    private String startAll() {
        List<String> msgs = _group.startAllControllers();
        return getMessages(msgs);
    }

    private String restartAll() {
        List<String> msgs = _group.restartAllControllers();
        return getMessages(msgs);
    }

    private String reloadConfig() {
        _group.reloadControllers();
        return _("Configuration reloaded for all tunnels");
    }

    private String start() {
        if (_tunnel < 0) return "Invalid tunnel";
        
        List<TunnelController> controllers = _group.getControllers();
        if (_tunnel >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = controllers.get(_tunnel);
        controller.startTunnelBackground();
        // give the messages a chance to make it to the window
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        // and give them something to look at in any case
        return _("Starting tunnel") + ' ' + getTunnelName(_tunnel) + "&hellip;";
    }
    
    private String stop() {
        if (_tunnel < 0) return "Invalid tunnel";
        
        List<TunnelController> controllers = _group.getControllers();
        if (_tunnel >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = controllers.get(_tunnel);
        controller.stopTunnel();
        // give the messages a chance to make it to the window
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        // and give them something to look at in any case
        return _("Stopping tunnel") + ' ' + getTunnelName(_tunnel) + "&hellip;";
    }
    
    private String saveChanges() {
        // Get current tunnel controller
        TunnelController cur = getController(_tunnel);
        
        Properties config = getConfig();
        
        if (cur == null) {
            // creating new
            cur = new TunnelController(config, "", true);
            _group.addController(cur);
            if (cur.getStartOnLoad())
                cur.startTunnelBackground();
        } else {
            cur.setConfig(config, "");
        }
        // Only modify other shared tunnels
        // if the current tunnel is shared, and of supported type
        if (Boolean.parseBoolean(cur.getSharedClient()) && isClient(cur.getType())) {
            // all clients use the same I2CP session, and as such, use the same I2CP options
            List<TunnelController> controllers = _group.getControllers();

            for (int i = 0; i < controllers.size(); i++) {
                TunnelController c = controllers.get(i);

                // Current tunnel modified by user, skip
                if (c == cur) continue;

                // Only modify this non-current tunnel
                // if it belongs to a shared destination, and is of supported type
                if (Boolean.parseBoolean(c.getSharedClient()) && isClient(c.getType())) {
                    Properties cOpt = c.getConfig("");
                    if (_tunnelQuantity != null) {
                        cOpt.setProperty("option.inbound.quantity", _tunnelQuantity);
                        cOpt.setProperty("option.outbound.quantity", _tunnelQuantity);
                    }
                    if (_tunnelDepth != null) {
                        cOpt.setProperty("option.inbound.length", _tunnelDepth);
                        cOpt.setProperty("option.outbound.length", _tunnelDepth);
                    }
                    if (_tunnelVariance != null) {
                        cOpt.setProperty("option.inbound.lengthVariance", _tunnelVariance);
                        cOpt.setProperty("option.outbound.lengthVariance", _tunnelVariance);
                    }
                    if (_tunnelBackupQuantity != null) {
                        cOpt.setProperty("option.inbound.backupQuantity", _tunnelBackupQuantity);
                        cOpt.setProperty("option.outbound.backupQuantity", _tunnelBackupQuantity);
                    }
                    cOpt.setProperty("option.inbound.nickname", CLIENT_NICKNAME);
                    cOpt.setProperty("option.outbound.nickname", CLIENT_NICKNAME);
                    
                    c.setConfig(cOpt, "");
                }
            }
        }
        
        List<String> msgs = doSave();
        return getMessages(msgs);
    }

    private List<String> doSave() { 
        List<String> rv = _group.clearAllMessages();
        try {
            _group.saveConfig();
            rv.add(0, _("Configuration changes saved"));
        } catch (IOException ioe) {
            _log.error("Failed to save config file", ioe);
            rv.add(0, _("Failed to save configuration") + ": " + ioe.toString());
        }
        return rv;
    } 

    /**
     *  Stop the tunnel, delete from config,
     *  rename the private key file if in the default directory
     */
    private String deleteTunnel() {
        if (!_removeConfirmed)
            return "Please confirm removal";
        
        TunnelController cur = getController(_tunnel);
        if (cur == null)
            return "Invalid tunnel number";
        
        List<String> msgs = _group.removeController(cur);
        msgs.addAll(doSave());

        // Rename private key file if it was a default name in
        // the default directory, so it doesn't get reused when a new
        // tunnel is created.
        // Use configured file name if available, not the one from the form.
        String pk = cur.getPrivKeyFile();
        if (pk == null)
            pk = _privKeyFile;
        if (pk != null && pk.startsWith("i2ptunnel") && pk.endsWith("-privKeys.dat") &&
            ((!isClient(cur.getType())) || cur.getPersistentClientKey())) {
            File pkf = new File(_context.getConfigDir(), pk);
            if (pkf.exists()) {
                String name = cur.getName();
                if (name == null) {
                    name = cur.getDescription();
                    if (name == null) {
                        name = cur.getType();
                        if (name == null)
                            name = Long.toString(_context.clock().now());
                    }
                }
                name = "i2ptunnel-deleted-" + name.replace(' ', '_') + '-' + _context.clock().now() + "-privkeys.dat";
                File backupDir = new SecureFile(_context.getConfigDir(), TunnelController.KEY_BACKUP_DIR);
                File to;
                if (backupDir.isDirectory() || backupDir.mkdir())
                    to = new File(backupDir, name);
                else
                    to = new File(_context.getConfigDir(), name);
                boolean success = FileUtil.rename(pkf, to);
                if (success)
                    msgs.add("Private key file " + pkf.getAbsolutePath() +
                             " renamed to " + to.getAbsolutePath());
            }
        }
        return getMessages(msgs);
    }
    
    /**
     * Executes any action requested (start/stop/etc) and dump out the 
     * messages.
     *
     */
    public String getMessages() {
        if (_group == null)
            return _fatalError;
        
        StringBuilder buf = new StringBuilder(512);
        if (_action != null) {
            try {
                buf.append(processAction()).append("\n");
            } catch (Exception e) {
                _log.log(Log.CRIT, "Error processing " + _action, e);
            }
        }
        getMessages(_group.clearAllMessages(), buf);
        return buf.toString();
    }
    
    ////
    // The remaining methods are simple bean props for the jsp to query
    ////
    
    public String getTheme() {
        String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
        return "/themes/console/" + theme + "/";
    }

    public boolean allowCSS() {
        String css = _context.getProperty(PROP_CSS_DISABLED);
        return (css == null);
    }
    
    public boolean allowJS() {
        String js = _context.getProperty(PROP_JS_DISABLED);
        return (js == null);
    }
    
    public int getTunnelCount() {
        if (_group == null) return 0;
        return _group.getControllers().size();
    }
    
    public boolean isClient(int tunnelNum) {
        TunnelController cur = getController(tunnelNum);
        if (cur == null) return false;
        return isClient(cur.getType());
    }

    public static boolean isClient(String type) {
        return ( (TunnelController.TYPE_STD_CLIENT.equals(type)) || 
        		(TunnelController.TYPE_HTTP_CLIENT.equals(type)) ||
        		(TunnelController.TYPE_SOCKS.equals(type)) ||
        		(TunnelController.TYPE_SOCKS_IRC.equals(type)) ||
        		(TunnelController.TYPE_CONNECT.equals(type)) ||
        		(TunnelController.TYPE_STREAMR_CLIENT.equals(type)) ||
        		(TunnelController.TYPE_IRC_CLIENT.equals(type)));
    }
    
    public String getTunnelName(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getName() != null)
            return tun.getName();
        else
            return _("New Tunnel");
    }
    
    /**
     *  No validation
     */
    public String getClientPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getListenPort() != null)
            return tun.getListenPort();
        else
            return "";
    }
    
    /**
     *  Returns error message if blank or invalid
     *  @since 0.9.3
     */
    public String getClientPort2(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getListenPort() != null) {
            String port = tun.getListenPort();
            if (port.length() == 0)
                return "<font color=\"red\">" + _("Port not set") + "</font>";
            int iport = Addresses.getPort(port);
            if (iport == 0)
                return "<font color=\"red\">" + _("Invalid port") + ' ' + port + "</font>";
            if (iport < 1024)
                return "<font color=\"red\">" +
                       _("Warning - ports less than 1024 are not recommended") +
                       ": " + port + "</font>";
            // dup check, O(n**2)
            List<TunnelController> controllers = _group.getControllers();
            for (int i = 0; i < controllers.size(); i++) {
                if (i == tunnel)
                    continue;
                if (port.equals(controllers.get(i).getListenPort()))
                    return "<font color=\"red\">" +
                           _("Warning - duplicate port") +
                           ": " + port + "</font>";
            }
            return port;
        }
        return "<font color=\"red\">" + _("Port not set") + "</font>";
    }
    
    public String getTunnelType(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return getTypeName(tun.getType());
        else
            return "";
    }
    
    public String getTypeName(String internalType) {
        if (TunnelController.TYPE_STD_CLIENT.equals(internalType)) return _("Standard client");
        else if (TunnelController.TYPE_HTTP_CLIENT.equals(internalType)) return _("HTTP/HTTPS client");
        else if (TunnelController.TYPE_IRC_CLIENT.equals(internalType)) return _("IRC client");
        else if (TunnelController.TYPE_STD_SERVER.equals(internalType)) return _("Standard server");
        else if (TunnelController.TYPE_HTTP_SERVER.equals(internalType)) return _("HTTP server");
        else if (TunnelController.TYPE_SOCKS.equals(internalType)) return _("SOCKS 4/4a/5 proxy");
        else if (TunnelController.TYPE_SOCKS_IRC.equals(internalType)) return _("SOCKS IRC proxy");
        else if (TunnelController.TYPE_CONNECT.equals(internalType)) return _("CONNECT/SSL/HTTPS proxy");
        else if (TunnelController.TYPE_IRC_SERVER.equals(internalType)) return _("IRC server");
        else if (TunnelController.TYPE_STREAMR_CLIENT.equals(internalType)) return _("Streamr client");
        else if (TunnelController.TYPE_STREAMR_SERVER.equals(internalType)) return _("Streamr server");
        else if (TunnelController.TYPE_HTTP_BIDIR_SERVER.equals(internalType)) return _("HTTP bidir");
        else return internalType;
    }
    
    public String getInternalType(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getType();
        else
            return "";
    }
    
    public String getClientInterface(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            if ("streamrclient".equals(tun.getType()))
                return tun.getTargetHost();
            else
                return tun.getListenOnInterface();
        } else
            return "127.0.0.1";
    }
    
    public int getTunnelStatus(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return NOT_RUNNING;
        if (tun.getIsRunning()) {
            if (isClient(tunnel) && tun.getIsStandby())
                return STANDBY;
            else
                return RUNNING;
        } else if (tun.getIsStarting()) return STARTING;
        else return NOT_RUNNING;
    }
    
    public String getTunnelDescription(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getDescription() != null)
            return tun.getDescription();
        else
            return "";
    }
    
    public String getSharedClient(int tunnel) {
    	TunnelController tun = getController(tunnel);
    	if (tun != null)
    		return tun.getSharedClient();
    	else
    		return "";
    }
    
    public String getClientDestination(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return "";
        String rv;
        if (TunnelController.TYPE_STD_CLIENT.equals(tun.getType()) ||
            TunnelController.TYPE_IRC_CLIENT.equals(tun.getType()) ||
            TunnelController.TYPE_STREAMR_CLIENT.equals(tun.getType()))
            rv = tun.getTargetDestination();
        else
            rv = tun.getProxyList();
        return rv != null ? rv : "";
    }
    
    /**
     * Call this to see if it is ok to linkify getServerTarget()
     * @since 0.8.3
     */
    public boolean isServerTargetLinkValid(int tunnel) {
        TunnelController tun = getController(tunnel);
        return tun != null &&
               TunnelController.TYPE_HTTP_SERVER.equals(tun.getType()) &&
               tun.getTargetHost() != null &&
               tun.getTargetPort() != null;
    }

    /**
     * @return valid host:port only if isServerTargetLinkValid() is true
     */
    public String getServerTarget(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            String host;
            if ("streamrserver".equals(tun.getType()))
                host = tun.getListenOnInterface();
            else
                host = tun.getTargetHost();
            String port = tun.getTargetPort();
            if (host == null || host.length() == 0)
                host = "<font color=\"red\">" + _("Host not set") + "</font>";
            else if (Addresses.getIP(host) == null)
                host = "<font color=\"red\">" + _("Invalid address") + ' ' + host + "</font>";
            else if (host.indexOf(':') >= 0)
                host = '[' + host + ']';
            if (port == null || port.length() == 0)
                port = "<font color=\"red\">" + _("Port not set") + "</font>";
            else if (Addresses.getPort(port) == 0)
                port = "<font color=\"red\">" + _("Invalid port") + ' ' + port + "</font>";
            return host + ':' + port;
       }  else
            return "";
    }
    
    public String getDestinationBase64(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            String rv = tun.getMyDestination();
            if (rv != null)
                return rv;
            // if not running, do this the hard way
            String keyFile = tun.getPrivKeyFile();
            if (keyFile != null && keyFile.trim().length() > 0) {
                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                try {
                    Destination d = pkf.getDestination();
                    if (d != null)
                        return d.toBase64();
                } catch (Exception e) {}
            }
        }
        return "";
    }
    
    /**
     *  @return "{52 chars}.b32.i2p" or ""
     */
    public String getDestHashBase32(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            String rv = tun.getMyDestHashBase32();
            if (rv != null)
                return rv;
        }
        return "";
    }
    
    /**
     *  For index.jsp
     *  @return true if the plugin is enabled, installed, and running
     *  @since 0.9.11
     */
    public boolean getIsUsingOutproxyPlugin(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            if (TunnelController.TYPE_HTTP_CLIENT.equals(tun.getType())) {
                Properties opts = tun.getClientOptionProps();
                if (Boolean.parseBoolean(opts.getProperty(I2PTunnelHTTPClient.PROP_USE_OUTPROXY_PLUGIN, "true"))) {
                    ClientAppManager mgr = _context.clientAppManager();
                    if (mgr != null)
                        return mgr.getRegisteredApp(Outproxy.NAME) != null;
                }
            }
        }
        return false;
    }

    ///
    /// bean props for form submission
    ///
    
    /**
     * What type of tunnel (httpclient, ircclient, client, or server).  This is 
     * required when adding a new tunnel.
     *
     */
    public void setType(String type) { 
        _type = (type != null ? type.trim() : null);   
    }
    String getType() { return _type; }
    
    /** Short name of the tunnel */
    public void setName(String name) { 
        _name = (name != null ? name.trim() : null);
    }
    /** one line description */
    public void setDescription(String description) { 
        _description = (description != null ? description.trim() : null);
    }
    /** I2CP host the router is on, ignored when in router context */
    public void setClientHost(String host) {
        _i2cpHost = (host != null ? host.trim() : null);
    }
    /** I2CP port the router is on, ignored when in router context */
    public void setClientport(String port) {
        _i2cpPort = (port != null ? port.trim() : null);
    }
    /** how many hops to use for inbound tunnels */
    public void setTunnelDepth(String tunnelDepth) { 
        _tunnelDepth = (tunnelDepth != null ? tunnelDepth.trim() : null);
    }
    /** how many parallel inbound tunnels to use */
    public void setTunnelQuantity(String tunnelQuantity) { 
        _tunnelQuantity = (tunnelQuantity != null ? tunnelQuantity.trim() : null);
    }
    /** how much randomisation to apply to the depth of tunnels */
    public void setTunnelVariance(String tunnelVariance) { 
        _tunnelVariance = (tunnelVariance != null ? tunnelVariance.trim() : null);
    }
    /** how many tunnels to hold in reserve to guard against failures */
    public void setTunnelBackupQuantity(String tunnelBackupQuantity) { 
        _tunnelBackupQuantity = (tunnelBackupQuantity != null ? tunnelBackupQuantity.trim() : null);
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
    public void setPort(String port) { 
        _port = (port != null ? port.trim() : null);
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
    public void setTargetPort(String port) { 
        _targetPort = (port != null ? port.trim() : null);
    }
    /** What host does this http server tunnel spoof */
    public void setSpoofedHost(String host) { 
        _spoofedHost = (host != null ? host.trim() : null);
    }
    /** What filename is this server tunnel's private keys stored in */
    public void setPrivKeyFile(String file) { 
        _privKeyFile = (file != null ? file.trim() : null);
    }
    /**
     * If called with any value (and the form submitted with action=Remove),
     * we really do want to stop and remove the tunnel.
     */
    public void setRemoveConfirm(String moo) {
        _removeConfirmed = true;
    }
    /**
     * If called with any value, we want this tunnel to start whenever it is
     * loaded (aka right now and whenever the router is started up)
     */
    public void setStartOnLoad(String moo) {
        _startOnLoad = true;
    }
    public void setShared(String moo) {
    	_sharedClient=true;
    }
    public void setShared(boolean val) {
    	_sharedClient=val;
    }
    public void setConnectDelay(String moo) {
        _connectDelay = true;
    }
    public void setProfile(String profile) { 
        _profile = profile; 
    }

    public void setReduce(String moo) {
        _booleanOptions.add("i2cp.reduceOnIdle");
    }
    public void setClose(String moo) {
        _booleanOptions.add("i2cp.closeOnIdle");
    }
    public void setEncrypt(String moo) {
        _booleanOptions.add("i2cp.encryptLeaseSet");
    }

    /** @since 0.8.9 */
    public void setDCC(String moo) {
        _booleanOptions.add(I2PTunnelIRCClient.PROP_DCC);
    }

    /** @since 0.9.9 */
    public void setUseSSL(String moo) {
        _booleanOptions.add(I2PTunnelServer.PROP_USE_SSL);
    }

    /** @since 0.9.9 */
    public boolean isSSLEnabled(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = tun.getClientOptionProps();
            return Boolean.parseBoolean(opts.getProperty(I2PTunnelServer.PROP_USE_SSL));
        }
        return false;
    }

    /** @since 0.9.12 */
    public void setRejectInproxy(String moo) {
        _booleanOptions.add(I2PTunnelHTTPServer.OPT_REJECT_INPROXY);
    }

    /** @since 0.9.12 */
    public boolean isRejectInproxy(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = tun.getClientOptionProps();
            return Boolean.parseBoolean(opts.getProperty(I2PTunnelHTTPServer.OPT_REJECT_INPROXY));
        }
        return false;
    }

    /** @since 0.9.13 */
    public void setUniqueLocal(String moo) {
        _booleanOptions.add(I2PTunnelServer.PROP_UNIQUE_LOCAL);
    }

    protected static final String PROP_ENABLE_ACCESS_LIST = "i2cp.enableAccessList";
    protected static final String PROP_ENABLE_BLACKLIST = "i2cp.enableBlackList";

    public void setAccessMode(String val) {
        if ("1".equals(val))
            _booleanOptions.add(PROP_ENABLE_ACCESS_LIST);
        else if ("2".equals(val))
            _booleanOptions.add(PROP_ENABLE_BLACKLIST);
    }

    public void setDelayOpen(String moo) {
        _booleanOptions.add("i2cp.delayOpen");
    }
    public void setNewDest(String val) {
        if ("1".equals(val))
            _booleanOptions.add("i2cp.newDestOnResume");
        else if ("2".equals(val))
            _booleanOptions.add("persistentClientKey");
    }

    public void setReduceTime(String val) {
        if (val != null) {
            try {
                _otherOptions.put("i2cp.reduceIdleTime", Integer.toString(Integer.parseInt(val.trim()) * 60*1000));
            } catch (NumberFormatException nfe) {}
        }
    }
    public void setReduceCount(String val) {
        if (val != null)
            _otherOptions.put("i2cp.reduceQuantity", val.trim());
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

    public void setCloseTime(String val) {
        if (val != null) {
            try {
                _otherOptions.put("i2cp.closeIdleTime", Integer.toString(Integer.parseInt(val.trim()) * 60*1000));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** @since 0.9.14 */
    public void setAllowUserAgent(String moo) {
        _booleanOptions.add(I2PTunnelHTTPClient.PROP_USER_AGENT);
    }

    /** @since 0.9.14 */
    public void setAllowReferer(String moo) {
        _booleanOptions.add(I2PTunnelHTTPClient.PROP_REFERER);
    }

    /** @since 0.9.14 */
    public void setAllowAccept(String moo) {
        _booleanOptions.add(I2PTunnelHTTPClient.PROP_ACCEPT);
    }

    /** @since 0.9.14 */
    public void setAllowInternalSSL(String moo) {
        _booleanOptions.add(I2PTunnelHTTPClient.PROP_INTERNAL_SSL);
    }

    /** all proxy auth @since 0.8.2 */
    public void setProxyAuth(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPClientBase.PROP_AUTH, I2PTunnelHTTPClientBase.DIGEST_AUTH);
    }
    
    public void setProxyUsername(String s) {
        if (s != null)
            _newProxyUser = s.trim();
    }
    
    public void setProxyPassword(String s) {
        if (s != null)
            _newProxyPW = s.trim();
    }
    
    public void setOutproxyAuth(String s) {
        _otherOptions.put(I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH, I2PTunnelHTTPClientBase.DIGEST_AUTH);
    }
    
    public void setOutproxyUsername(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER, s.trim());
    }
    
    public void setOutproxyPassword(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW, s.trim());
    }

    /** @since 0.9.11 */
    public void setSslProxies(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPClient.PROP_SSL_OUTPROXIES, s.trim().replace(" ", ","));
    }

    /** @since 0.9.11 */
    public void setUseOutproxyPlugin(String moo) {
        _booleanOptions.add(I2PTunnelHTTPClient.PROP_USE_OUTPROXY_PLUGIN);
    }
    
    /** all of these are @since 0.8.3 */
    protected static final String PROP_MAX_CONNS_MIN = "i2p.streaming.maxConnsPerMinute";
    protected static final String PROP_MAX_CONNS_HOUR = "i2p.streaming.maxConnsPerHour";
    protected static final String PROP_MAX_CONNS_DAY = "i2p.streaming.maxConnsPerDay";
    protected static final String PROP_MAX_TOTAL_CONNS_MIN = "i2p.streaming.maxTotalConnsPerMinute";
    protected static final String PROP_MAX_TOTAL_CONNS_HOUR = "i2p.streaming.maxTotalConnsPerHour";
    protected static final String PROP_MAX_TOTAL_CONNS_DAY = "i2p.streaming.maxTotalConnsPerDay";
    protected static final String PROP_MAX_STREAMS = "i2p.streaming.maxConcurrentStreams";

    public void setLimitMinute(String s) {
        if (s != null)
            _otherOptions.put(PROP_MAX_CONNS_MIN, s.trim());
    }

    public void setLimitHour(String s) {
        if (s != null)
            _otherOptions.put(PROP_MAX_CONNS_HOUR, s.trim());
    }

    public void setLimitDay(String s) {
        if (s != null)
            _otherOptions.put(PROP_MAX_CONNS_DAY, s.trim());
    }

    public void setTotalMinute(String s) {
        if (s != null)
            _otherOptions.put(PROP_MAX_TOTAL_CONNS_MIN, s.trim());
    }

    public void setTotalHour(String s) {
        if (s != null)
            _otherOptions.put(PROP_MAX_TOTAL_CONNS_HOUR, s.trim());
    }

    public void setTotalDay(String s) {
        if (s != null)
            _otherOptions.put(PROP_MAX_TOTAL_CONNS_DAY, s.trim());
    }

    public void setMaxStreams(String s) {
        if (s != null)
            _otherOptions.put(PROP_MAX_STREAMS, s.trim());
    }

    /**
     * POST limits
     * @since 0.9.9
     */
    public void setPostMax(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_MAX, s.trim());
    }

    public void setPostTotalMax(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_TOTAL_MAX, s.trim());
    }

    public void setPostCheckTime(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_WINDOW, Integer.toString(Integer.parseInt(s.trim()) * 60));
    }

    public void setPostBanTime(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_BAN_TIME, Integer.toString(Integer.parseInt(s.trim()) * 60));
    }

    public void setPostTotalBanTime(String s) {
        if (s != null)
            _otherOptions.put(I2PTunnelHTTPServer.OPT_POST_TOTAL_BAN_TIME, Integer.toString(Integer.parseInt(s.trim()) * 60));
    }

    /** params needed for hashcash and dest modification */
    public void setEffort(String val) {
        if (val != null) {
            try {
                _hashCashValue = Integer.parseInt(val.trim());
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setCert(String val) {
        if (val != null) {
            try {
                _certType = Integer.parseInt(val.trim());
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setSigner(String val) {
        _certSigner = val;
    }

    /** @since 0.9.12 */
    public void setSigType(String val) {
        if (val != null) {
            _otherOptions.put(I2PClient.PROP_SIGTYPE, val);
            if (val.equals("0"))
                _certType = 0;
            else
                _certType = 5;
        }
        // TODO: Call modifyDestination??
        // Otherwise this only works on a new tunnel...
    }

    /** Modify or create a destination */
    private String modifyDestination() {
        if (_privKeyFile == null || _privKeyFile.trim().length() <= 0)
            return "Private Key File not specified";

        TunnelController tun = getController(_tunnel);
        Properties config = getConfig();
        if (tun == null) {
            // creating new
            tun = new TunnelController(config, "", true);
            _group.addController(tun);
            saveChanges();
        } else if (tun.getIsRunning() || tun.getIsStarting()) {
            return "Tunnel must be stopped before modifying destination";
        }

        File keyFile = new File(_privKeyFile);
        if (!keyFile.isAbsolute())
            keyFile = new File(_context.getConfigDir(), _privKeyFile);
        PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
        try {
            pkf.createIfAbsent();
        } catch (Exception e) {
            return "Create private key file failed: " + e;
        }
        switch (_certType) {
            case Certificate.CERTIFICATE_TYPE_NULL:
            case Certificate.CERTIFICATE_TYPE_HIDDEN:
                pkf.setCertType(_certType);
                break;
            case Certificate.CERTIFICATE_TYPE_HASHCASH:
                pkf.setHashCashCert(_hashCashValue);
                break;
            case Certificate.CERTIFICATE_TYPE_SIGNED:
                if (_certSigner == null || _certSigner.trim().length() <= 0)
                    return "No signing destination specified";
                // find the signer's key file...
                String signerPKF = null;
                for (int i = 0; i < getTunnelCount(); i++) {
                    TunnelController c = getController(i);
                    if (_certSigner.equals(c.getConfig("").getProperty(TunnelController.PROP_NAME)) ||
                        _certSigner.equals(c.getConfig("").getProperty(TunnelController.PROP_SPOOFED_HOST))) {
                        signerPKF = c.getConfig("").getProperty(TunnelController.PROP_FILE);
                        break;
                    }
                }
                if (signerPKF == null || signerPKF.length() <= 0)
                    return "Signing destination " + _certSigner + " not found";
                if (_privKeyFile.equals(signerPKF))
                    return "Self-signed destinations not allowed";
                Certificate c = pkf.setSignedCert(new PrivateKeyFile(signerPKF));
                if (c == null)
                    return "Signing failed - does signer destination exist?";
                break;
            default:
                return "Unknown certificate type";
        }
        Destination newdest;
        try {
            pkf.write();
            newdest = pkf.getDestination();
        } catch (Exception e) {
            return "Modification failed: " + e;
        }
        return "Destination modified - " +
               "New Base32 is " + newdest.toBase32() +
               "New Destination is " + newdest.toBase64();
     }

    /** New key */
    private String generateNewEncryptionKey() {
        TunnelController tun = getController(_tunnel);
        Properties config = getConfig();
        if (tun == null) {
            // creating new
            tun = new TunnelController(config, "", true);
            _group.addController(tun);
            saveChanges();
        } else if (tun.getIsRunning() || tun.getIsStarting()) {
            return "Tunnel must be stopped before modifying leaseset encryption key";
        }
        byte[] data = new byte[SessionKey.KEYSIZE_BYTES];
        _context.random().nextBytes(data);
        SessionKey sk = new SessionKey(data);
        setEncryptKey(sk.toBase64());
        setEncrypt("");
        saveChanges();
        return "New Leaseset Encryption Key: " + sk.toBase64();
     }

    /**
     * Based on all provided data, create a set of configuration parameters 
     * suitable for use in a TunnelController.  This will replace (not add to)
     * any existing parameters, so this should return a comprehensive mapping.
     *
     */
    private Properties getConfig() {
        Properties config = new Properties();
        updateConfigGeneric(config);
        
        if ((isClient(_type) && !TunnelController.TYPE_STREAMR_CLIENT.equals(_type)) ||
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

        if (isClient(_type)) {
            // generic client stuff
            if (_port != null)
                config.setProperty(TunnelController.PROP_LISTEN_PORT, _port);
            config.setProperty(TunnelController.PROP_SHARED, _sharedClient + "");
            for (String p : _booleanClientOpts)
                config.setProperty(OPT + p, "" + _booleanOptions.contains(p));
            for (String p : _otherClientOpts)
                if (_otherOptions.containsKey(p))
                    config.setProperty(OPT + p, _otherOptions.get(p));
        } else {
            // generic server stuff
            if (_targetPort != null)
                config.setProperty(TunnelController.PROP_TARGET_PORT, _targetPort);
            for (String p : _booleanServerOpts)
                config.setProperty(OPT + p, "" + _booleanOptions.contains(p));
            for (String p : _otherServerOpts)
                if (_otherOptions.containsKey(p))
                    config.setProperty(OPT + p, _otherOptions.get(p));
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
            if (_port != null)
                config.setProperty(TunnelController.PROP_LISTEN_PORT, _port);
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

        return config;
    }
    
    private static final String _noShowOpts[] = {
        "inbound.length", "outbound.length", "inbound.lengthVariance", "outbound.lengthVariance",
        "inbound.backupQuantity", "outbound.backupQuantity", "inbound.quantity", "outbound.quantity",
        "inbound.nickname", "outbound.nickname", "i2p.streaming.connectDelay", "i2p.streaming.maxWindowSize",
        I2PTunnelIRCClient.PROP_DCC
        };
    private static final String _booleanClientOpts[] = {
        "i2cp.reduceOnIdle", "i2cp.closeOnIdle", "i2cp.newDestOnResume", "persistentClientKey", "i2cp.delayOpen"
        };
    private static final String _booleanProxyOpts[] = {
        I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH,
        I2PTunnelHTTPClient.PROP_USE_OUTPROXY_PLUGIN,
        I2PTunnelHTTPClient.PROP_USER_AGENT,
        I2PTunnelHTTPClient.PROP_REFERER,
        I2PTunnelHTTPClient.PROP_ACCEPT,
        I2PTunnelHTTPClient.PROP_INTERNAL_SSL
        };
    private static final String _booleanServerOpts[] = {
        "i2cp.reduceOnIdle", "i2cp.encryptLeaseSet", PROP_ENABLE_ACCESS_LIST, PROP_ENABLE_BLACKLIST,
        I2PTunnelServer.PROP_USE_SSL,
        I2PTunnelHTTPServer.OPT_REJECT_INPROXY,
        I2PTunnelServer.PROP_UNIQUE_LOCAL
        };
    private static final String _otherClientOpts[] = {
        "i2cp.reduceIdleTime", "i2cp.reduceQuantity", "i2cp.closeIdleTime",
        "outproxyUsername", "outproxyPassword",
        I2PTunnelHTTPClient.PROP_JUMP_SERVERS,
        I2PTunnelHTTPClientBase.PROP_AUTH,
        I2PClient.PROP_SIGTYPE,
        I2PTunnelHTTPClient.PROP_SSL_OUTPROXIES
        };
    private static final String _otherServerOpts[] = {
        "i2cp.reduceIdleTime", "i2cp.reduceQuantity", "i2cp.leaseSetKey", "i2cp.accessList",
         PROP_MAX_CONNS_MIN, PROP_MAX_CONNS_HOUR, PROP_MAX_CONNS_DAY,
         PROP_MAX_TOTAL_CONNS_MIN, PROP_MAX_TOTAL_CONNS_HOUR, PROP_MAX_TOTAL_CONNS_DAY,
         PROP_MAX_STREAMS, I2PClient.PROP_SIGTYPE
        };
    private static final String _httpServerOpts[] = {
        I2PTunnelHTTPServer.OPT_POST_WINDOW,
        I2PTunnelHTTPServer.OPT_POST_BAN_TIME,
        I2PTunnelHTTPServer.OPT_POST_TOTAL_BAN_TIME,
        I2PTunnelHTTPServer.OPT_POST_MAX,
        I2PTunnelHTTPServer.OPT_POST_TOTAL_MAX
        };

    /**
     *  do NOT add these to noShoOpts, we must leave them in for HTTPClient and ConnectCLient
     *  so they will get migrated to MD5
     *  TODO migrate socks to MD5
     */
    private static final String _otherProxyOpts[] = {
        "proxyUsername", "proxyPassword"
        };

    protected static final Set<String> _noShowSet = new HashSet<String>(128);
    protected static final Set<String> _nonProxyNoShowSet = new HashSet<String>(4);
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

        if (_tunnelQuantity != null) {
            config.setProperty("option.inbound.quantity", _tunnelQuantity);
            config.setProperty("option.outbound.quantity", _tunnelQuantity);
        }
        if (_tunnelDepth != null) {
            config.setProperty("option.inbound.length", _tunnelDepth);
            config.setProperty("option.outbound.length", _tunnelDepth);
        }
        if (_tunnelVariance != null) {
            config.setProperty("option.inbound.lengthVariance", _tunnelVariance);
            config.setProperty("option.outbound.lengthVariance", _tunnelVariance);
        }
        if (_tunnelBackupQuantity != null) {
            config.setProperty("option.inbound.backupQuantity", _tunnelBackupQuantity);
            config.setProperty("option.outbound.backupQuantity", _tunnelBackupQuantity);
        }
        if (_connectDelay)
            config.setProperty("option.i2p.streaming.connectDelay", "1000");
        else
            config.setProperty("option.i2p.streaming.connectDelay", "0");
        if (isClient(_type) && _sharedClient) {
            config.setProperty("option.inbound.nickname", CLIENT_NICKNAME);
            config.setProperty("option.outbound.nickname", CLIENT_NICKNAME);
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

    ///
    ///
    ///
    
    protected TunnelController getController(int tunnel) {
        if (tunnel < 0) return null;
        if (_group == null) return null;
        List<TunnelController> controllers = _group.getControllers();
        if (controllers.size() > tunnel)
            return controllers.get(tunnel); 
        else
            return null;
    }
    
    private static String getMessages(List<String> msgs) {
        StringBuilder buf = new StringBuilder(128);
        getMessages(msgs, buf);
        return buf.toString();
    }

    private static void getMessages(List<String> msgs, StringBuilder buf) {
        if (msgs == null) return;
        for (int i = 0; i < msgs.size(); i++) {
            buf.append(msgs.get(i)).append("\n");
        }
    }

    protected String _(String key) {
        return Messages._(key, _context);
    }

    /** translate (ngettext)
     *  @since 0.9.7
     */
    protected String ngettext(String s, String p, int n) {
        return Messages.ngettext(s, p, n, _context);
    }
}
