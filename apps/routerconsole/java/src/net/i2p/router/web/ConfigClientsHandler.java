package net.i2p.router.web;

import net.i2p.router.ClientTunnelSettings;

/**
 * Handler to deal with form submissions from the client config form and act
 * upon the values.
 *
 */
public class ConfigClientsHandler extends FormHandler {
    private String _numClients;
    private String _numTunnels;
    private String _numHops;
    private String _numHopsOutbound;
    private boolean _shouldSave;
    
    public void ConfigNetHandler() {
        _shouldSave = false;
    }
    
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else {
            // noop
        }
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }
    
    public void setClientcount(String num) {
        _numClients = (num != null ? num.trim(): null);
    }
    public void setTunnelcount(String num) {
        _numTunnels = (num != null ? num.trim() : null);
    }
    public void setTunneldepth(String num) {
        _numHops = (num != null ? num.trim() : null);
    }
    public void setTunneldepthoutbound(String num) {
        _numHopsOutbound = (num != null ? num.trim() : null);
    }
    
    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        boolean saveRequired = false;
        
        if ( (_numClients != null) && (_numClients.length() > 0) ) {
            _context.router().setConfigSetting("router.targetClients", _numClients);
            addFormNotice("Updating estimated number of clients to " + _numClients);
            saveRequired = true;
        }
        
        if ( (_numTunnels != null) && (_numTunnels.length() > 0) ) {
            _context.router().setConfigSetting(ClientTunnelSettings.PROP_NUM_INBOUND, _numTunnels);
            addFormNotice("Updating default number of tunnels per client to " + _numTunnels);
            saveRequired = true;
        }
        
        if ( (_numHops != null) && (_numHops.length() > 0) ) {
            _context.router().setConfigSetting(ClientTunnelSettings.PROP_DEPTH_INBOUND, _numHops);
            addFormNotice("Updating default tunnel length to " + _numHops);
            saveRequired = true;
        }
        
        if ( (_numHopsOutbound != null) && (_numHopsOutbound.length() > 0) ) {
            _context.router().setConfigSetting(ClientTunnelSettings.PROP_DEPTH_OUTBOUND, _numHopsOutbound);
            addFormNotice("Updating default outbound tunnel length to " + _numHopsOutbound);
            saveRequired = true;
        }
        
        if (saveRequired) {
            boolean saved = _context.router().saveConfig();
            if (saved) 
                addFormNotice("Configuration saved successfully");
            else
                addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs");
        }
    }
}
