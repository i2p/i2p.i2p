package net.i2p.i2ptunnel;

import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * UUUUuuuuuugly glue code to handle bean interaction from the web, process
 * that data, and spit out the results (or the form requested).  The basic 
 * usage is to set any of the fields with data then query the bean via 
 * getActionResults() which triggers the request processing (taking all the 
 * provided data, doing what needs to be done) and returns the results of those
 * activites.  Then a subsequent call to getEditForm() generates the HTML form
 * to either edit the currently selected tunnel (if specified) or add a new one.
 * This functionality is delegated to the WebEditPageFormGenerator.
 *
 */
public class WebEditPageHelper {
    private Log _log;
    private String _action;
    private String _type;
    private String _id;
    private String _name;
    private String _description;
    private String _i2cpHost;
    private String _i2cpPort;
    private String _tunnelDepth;
    private String _tunnelCount;
    private String _customOptions;
    private String _proxyList;
    private String _port;
    private String _reachableBy;
    private String _reachableByOther;
    private String _targetDestination;
    private String _targetHost;
    private String _targetPort;
    private String _privKeyFile;
    private boolean _startOnLoad;
    private boolean _privKeyGenerate;
    private boolean _removeConfirmed;
    
    public WebEditPageHelper() {
        _action = null;
        _type = null;
        _id = null;
        _removeConfirmed = false;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(WebEditPageHelper.class);
    }
    
    /**
     * Used for form submit - either "Save" or Remove"
     */
    public void setAction(String action) { 
        _action = (action != null ? action.trim() : null);   
    }
    /**
     * What type of tunnel (httpclient, client, or server).  This is 
     * required when adding a new tunnel.
     *
     */
    public void setType(String type) { 
        _type = (type != null ? type.trim() : null);   
    }
    /**
     * Which particular tunnel should be edited (index into the current
     * TunnelControllerGroup's getControllers() list).  This is required
     * when editing a tunnel, but not when adding a new one.
     *
     */
    public void setNum(String id) { 
        _id = (id != null ? id.trim() : null);   
    }
    String getType() { return _type; }
    String getNum() { return _id; }
    
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
    }
    /** I2CP port the router is on */
    public void setClientPort(String port) {
        _i2cpPort = (port != null ? port.trim() : null);
    }
    /** how many hops to use for inbound tunnels */
    public void setTunnelDepth(String tunnelDepth) { 
        _tunnelDepth = (tunnelDepth != null ? tunnelDepth.trim() : null);
    }
    /** how many parallel inbound tunnels to use */
    public void setTunnelCount(String tunnelCount) { 
        _tunnelCount = (tunnelCount != null ? tunnelCount.trim() : null);
    }
    /** what I2P session overrides should be used */
    public void setCustomOptions(String customOptions) { 
        _customOptions = (customOptions != null ? customOptions.trim() : null);
    }
    /** what HTTP outproxies should be used (httpclient specific) */
    public void setProxyList(String proxyList) { 
        _proxyList = (proxyList != null ? proxyList.trim() : null);
    }
    /** what port should this client/httpclient listen on */
    public void setPort(String port) { 
        _port = (port != null ? port.trim() : null);
    }
    /** 
     * what interface should this client/httpclient listen on (unless 
     * overridden by the setReachableByOther() field)
     */
    public void setReachableBy(String reachableBy) { 
        _reachableBy = (reachableBy != null ? reachableBy.trim() : null);
    }
    /**
     * If specified, defines the exact IP interface to listen for requests
     * on (in the case of client/httpclient tunnels)
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
    /** What filename is this server tunnel's private keys stored in */
    public void setPrivKeyFile(String file) { 
        _privKeyFile = (file != null ? file.trim() : null);
    }
    /** 
     * If called with any value, we want to generate a new destination
     * for this server tunnel.  This won't cause any existing private keys
     * to be overwritten, however.
     */
    public void setPrivKeyGenerate(String moo) { 
        _privKeyGenerate = true;
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
    
    /**
     * Process the form and display any resulting messages
     *
     */
    public String getActionResults() {
        try {
            return processAction();
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Internal error processing request", t);
            return "Internal error - " + t.getMessage();
        }
    }
    
    /**
     * Generate an HTML form to edit / create a tunnel according to the 
     * specified fields
     */
    public String getEditForm() {
        try {
            return WebEditPageFormGenerator.getForm(this);
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Internal error retrieving edit form", t);
            return "Internal error - " + t.getMessage();
        }
    }

    /**
     * Retrieve the tunnel pointed to by the current id
     *
     */
    TunnelController getTunnelController() {
        if (_id == null) return null;
        int id = -1;
        try {
            id = Integer.parseInt(_id);
            List controllers = TunnelControllerGroup.getInstance().getControllers();
            if ( (id < 0) || (id >= controllers.size()) )
                return null;
            else
                return (TunnelController)controllers.get(id);
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid tunnel id [" + _id + "]", nfe);
            return null;
        }
    }
    
    private String processAction() {
        if ( (_action == null) || (_action.trim().length() <= 0) )
            return "";
        if ("Save".equals(_action))
            return save();
        else if ("Remove".equals(_action))
            return remove();
        else
            return "Action <i>" + _action + "</i> unknown";
    }
    
    private String remove() {
        if (!_removeConfirmed)
            return "Please confirm removal";
        
        TunnelController cur = getTunnelController();
        if (cur == null)
            return "Invalid tunnel number";
        
        List msgs = TunnelControllerGroup.getInstance().removeController(cur);
        msgs.addAll(doSave());
        return getMessages(msgs);
    }
    
    private String save() {
        if (_type == null)
            return "<b>Invalid form submission (no type?)</b>";
        Properties config = getConfig();
        if (config == null)
            return "<b>Invalid params</b>";
        
        TunnelController cur = getTunnelController();
        if (cur == null) {
            // creating new
            cur = new TunnelController(config, "", _privKeyGenerate);
            TunnelControllerGroup.getInstance().addController(cur);
        } else {
            cur.setConfig(config, "");
        }
        
        
        return getMessages(doSave());
    }
    private List doSave() { 
        TunnelControllerGroup.getInstance().saveConfig();
        return TunnelControllerGroup.getInstance().clearAllMessages();
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
        } else if ("client".equals(_type)) {
            if (_port != null)
                config.setProperty("listenPort", _port);
            if (_reachableByOther != null)
                config.setProperty("interface", _reachableByOther);
            else
                config.setProperty("interface", _reachableBy);
            if (_targetDestination != null)
                config.setProperty("targetDestination", _targetDestination);
        } else if ("server".equals(_type)) {
            if (_targetHost != null)
                config.setProperty("targetHost", _targetHost);
            if (_targetPort != null)
                config.setProperty("targetPort", _targetPort);
            if (_privKeyFile != null)
                config.setProperty("privKeyFile", _privKeyFile);
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
        if (_i2cpPort != null)
            config.setProperty("i2cpPort", _i2cpPort);
        
        if (_customOptions != null) {
            StringTokenizer tok = new StringTokenizer(_customOptions);
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int eq = pair.indexOf('=');
                if ( (eq <= 0) || (eq >= pair.length()) )
                    continue;
                String key = pair.substring(0, eq);
                String val = pair.substring(eq+1);
                if ("tunnels.numInbound".equals(key)) continue;
                if ("tunnels.depthInbound".equals(key)) continue;
                config.setProperty("option." + key, val);
            }
        }

        config.setProperty("startOnLoad", _startOnLoad + "");
        
        if (_tunnelCount != null)
            config.setProperty("option.tunnels.numInbound", _tunnelCount);
        if (_tunnelDepth != null)
            config.setProperty("option.tunnels.depthInbound", _tunnelDepth);
    }

    /**
     * Pretty print the messages provided
     *
     */
    private String getMessages(List msgs) {
        if (msgs == null) return "";
        int num = msgs.size();
        switch (num) {
            case 0: return "";
            case 1: return (String)msgs.get(0);
            default:
                StringBuffer buf = new StringBuffer(512);
                buf.append("<ul>");
                for (int i = 0; i < num; i++)
                    buf.append("<li>").append((String)msgs.get(i)).append("</li>\n");
                buf.append("</ul>\n");
                return buf.toString();
        }
    }
}
