package net.i2p.i2ptunnel.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.util.Log;

/**
 * Simple accessor for exposing tunnel info, but also an ugly form handler
 *
 */
public class IndexBean {
    protected I2PAppContext _context;
    protected Log _log;
    protected TunnelControllerGroup _group;
    private String _action;
    private int _tunnel;
    private long _prevNonce;
    private long _curNonce;
    private long _nextNonce;
    private String _passphrase;

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
    private String _reachableByOther;
    private String _targetDestination;
    private String _targetHost;
    private String _targetPort;
    private String _spoofedHost;
    private String _privKeyFile;
    private String _profile;
    private boolean _startOnLoad;
    private boolean _sharedClient;
    private boolean _privKeyGenerate;
    private boolean _removeConfirmed;
    
    public static final int RUNNING = 1;
    public static final int STARTING = 2;
    public static final int NOT_RUNNING = 3;
    
    public static final String PROP_TUNNEL_PASSPHRASE = "i2ptunnel.passphrase";
    static final String PROP_NONCE = IndexBean.class.getName() + ".nonce";
    static final String CLIENT_NICKNAME = "shared clients";
    
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    public static final String PROP_CSS_DISABLED = "routerconsole.css.disabled";
    public static final String PROP_JS_DISABLED = "routerconsole.javascript.disabled";
    
    public IndexBean() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(IndexBean.class);
        _group = TunnelControllerGroup.getInstance();
        _action = null;
        _tunnel = -1;
        _curNonce = -1;
        _prevNonce = -1;
        try { 
            String nonce = System.getProperty(PROP_NONCE);
            if (nonce != null)
                _prevNonce = Long.parseLong(nonce);
        } catch (NumberFormatException nfe) {}
        _nextNonce = _context.random().nextLong();
        System.setProperty(PROP_NONCE, Long.toString(_nextNonce));
    }
    
    public long getNextNonce() { return _nextNonce; }
    public void setNonce(String nonce) {
        if ( (nonce == null) || (nonce.trim().length() <= 0) ) return;
        try {
            _curNonce = Long.parseLong(nonce);
        } catch (NumberFormatException nfe) {
            _curNonce = -1;
        }
    }
    public void setPassphrase(String phrase) {
        _passphrase = phrase;
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
    
    private boolean validPassphrase(String proposed) {
        if (proposed == null) return false;
        String pass = _context.getProperty(PROP_TUNNEL_PASSPHRASE);
        if ( (pass != null) && (pass.trim().length() > 0) ) 
            return pass.trim().equals(proposed.trim());
        else
            return false;
    }
    
    private String processAction() {
        if ( (_action == null) || (_action.trim().length() <= 0) )
            return "";
        if ( (_prevNonce != _curNonce) && (!validPassphrase(_passphrase)) )
            return "Invalid nonce, are you being spoofed?";
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
                (_action.toLowerCase().indexOf("s</span>ave") >= 0))
            return saveChanges();
        else if ("Delete this proxy".equals(_action) || // IE workaround:
                (_action.toLowerCase().indexOf("d</span>elete") >= 0))
            return deleteTunnel();
        else
            return "Action " + _action + " unknown";
    }
    private String stopAll() {
        if (_group == null) return "";
        List msgs = _group.stopAllControllers();
        return getMessages(msgs);
    }
    private String startAll() {
        if (_group == null) return "";
        List msgs = _group.startAllControllers();
        return getMessages(msgs);
    }
    private String restartAll() {
        if (_group == null) return "";
        List msgs = _group.restartAllControllers();
        return getMessages(msgs);
    }
    private String reloadConfig() {
        if (_group == null) return "";
        
        _group.reloadControllers();
        return "Config reloaded";
    }
    private String start() {
        if (_tunnel < 0) return "Invalid tunnel";
        
        List controllers = _group.getControllers();
        if (_tunnel >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = (TunnelController)controllers.get(_tunnel);
        controller.startTunnelBackground();
        return "";
    }
    
    private String stop() {
        if (_tunnel < 0) return "Invalid tunnel";
        
        List controllers = _group.getControllers();
        if (_tunnel >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = (TunnelController)controllers.get(_tunnel);
        controller.stopTunnel();
        return "";
    }
    
    private String saveChanges() {
        TunnelController cur = getController(_tunnel);
        
        Properties config = getConfig();
        if (config == null)
            return "Invalid params";
        
        if (cur == null) {
            // creating new
            cur = new TunnelController(config, "", true);
            _group.addController(cur);
            if (cur.getStartOnLoad())
                cur.startTunnelBackground();
        } else {
            cur.setConfig(config, "");
        }
        
        if ("ircclient".equals(cur.getType()) || 
        		"httpclient".equals(cur.getType()) || 
        		"client".equals(cur.getType())) {
            // all clients use the same I2CP session, and as such, use the same
            // I2CP options
            List controllers = _group.getControllers();
            for (int i = 0; i < controllers.size(); i++) {
                TunnelController c = (TunnelController)controllers.get(i);
                if (c == cur) continue;
                //only change when they really are declared of beeing a sharedClient
                if (("httpclient".equals(c.getType()) || 
                		"ircclient".equals(c.getType())||
                		"client".equals(c.getType()) 
                		) && "true".equalsIgnoreCase(c.getSharedClient())) {
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
        
        List msgs = doSave();
        msgs.add(0, "Changes saved");
        return getMessages(msgs);
    }
    private List doSave() { 
        _group.saveConfig();
        return _group.clearAllMessages();
    } 
    private String deleteTunnel() {
        if (!_removeConfirmed)
            return "Please confirm removal";
        
        TunnelController cur = getController(_tunnel);
        if (cur == null)
            return "Invalid tunnel number";
        
        List msgs = _group.removeController(cur);
        msgs.addAll(doSave());
        return getMessages(msgs);
    }
    
    /**
     * Executes any action requested (start/stop/etc) and dump out the 
     * messages.
     *
     */
    public String getMessages() {
        if (_group == null)
            return "";
        
        StringBuffer buf = new StringBuffer(512);
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
    	String theme = _context.getProperty(PROP_THEME_NAME);
    	if (theme != null)
    		return "/themes/console/" + theme + "/";
    	else
    		return "/themes/console/";
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
        return ( ("client".equals(cur.getType())) || 
        		("httpclient".equals(cur.getType())) ||
        		("ircclient".equals(cur.getType())));
    }
    
    public String getTunnelName(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getName();
        else
            return "";
    }
    
    public String getClientPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getListenPort();
        else
            return "";
    }
    
    public String getTunnelType(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return getTypeName(tun.getType());
        else
            return "";
    }
    
    public String getTypeName(String internalType) {
        if ("client".equals(internalType)) return "Standard client";
        else if ("httpclient".equals(internalType)) return "HTTP client";
        else if ("ircclient".equals(internalType)) return "IRC client";
        else if ("server".equals(internalType)) return "Standard server";
        else if ("httpserver".equals(internalType)) return "HTTP server";
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
        if (tun != null)
            return tun.getListenOnInterface();
        else
            return "";
    }
    
    public int getTunnelStatus(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return NOT_RUNNING;
        if (tun.getIsRunning()) return RUNNING;
        else if (tun.getIsStarting()) return STARTING;
        else return NOT_RUNNING;
    }
    
    public String getTunnelDescription(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
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
        if ("client".equals(tun.getType())||"ircclient".equals(tun.getType())) return tun.getTargetDestination();
        else return tun.getProxyList();
    }
    
    public String getServerTarget(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getTargetHost() + ':' + tun.getTargetPort();
        else
            return "";
    }
    
    public String getDestinationBase64(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            String rv = tun.getMyDestination();
            if (rv != null)
                return rv;
            else
                return "";
        } else {
            return "";
        }
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
    /** I2CP host the router is on */
    public void setClientHost(String host) {
        _i2cpHost = (host != null ? host.trim() : null);
        System.out.println("set client host [" + host + "]");
    }
    /** I2CP port the router is on */
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
     * what interface should this client/httpclient/ircclient listen on (unless 
     * overridden by the setReachableByOther() field)
     */
    public void setReachableBy(String reachableBy) { 
        _reachableBy = (reachableBy != null ? reachableBy.trim() : null);
    }
    /**
     * If specified, defines the exact IP interface to listen for requests
     * on (in the case of client/httpclient/ircclient tunnels)
     */
    public void setReachableByOther(String reachableByOther) { 
        _reachableByOther = (reachableByOther != null ? reachableByOther.trim() : null);
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

    /**
     * Based on all provided data, create a set of configuration parameters 
     * suitable for use in a TunnelController.  This will replace (not add to)
     * any existing parameters, so this should return a comprehensive mapping.
     *
     */
    private Properties getConfig() {
        Properties config = new Properties();
        updateConfigGeneric(config);
        
        if ("httpclient".equals(_type)) {
            if (_port != null)
                config.setProperty("listenPort", _port);
            if (_reachableByOther != null)
                config.setProperty("interface", _reachableByOther);
            else
                config.setProperty("interface", _reachableBy);
            if (_proxyList != null)
                config.setProperty("proxyList", _proxyList);

        	config.setProperty("option.inbound.nickname", CLIENT_NICKNAME);
        	config.setProperty("option.outbound.nickname", CLIENT_NICKNAME);
            if (_name != null && !_sharedClient) {
                 config.setProperty("option.inbound.nickname", _name);
                 config.setProperty("option.outbound.nickname", _name);
            }

            config.setProperty("sharedClient", _sharedClient + "");
        }else if ("ircclient".equals(_type)) {
                if (_port != null)
                    config.setProperty("listenPort", _port);
                if (_reachableByOther != null)
                    config.setProperty("interface", _reachableByOther);
                else
                    config.setProperty("interface", _reachableBy);
                if (_targetDestination != null)
                    config.setProperty("targetDestination", _targetDestination);

            	config.setProperty("option.inbound.nickname", CLIENT_NICKNAME);
            	config.setProperty("option.outbound.nickname", CLIENT_NICKNAME);
                if (_name != null && !_sharedClient) {
                     config.setProperty("option.inbound.nickname", _name);
                     config.setProperty("option.outbound.nickname", _name);
                }

                config.setProperty("sharedClient", _sharedClient + "");
        } else if ("client".equals(_type)) {
            if (_port != null)
                config.setProperty("listenPort", _port);
            if (_reachableByOther != null)
                config.setProperty("interface", _reachableByOther);
            else
                config.setProperty("interface", _reachableBy);
            if (_targetDestination != null)
                config.setProperty("targetDestination", _targetDestination);
            
            config.setProperty("option.inbound.nickname", CLIENT_NICKNAME);
            config.setProperty("option.outbound.nickname", CLIENT_NICKNAME);
            if (_name != null && !_sharedClient) {
                config.setProperty("option.inbound.nickname", _name);
                config.setProperty("option.outbound.nickname", _name);
           }
            config.setProperty("sharedClient", _sharedClient + "");
        } else if ("server".equals(_type)) {
            if (_targetHost != null)
                config.setProperty("targetHost", _targetHost);
            if (_targetPort != null)
                config.setProperty("targetPort", _targetPort);
            if (_privKeyFile != null)
                config.setProperty("privKeyFile", _privKeyFile);
        } else if ("httpserver".equals(_type)) {
            if (_targetHost != null)
                config.setProperty("targetHost", _targetHost);
            if (_targetPort != null)
                config.setProperty("targetPort", _targetPort);
            if (_privKeyFile != null)
                config.setProperty("privKeyFile", _privKeyFile);
            if (_spoofedHost != null)
                config.setProperty("spoofedHost", _spoofedHost);
        } else {
            return null;
        }

        return config;
    }
    
    private void updateConfigGeneric(Properties config) {
        config.setProperty("type", _type);
        if (_name != null)
            config.setProperty("name", _name);
        if (_description != null)
            config.setProperty("description", _description);
        if (_i2cpHost != null)
            config.setProperty("i2cpHost", _i2cpHost);
        if ( (_i2cpPort != null) && (_i2cpPort.trim().length() > 0) ) {
            config.setProperty("i2cpPort", _i2cpPort);
        } else {
            config.setProperty("i2cpPort", "7654");
        }
        
        if (_customOptions != null) {
            StringTokenizer tok = new StringTokenizer(_customOptions);
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int eq = pair.indexOf('=');
                if ( (eq <= 0) || (eq >= pair.length()) )
                    continue;
                String key = pair.substring(0, eq);
                String val = pair.substring(eq+1);
                if ("inbound.length".equals(key)) continue;
                if ("outbound.length".equals(key)) continue;
                if ("inbound.quantity".equals(key)) continue;
                if ("outbound.quantity".equals(key)) continue;
                if ("inbound.lengthVariance".equals(key)) continue;
                if ("outbound.lengthVariance".equals(key)) continue;
                if ("inbound.backupQuantity".equals(key)) continue;
                if ("outbound.backupQuantity".equals(key)) continue;
                if ("inbound.nickname".equals(key)) continue;
                if ("outbound.nickname".equals(key)) continue;
                if ("i2p.streaming.connectDelay".equals(key)) continue;
                if ("i2p.streaming.maxWindowSize".equals(key)) continue;
                config.setProperty("option." + key, val);
            }
        }

        config.setProperty("startOnLoad", _startOnLoad + "");

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
        if (_name != null) {
            if ( ((!"client".equals(_type)) && (!"httpclient".equals(_type))&& (!"ircclient".equals(_type))) || (!_sharedClient) ) {
                config.setProperty("option.inbound.nickname", _name);
                config.setProperty("option.outbound.nickname", _name);
            } else {
                config.setProperty("option.inbound.nickname", CLIENT_NICKNAME);
                config.setProperty("option.outbound.nickname", CLIENT_NICKNAME);
            }
        } 
        if ("interactive".equals(_profile))
            config.setProperty("option.i2p.streaming.maxWindowSize", "1");
        else
            config.remove("option.i2p.streaming.maxWindowSize");
    }

    ///
    ///
    ///
    
    protected TunnelController getController(int tunnel) {
        if (tunnel < 0) return null;
        if (_group == null) return null;
        List controllers = _group.getControllers();
        if (controllers.size() > tunnel)
            return (TunnelController)controllers.get(tunnel); 
        else
            return null;
    }
    
    private String getMessages(List msgs) {
        StringBuffer buf = new StringBuffer(128);
        getMessages(msgs, buf);
        return buf.toString();
    }
    private void getMessages(List msgs, StringBuffer buf) {
        if (msgs == null) return;
        for (int i = 0; i < msgs.size(); i++) {
            buf.append((String)msgs.get(i)).append("\n");
        }
    }
}