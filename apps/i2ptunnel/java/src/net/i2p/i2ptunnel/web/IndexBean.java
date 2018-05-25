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
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.app.ClientAppManager;
import net.i2p.app.Outproxy;
import net.i2p.data.Certificate;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.SessionKey;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.i2ptunnel.I2PTunnelHTTPServer;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.i2ptunnel.ui.GeneralHelper;
import net.i2p.i2ptunnel.ui.Messages;
import net.i2p.i2ptunnel.ui.TunnelConfig;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.UIMessages;

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
    protected final GeneralHelper _helper;
    private final String _fatalError;
    private String _action;
    private int _tunnel;
    //private long _prevNonce;
    //private long _prevNonce2;
    private String _curNonce;
    //private long _nextNonce;
    private int _msgID = -1;

    private final TunnelConfig _config;
    private boolean _removeConfirmed;
    private int _hashCashValue;
    private int _certType;
    private String _certSigner;
    
    public static final int RUNNING = GeneralHelper.RUNNING;
    public static final int STARTING = GeneralHelper.STARTING;
    public static final int NOT_RUNNING = GeneralHelper.NOT_RUNNING;
    public static final int STANDBY = GeneralHelper.STANDBY;
    
    //static final String PROP_NONCE = IndexBean.class.getName() + ".nonce";
    //static final String PROP_NONCE_OLD = PROP_NONCE + '2';
    /** 3 wasn't enough for some browsers. They are reloading the page for some reason - maybe HEAD? @since 0.8.1 */
    private static final int MAX_NONCES = 8;
    /** store nonces in a static FIFO instead of in System Properties @since 0.8.1 */
    private static final List<String> _nonces = new ArrayList<String>(MAX_NONCES + 1);
    private static final UIMessages _messages = new UIMessages(100);

    public static final String PROP_THEME_NAME = "routerconsole.theme";
    public static final String DEFAULT_THEME = "light";
    public static final String PROP_CSS_DISABLED = "routerconsole.css.disabled";
    public static final String PROP_JS_DISABLED = "routerconsole.javascript.disabled";
    private static final String PROP_PW_ENABLE = "routerconsole.auth.enable";
    
    public IndexBean() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(IndexBean.class);
        TunnelControllerGroup tcg;
        String error;
        try {
            tcg = TunnelControllerGroup.getInstance();
            error = tcg == null ? _t("Tunnels are not initialized yet, please reload in two minutes.")
                                : null;
        } catch (IllegalArgumentException iae) {
            tcg = null;
            error = iae.toString();
        }
        _group = tcg;
        _helper = new GeneralHelper(_context, _group);
        _fatalError = error;
        _tunnel = -1;
        _curNonce = "-1";
        addNonce();
        _config = new TunnelConfig();
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

    /**
      * do we know this nonce?
      * @since 0.8.1 public since 0.9.35
      */
    public static boolean haveNonce(String nonce) {
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

    /** @since 0.9.33 */
    public void setMsgid(String id) {
        if (id == null) return;
        try {
            _msgID = Integer.parseInt(id);
        } catch (NumberFormatException nfe) {
            _msgID = -1;
        }
    }
    
    /** @return non-null */
    private String processAction() {
        if ( (_action == null) || (_action.trim().length() <= 0) || ("Cancel".equals(_action)))
            return "";
        if (_group == null)
            return "Error - tunnels are not initialized yet";
        // If passwords are turned on, all is assumed good
        if (!_context.getBooleanProperty(PROP_PW_ENABLE) &&
            !haveNonce(_curNonce))
            return _t("Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.")
                   + ' ' +
                   _t("If the problem persists, verify that you have cookies enabled in your browser.");
        // for any of these that call getMessage(msgs),
        // we return "", as getMessage() will add them to the returned string.
        if ("Stop all".equals(_action)) {
            stopAll();
            return "";
        } else if ("Start all".equals(_action)) {
            startAll();
            return "";
        } else if ("Restart all".equals(_action)) {
            restartAll();
            return "";
        } else if ("Reload configuration".equals(_action)) {
            return reloadConfig();
        } else if ("stop".equals(_action)) {
            return stop();
        } else if ("start".equals(_action)) {
            return start();
        } else if ("Save changes".equals(_action) || // IE workaround:
                (_action.toLowerCase(Locale.US).indexOf("s</span>ave") >= 0)) {
            saveChanges();
            return "";
        } else if ("Delete this proxy".equals(_action) || // IE workaround:
                (_action.toLowerCase(Locale.US).indexOf("d</span>elete") >= 0)) {
            deleteTunnel();
            return "";
        } else if ("Estimate".equals(_action)) {
            return PrivateKeyFile.estimateHashCashTime(_hashCashValue);
        } else if ("Modify".equals(_action)) {
            return modifyDestination();
        } else if ("Generate".equals(_action)) {
            return generateNewEncryptionKey();
        } else if ("Clear".equals(_action)) {
            _messages.clearThrough(_msgID);
            return "";
        } else {
            return "Action " + _action + " unknown";
        }
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
        return _t("Configuration reloaded for all tunnels");
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
        // FIXME name will be HTML escaped twice
        return _t("Starting tunnel") + ' ' + getTunnelName(_tunnel) + "...";
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
        // FIXME name will be HTML escaped twice
        return _t("Stopping tunnel") + ' ' + getTunnelName(_tunnel) + "...";
    }
    
    /**
     * Only call this ONCE! Or you will get duplicate tunnels on save.
     *
     * @return not HTML escaped, or "" if empty
     */
    private String saveChanges() {
        // FIXME name will be HTML escaped twice
        return getMessages(_helper.saveTunnel(_tunnel, _config));
    }

    /**
     *  Stop the tunnel, delete from config,
     *  rename the private key file if in the default directory
     */
    private String deleteTunnel() {
        if (!_removeConfirmed)
            return "Please confirm removal";

        return getMessages(_helper.deleteTunnel(_tunnel, _config.getPrivKeyFile()));
    }
    
    /**
     * Executes any action requested (start/stop/etc) and dump out the 
     * messages.
     *
     * Only call this ONCE! Or you will get duplicate tunnels on save.
     *
     * @return HTML escaped or "" if empty
     */
    public String getMessages() {
        if (_group == null)
            return _fatalError;
        
        StringBuilder buf = new StringBuilder(512);
        if (_action != null) {
            try {
                String result = processAction();
                if (result.length() > 0)
                    buf.append(result).append('\n');
            } catch (RuntimeException e) {
                _log.log(Log.CRIT, "Error processing " + _action, e);
                buf.append("Error: ").append(e.toString()).append('\n');
            }
        }
        List<UIMessages.Message> msgs = _messages.getMessages();
        if (!msgs.isEmpty()) {
            for (UIMessages.Message msg : msgs) {
                buf.append(msg.message).append('\n');
            }
        }
        getMessages(_group.clearAllMessages(), buf);
        return DataHelper.escapeHTML(buf.toString());
    }
    
    /**
     * The last stored message ID
     *
     * @since 0.9.33
     */
    public int getLastMessageID() {
        return _messages.getLastMessageID();
    }
    
    ////
    // The remaining methods are simple bean props for the jsp to query
    ////
    
    public String getTheme() {
        String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
        return "/themes/console/" + theme + "/";
    }

    public boolean allowCSS() {
        return !_context.getBooleanProperty(PROP_CSS_DISABLED);
    }
    
    public boolean allowJS() {
        return !_context.getBooleanProperty(PROP_JS_DISABLED);
    }
    
    public int getTunnelCount() {
        if (_group == null) return 0;
        return _group.getControllers().size();
    }
    
    /**
     *  Is it a client or server in the UI and I2P side?
     *  Note that a streamr client is a UI and I2P client but a server on the localhost side.
     *  Note that a streamr server is a UI and I2P server but a client on the localhost side.
     */
    public boolean isClient(int tunnelNum) {
        TunnelController cur = getController(tunnelNum);
        if (cur == null) return false;
        return cur.isClient();
    }

    /**
     *  Is it a client or server in the UI and I2P side?
     *  Note that a streamr client is a UI and I2P client but a server on the localhost side.
     *  Note that a streamr server is a UI and I2P server but a client on the localhost side.
     */
    public static boolean isClient(String type) {
        return TunnelController.isClient(type);
    }
    
    public String getTunnelName(int tunnel) {
        String name = _helper.getTunnelName(tunnel);
        if (name != null && name.length() > 0)
            return DataHelper.escapeHTML(name);
        else
            return _t("New Tunnel");
    }
    
    /**
     *  No validation
     */
    public String getClientPort(int tunnel) {
        int port = _helper.getClientPort(tunnel);
        return port > 0 ? Integer.toString(port) : "";
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
                return "<font color=\"red\">" + _t("Port not set") + "</font>";
            int iport = Addresses.getPort(port);
            if (iport == 0)
                return "<font color=\"red\">" + _t("Invalid port") + ' ' + port + "</font>";
            if (iport < 1024)
                return "<font color=\"red\">" +
                       _t("Warning - ports less than 1024 are not recommended") +
                       ": " + port + "</font>";
            // dup check, O(n**2)
            List<TunnelController> controllers = _group.getControllers();
            for (int i = 0; i < controllers.size(); i++) {
                if (i == tunnel)
                    continue;
                if (port.equals(controllers.get(i).getListenPort()))
                    return "<font color=\"red\">" +
                           _t("Warning - duplicate port") +
                           ": " + port + "</font>";
            }
            return port;
        }
        return "<font color=\"red\">" + _t("Port not set") + "</font>";
    }
    
    public String getTunnelType(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return getTypeName(tun.getType());
        else
            return "";
    }
    
    public String getTypeName(String internalType) {
        if (TunnelController.TYPE_STD_CLIENT.equals(internalType)) return _t("Standard client");
        else if (TunnelController.TYPE_HTTP_CLIENT.equals(internalType)) return _t("HTTP/HTTPS client");
        else if (TunnelController.TYPE_IRC_CLIENT.equals(internalType)) return _t("IRC client");
        else if (TunnelController.TYPE_STD_SERVER.equals(internalType)) return _t("Standard server");
        else if (TunnelController.TYPE_HTTP_SERVER.equals(internalType)) return _t("HTTP server");
        else if (TunnelController.TYPE_SOCKS.equals(internalType)) return _t("SOCKS 4/4a/5 proxy");
        else if (TunnelController.TYPE_SOCKS_IRC.equals(internalType)) return _t("SOCKS IRC proxy");
        else if (TunnelController.TYPE_CONNECT.equals(internalType)) return _t("CONNECT/SSL/HTTPS proxy");
        else if (TunnelController.TYPE_IRC_SERVER.equals(internalType)) return _t("IRC server");
        else if (TunnelController.TYPE_STREAMR_CLIENT.equals(internalType)) return _t("Streamr client");
        else if (TunnelController.TYPE_STREAMR_SERVER.equals(internalType)) return _t("Streamr server");
        else if (TunnelController.TYPE_HTTP_BIDIR_SERVER.equals(internalType)) return _t("HTTP bidir");
        else return internalType;
    }
    
    public String getInternalType(int tunnel) {
        return _helper.getTunnelType(tunnel);
    }
    
    public String getClientInterface(int tunnel) {
        return _helper.getClientInterface(tunnel);
    }
    
    public int getTunnelStatus(int tunnel) {
        return _helper.getTunnelStatus(tunnel);
    }
    
    public String getTunnelDescription(int tunnel) {
        return DataHelper.escapeHTML(_helper.getTunnelDescription(tunnel));
    }
    
    public String getSharedClient(int tunnel) {
    	TunnelController tun = getController(tunnel);
    	if (tun != null)
    		return tun.getSharedClient();
    	else
    		return "";
    }
    
    public String getClientDestination(int tunnel) {
        return _helper.getClientDestination(tunnel);
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
                host = "<font color=\"red\">" + _t("Host not set") + "</font>";
            else if (Addresses.getIP(host) == null)
                host = "<font color=\"red\">" + _t("Invalid address") + ' ' + host + "</font>";
            else if (host.indexOf(':') >= 0)
                host = '[' + host + ']';
            if (port == null || port.length() == 0)
                port = "<font color=\"red\">" + _t("Port not set") + "</font>";
            else if (Addresses.getPort(port) == 0)
                port = "<font color=\"red\">" + _t("Invalid port") + ' ' + port + "</font>";
            return host + ':' + port;
       }  else
            return "";
    }
    
    /**
     *  Works even if tunnel is not running.
     *  @return Destination or null
     *  @since 0.9.17
     */
    protected Destination getDestination(int tunnel) {
        return _helper.getDestination(tunnel);
    }
    
    /**
     *  Works even if tunnel is not running.
     *  @return Base64 or ""
     */
    public String getDestinationBase64(int tunnel) {
        Destination d = getDestination(tunnel);
        if (d != null)
            return d.toBase64();
        return "";
    }
    
    /**
     *  Works even if tunnel is not running.
     *  @return "{52 chars}.b32.i2p" or ""
     */
    public String getDestHashBase32(int tunnel) {
        Destination d = getDestination(tunnel);
        if (d != null)
            return d.toBase32();
        return "";
    }

    /**
     *  Works even if tunnel is not running.
     *  @return Destination or null
     *  @since 0.9.30
     */
    protected Destination getAltDestination(int tunnel) {
        return _helper.getAltDestination(tunnel);
    }
    
    /**
     *  Works even if tunnel is not running.
     *  @return Base64 or ""
     *  @since 0.9.30
     */
    public String getAltDestinationBase64(int tunnel) {
        Destination d = getAltDestination(tunnel);
        if (d != null)
            return d.toBase64();
        return "";
    }
    
    /**
     *  Works even if tunnel is not running.
     *  @return "{52 chars}.b32.i2p" or ""
     *  @since 0.9.30
     */
    public String getAltDestHashBase32(int tunnel) {
        Destination d = getAltDestination(tunnel);
        if (d != null)
            return d.toBase32();
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
                if (Boolean.parseBoolean(opts.getProperty(I2PTunnelHTTPClientBase.PROP_USE_OUTPROXY_PLUGIN, "true"))) {
                    ClientAppManager mgr = _context.clientAppManager();
                    if (mgr != null)
                        return mgr.getRegisteredApp(Outproxy.NAME) != null;
                }
            }
        }
        return false;
    }

    /**
     * @since 0.9.32 moved from EditBean
     */
    public String getSpoofedHost(int tunnel) {
        return DataHelper.escapeHTML(_helper.getSpoofedHost(tunnel));
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
        _config.setType(type);
    }
    String getType() { return _config.getType(); }
    
    /** Short name of the tunnel */
    public void setName(String name) { 
        _config.setName(name);
    }
    /** one line description */
    public void setNofilter_description(String description) { 
        _config.setDescription(description);
    }
    /** I2CP host the router is on, ignored when in router context */
    public void setClientHost(String host) {
        _config.setClientHost(host);
    }
    /** I2CP port the router is on, ignored when in router context */
    public void setClientport(String port) {
        _config.setClientPort(port);
    }

    /** how many hops to use for inbound tunnels
     *  In or both in/out
     */
    public void setTunnelDepth(String tunnelDepth) {
        if (tunnelDepth != null) {
            try {
                _config.setTunnelDepth(Integer.parseInt(tunnelDepth.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** how many parallel inbound tunnels to use
     *  In or both in/out
     */
    public void setTunnelQuantity(String tunnelQuantity) {
        if (tunnelQuantity != null) {
            try {
                _config.setTunnelQuantity(Integer.parseInt(tunnelQuantity.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** how much randomisation to apply to the depth of tunnels
     *  In or both in/out
     */
    public void setTunnelVariance(String tunnelVariance) {
        if (tunnelVariance != null) {
            try {
                _config.setTunnelVariance(Integer.parseInt(tunnelVariance.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** how many tunnels to hold in reserve to guard against failures
     *  In or both in/out
     */
    public void setTunnelBackupQuantity(String tunnelBackupQuantity) {
        if (tunnelBackupQuantity != null) {
            try {
                _config.setTunnelBackupQuantity(Integer.parseInt(tunnelBackupQuantity.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** how many hops to use for outbound tunnels
     *  @since 0.9.33
     */
    public void setTunnelDepthOut(String tunnelDepth) {
        if (tunnelDepth != null) {
            try {
                _config.setTunnelDepthOut(Integer.parseInt(tunnelDepth.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** how many parallel outbound tunnels to use
     *  @since 0.9.33
     */
    public void setTunnelQuantityOut(String tunnelQuantity) {
        if (tunnelQuantity != null) {
            try {
                _config.setTunnelQuantityOut(Integer.parseInt(tunnelQuantity.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** how much randomisation to apply to the depth of outbound tunnels
     *  @since 0.9.33
     */
    public void setTunnelVarianceOut(String tunnelVariance) {
        if (tunnelVariance != null) {
            try {
                _config.setTunnelVarianceOut(Integer.parseInt(tunnelVariance.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** how many outbound tunnels to hold in reserve to guard against failures
     *  @since 0.9.33
     */
    public void setTunnelBackupQuantityOut(String tunnelBackupQuantity) {
        if (tunnelBackupQuantity != null) {
            try {
                _config.setTunnelBackupQuantityOut(Integer.parseInt(tunnelBackupQuantity.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** what I2P session overrides should be used */
    public void setNofilter_customOptions(String customOptions) { 
        _config.setCustomOptions(customOptions);
    }
    /** what HTTP outproxies should be used (httpclient specific) */
    public void setProxyList(String proxyList) { 
        _config.setProxyList(proxyList);
    }
    /** what port should this client/httpclient/ircclient listen on */
    public void setPort(String port) { 
        if (port != null) {
            try {
                _config.setPort(Integer.parseInt(port.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }
    /** 
     * what interface should this client/httpclient/ircclient listen on
     */
    public void setReachableBy(String reachableBy) { 
        _config.setReachableBy(reachableBy);
    }
    /** What peer does this client tunnel point at */
    public void setTargetDestination(String dest) { 
        _config.setTargetDestination(dest);
    }
    /** What host does this server tunnel point at */
    public void setTargetHost(String host) { 
        _config.setTargetHost(host);
    }
    /** What port does this server tunnel point at */
    public void setTargetPort(String port) {
        if (port != null) {
            try {
                _config.setTargetPort(Integer.parseInt(port.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }
    /** What host does this http server tunnel spoof */
    public void setSpoofedHost(String host) { 
        _config.setSpoofedHost(host);
    }

    /** What filename is this server tunnel's private keys stored in */
    public void setPrivKeyFile(String file) { 
        _config.setPrivKeyFile(file);
    }

    /**
     *  What filename is this server tunnel's alternate private keys stored in
     *  @since 0.9.30
     */
    public void setAltPrivKeyFile(String file) { 
        _config.setAltPrivKeyFile(file);
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
        _config.setStartOnLoad(true);
    }
    public void setShared(String moo) {
    	_config.setShared(true);
    }
    public void setShared(boolean val) {
    	_config.setShared(val);
    }
    public void setConnectDelay(String moo) {
        _config.setConnectDelay(true);
    }
    public void setProfile(String profile) { 
        _config.setProfile(profile);
    }

    public void setReduce(String moo) {
        _config.setReduce(true);
    }
    public void setClose(String moo) {
        _config.setClose(true);
    }
    public void setEncrypt(String moo) {
        _config.setEncrypt(true);
    }

    /** @since 0.8.9 */
    public void setDCC(String moo) {
        _config.setDCC(true);
    }

    /** @since 0.9.9 */
    public void setUseSSL(String moo) {
        _config.setUseSSL(true);
    }

    /** @since 0.9.9 */
    public boolean isSSLEnabled(int tunnel) {
        return _helper.isSSLEnabled(tunnel);
    }

    /** @since 0.9.12 */
    public void setRejectInproxy(String moo) {
        _config.setRejectInproxy(true);
    }

    /** @since 0.9.12 */
    public boolean isRejectInproxy(int tunnel) {
        return _helper.getRejectInproxy(tunnel);
    }

    /** @since 0.9.25 */
    public void setRejectReferer(String moo) {
        _config.setRejectReferer(true);
    }

    /** @since 0.9.25 */
    public boolean isRejectReferer(int tunnel) {
        return _helper.getRejectReferer(tunnel);
    }

    /** @since 0.9.25 */
    public void setRejectUserAgents(String moo) {
        _config.setRejectUserAgents(true);
    }

    /** @since 0.9.25 */
    public boolean isRejectUserAgents(int tunnel) {
        return _helper.getRejectUserAgents(tunnel);
    }

    /** @since 0.9.25 */
    public void setUserAgents(String agents) {
        _config.setUserAgents(agents);
    }

    /** @since 0.9.13 */
    public void setUniqueLocal(String moo) {
        _config.setUniqueLocal(true);
    }

    public void setAccessMode(String val) {
        if (val != null) {
            try {
                _config.setAccessMode(Integer.parseInt(val.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setDelayOpen(String moo) {
        _config.setDelayOpen(true);
    }
    public void setNewDest(String val) {
        if (val != null) {
            try {
                _config.setNewDest(Integer.parseInt(val.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setReduceTime(String val) {
        if (val != null) {
            try {
                _config.setReduceTime(Integer.parseInt(val.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }
    public void setReduceCount(String val) {
        if (val != null) {
            try {
                _config.setReduceCount(Integer.parseInt(val.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }
    public void setEncryptKey(String val) {
        _config.setEncryptKey(val);
    }

    public void setAccessList(String val) {
        _config.setAccessList(val);
    }

    public void setJumpList(String val) {
        _config.setJumpList(val);
    }

    public void setCloseTime(String val) {
        if (val != null) {
            try {
                _config.setCloseTime(Integer.parseInt(val.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** @since 0.9.14 */
    public void setAllowUserAgent(String moo) {
        _config.setAllowUserAgent(true);
    }

    /** @since 0.9.14 */
    public void setAllowReferer(String moo) {
        _config.setAllowReferer(true);
    }

    /** @since 0.9.14 */
    public void setAllowAccept(String moo) {
        _config.setAllowAccept(true);
    }

    /** @since 0.9.14 */
    public void setAllowInternalSSL(String moo) {
        _config.setAllowInternalSSL(true);
    }

    /** @since 0.9.18 */
    public void setMultihome(String moo) {
        _config.setMultihome(true);
    }

    /** all proxy auth @since 0.8.2 */
    public void setProxyAuth(String s) {
        _config.setProxyAuth(I2PTunnelHTTPClientBase.DIGEST_AUTH);
    }
    
    public void setProxyUsername(String s) {
        _config.setProxyUsername(s);
    }
    
    public void setNofilter_proxyPassword(String s) {
        _config.setProxyPassword(s);
    }
    
    public void setOutproxyAuth(String s) {
        _config.setOutproxyAuth(true);
    }
    
    public void setOutproxyUsername(String s) {
        _config.setOutproxyUsername(s);
    }
    
    public void setNofilter_outproxyPassword(String s) {
        _config.setOutproxyPassword(s);
    }

    /** @since 0.9.11 */
    public void setSslProxies(String s) {
        _config.setSslProxies(s);
    }

    /** @since 0.9.11 */
    public void setUseOutproxyPlugin(String moo) {
        _config.setUseOutproxyPlugin(true);
    }

    public void setLimitMinute(String s) {
        if (s != null) {
            try {
                _config.setLimitMinute(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setLimitHour(String s) {
        if (s != null) {
            try {
                _config.setLimitHour(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setLimitDay(String s) {
        if (s != null) {
            try {
                _config.setLimitDay(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setTotalMinute(String s) {
        if (s != null) {
            try {
                _config.setTotalMinute(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setTotalHour(String s) {
        if (s != null) {
            try {
                _config.setTotalHour(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setTotalDay(String s) {
        if (s != null) {
            try {
                _config.setTotalDay(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setMaxStreams(String s) {
        if (s != null) {
            try {
                _config.setMaxStreams(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    /**
     * POST limits
     * @since 0.9.9
     */
    public void setPostMax(String s) {
        if (s != null) {
            try {
                _config.setPostMax(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setPostTotalMax(String s) {
        if (s != null) {
            try {
                _config.setPostTotalMax(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setPostCheckTime(String s) {
        if (s != null) {
            try {
                _config.setPostCheckTime(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setPostBanTime(String s) {
        if (s != null) {
            try {
                _config.setPostBanTime(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
    }

    public void setPostTotalBanTime(String s) {
        if (s != null) {
            try {
                _config.setPostTotalBanTime(Integer.parseInt(s.trim()));
            } catch (NumberFormatException nfe) {}
        }
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
            _config.setSigType(val);
            if (val.equals("0"))
                _certType = 0;
            else
                _certType = 5;
        }
        // TODO: Call modifyDestination??
        // Otherwise this only works on a new tunnel...
    }

    /**
     *  Random keys, hidden in forms
     *  @since 0.9.18
     */
    public void setKey1(String s) {
        _config.setInboundRandomKey(s);
    }

    public void setKey2(String s) {
        _config.setOutboundRandomKey(s);
    }

    public void setKey3(String s) {
        _config.setLeaseSetSigningPrivateKey(s);
    }

    public void setKey4(String s) {
        _config.setLeaseSetPrivateKey(s);
    }

    /** Modify or create a destination */
    private String modifyDestination() {
        String privKeyFile = _config.getPrivKeyFile();
        if (privKeyFile == null)
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

        File keyFile = new File(privKeyFile);
        if (!keyFile.isAbsolute())
            keyFile = new File(_context.getConfigDir(), privKeyFile);
        PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
        try {
            pkf.createIfAbsent();
        } catch (I2PException e) {
            return "Create private key file failed: " + e;
        } catch (IOException e) {
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
                if (privKeyFile.equals(signerPKF))
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
        } catch (I2PException e) {
            return "Modification failed: " + e;
        } catch (IOException e) {
            return "Modification failed: " + e;
        }
        return "Destination modified - " +
               "New Base32 is " + newdest.toBase32() +
               "New Destination is " + newdest.toBase64();
     }

    /** New key */
    private String generateNewEncryptionKey() {
        TunnelController tun = getController(_tunnel);
        if (tun == null) {
            // creating new
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
        // This is easier than requiring TunnelConfig to talk to
        // TunnelControllerGroup and TunnelController
        _config.setDestination(getDestination(_tunnel));
        return _config.getConfig();
    }

    ///
    ///
    ///
    
    protected TunnelController getController(int tunnel) {
        return _helper.getController(tunnel);
    }
    
    private static String getMessages(List<String> msgs) {
        StringBuilder buf = new StringBuilder(128);
        getMessages(msgs, buf);
        return buf.toString();
    }

    private static void getMessages(List<String> msgs, StringBuilder buf) {
        if (msgs == null) return;
        for (int i = 0; i < msgs.size(); i++) {
            String msg = msgs.get(i);
            _messages.addMessageNoEscape(msg);
            buf.append(msg).append("\n");
        }
    }

    protected String _t(String key) {
        return Messages._t(key, _context);
    }

    /** translate (ngettext)
     *  @since 0.9.7
     */
    protected String ngettext(String s, String p, int n) {
        return Messages.ngettext(s, p, n, _context);
    }
}
