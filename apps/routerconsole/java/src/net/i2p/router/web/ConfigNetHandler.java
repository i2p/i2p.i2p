package net.i2p.router.web;

import net.i2p.data.RouterInfo;
import net.i2p.router.LoadTestManager;
import net.i2p.router.Router;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.ConfigServiceHandler.UpdateWrapperManagerAndRekeyTask;
import net.i2p.time.Timestamper;

/**
 * Handler to deal with form submissions from the main config form and act
 * upon the values.
 *
 */
public class ConfigNetHandler extends FormHandler {
    private String _hostname;
    private boolean _reseedRequested;
    private boolean _saveRequested;
    private boolean _recheckReachabilityRequested;
    private boolean _timeSyncEnabled;
    private boolean _requireIntroductions;
    private boolean _hiddenMode;
    private boolean _dynamicKeys;
    private String _ntcpHostname;
    private String _ntcpPort;
    private String _tcpPort;
    private String _udpPort;
    private boolean _ntcpAutoIP;
    private boolean _ntcpAutoPort;
    private boolean _upnp;
    private String _inboundRate;
    private String _inboundBurstRate;
    private String _inboundBurst;
    private String _outboundRate;
    private String _outboundBurstRate;
    private String _outboundBurst;
    private String _reseedFrom;
    private boolean _enableLoadTesting;
    private String _sharePct;
    private boolean _ratesOnly; // always false
    
    protected void processForm() {
        if (_saveRequested || ( (_action != null) && ("Save changes".equals(_action)) )) {
            saveChanges();
        } else if (_recheckReachabilityRequested) {
            recheckReachability();
        } else {
            // noop
        }
    }
    
    public void setSave(String moo) { _saveRequested = true; }
    public void setEnabletimesync(String moo) { _timeSyncEnabled = true; }
    public void setRecheckReachability(String moo) { _recheckReachabilityRequested = true; }
    public void setRequireIntroductions(String moo) { _requireIntroductions = true; }
    public void setHiddenMode(String moo) { _hiddenMode = true; }
    public void setDynamicKeys(String moo) { _dynamicKeys = true; }
    public void setEnableloadtesting(String moo) { _enableLoadTesting = true; }
    public void setNtcpAutoIP(String moo) { _ntcpAutoIP = true; }
    public void setNtcpAutoPort(String moo) { _ntcpAutoPort = true; }
    public void setUpnp(String moo) { _upnp = true; }
    
    public void setHostname(String hostname) { 
        _hostname = (hostname != null ? hostname.trim() : null); 
    }
    public void setTcpPort(String port) { 
        _tcpPort = (port != null ? port.trim() : null); 
    }
    public void setNtcphost(String host) {
        _ntcpHostname = (host != null ? host.trim() : null);
    }
    public void setNtcpport(String port) {
        _ntcpPort = (port != null ? port.trim() : null);
    }
    public void setUdpPort(String port) { 
        _udpPort = (port != null ? port.trim() : null); 
    }
    public void setInboundrate(String rate) { 
        _inboundRate = (rate != null ? rate.trim() : null); 
    }
    public void setInboundburstrate(String rate) { 
        _inboundBurstRate = (rate != null ? rate.trim() : null); 
    }
    public void setInboundburstfactor(String factor) { 
        _inboundBurst = (factor != null ? factor.trim() : null); 
    }
    public void setOutboundrate(String rate) { 
        _outboundRate = (rate != null ? rate.trim() : null); 
    }
    public void setOutboundburstrate(String rate) { 
        _outboundBurstRate = (rate != null ? rate.trim() : null); 
    }
    public void setOutboundburstfactor(String factor) { 
        _outboundBurst = (factor != null ? factor.trim() : null); 
    }
    public void setSharePercentage(String pct) {
        _sharePct = (pct != null ? pct.trim() : null);
    }
    
    private void recheckReachability() {
        _context.commSystem().recheckReachability();
        addFormNotice("Rechecking router reachability...");
    }
    
    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        boolean restartRequired = false;
        
        if (!_ratesOnly) {
            // Normalize some things to make the following code a little easier...
            String oldNHost = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME);
            if (oldNHost == null) oldNHost = "";
            String oldNPort = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_PORT);
            if (oldNPort == null) oldNPort = "";
            String sAutoHost = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_IP);
            String sAutoPort = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_PORT);
            boolean oldAutoHost = "true".equalsIgnoreCase(sAutoHost);
            boolean oldAutoPort = "true".equalsIgnoreCase(sAutoPort);
            if (_ntcpHostname == null) _ntcpHostname = "";
            if (_ntcpPort == null) _ntcpPort = "";

            if (oldAutoHost != _ntcpAutoIP || ! oldNHost.equalsIgnoreCase(_ntcpHostname)) {
                if (_ntcpAutoIP) {
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_IP, "true");
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME);
                    addFormNotice("Updating inbound TCP address to auto");
                } else if (_ntcpHostname.length() > 0) {
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME, _ntcpHostname);
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_IP);
                    addFormNotice("Updating inbound TCP address to " + _ntcpHostname);
                } else {
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME);
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_IP);
                    addFormNotice("Disabling inbound TCP");
                }
                restartRequired = true;
            }
            if (oldAutoPort != _ntcpAutoPort || ! oldNPort.equals(_ntcpPort)) {
                if ( _ntcpAutoPort ) {
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_PORT, "true");
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_PORT);
                    addFormNotice("Updating inbound TCP port to auto");
                } else if (_ntcpPort.length() > 0) {
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_PORT, _ntcpPort);
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_PORT);
                    addFormNotice("Updating inbound TCP port to " + _ntcpPort);
                } else {
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_PORT);
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_PORT);
                    addFormNotice("Disabling inbound TCP");
                }
                restartRequired = true;
            }

            if ( (_udpPort != null) && (_udpPort.length() > 0) ) {
                String oldPort = "" + _context.getProperty(UDPTransport.PROP_INTERNAL_PORT, UDPTransport.DEFAULT_INTERNAL_PORT);
                if (!oldPort.equals(_udpPort)) {
                    _context.router().setConfigSetting(UDPTransport.PROP_INTERNAL_PORT, _udpPort);
                    addFormNotice("Updating UDP port from " + oldPort + " to " + _udpPort);
                    restartRequired = true;
                }
            }

        }
        
        updateRates();
        
        if (!_ratesOnly) {
            if (_sharePct != null) {
                String old = _context.router().getConfigSetting(Router.PROP_BANDWIDTH_SHARE_PERCENTAGE);
                if ( (old == null) || (!old.equalsIgnoreCase(_sharePct)) ) {
                    _context.router().setConfigSetting(Router.PROP_BANDWIDTH_SHARE_PERCENTAGE, _sharePct);
                    addFormNotice("Updating bandwidth share percentage");
                }
            }

            // If hidden mode value changes, restart is required
            if (_hiddenMode && "false".equalsIgnoreCase(_context.getProperty(Router.PROP_HIDDEN, "false"))) {
                _context.router().setConfigSetting(Router.PROP_HIDDEN, "true");
                _context.router().addCapabilities(_context.router().getRouterInfo());
                addFormNotice("Gracefully restarting into Hidden Router Mode. Make sure you have no 0-1 length "
                              + "<a href=\"configtunnels.jsp\">tunnels!</a>");
                hiddenSwitch();
            }

            if (!_hiddenMode && "true".equalsIgnoreCase(_context.getProperty(Router.PROP_HIDDEN, "false"))) {
                _context.router().removeConfigSetting(Router.PROP_HIDDEN);
                _context.router().getRouterInfo().delCapability(RouterInfo.CAPABILITY_HIDDEN);
                addFormNotice("Gracefully restarting to exit Hidden Router Mode");
                hiddenSwitch();
            }

            _context.router().setConfigSetting(Router.PROP_DYNAMIC_KEYS, "" + _dynamicKeys);

            if (Boolean.valueOf(_context.getProperty(TransportManager.PROP_ENABLE_UPNP)).booleanValue() !=
                _upnp) {
                if (_upnp)
                    addFormNotice("Enabling UPnP, restart required to take effect");
                else
                    addFormNotice("Disabling UPnP, restart required to take effect");
            }
            _context.router().setConfigSetting(TransportManager.PROP_ENABLE_UPNP, "" + _upnp);

            if (_requireIntroductions) {
                _context.router().setConfigSetting(UDPTransport.PROP_FORCE_INTRODUCERS, "true");
                addFormNotice("Requiring SSU introduers");
            } else {
                _context.router().removeConfigSetting(UDPTransport.PROP_FORCE_INTRODUCERS);
            }

            // Time sync enable, means NOT disabled 
            _context.router().setConfigSetting(Timestamper.PROP_DISABLED, "false");
            
            LoadTestManager.setEnableLoadTesting(_context, _enableLoadTesting);
        }
        
        boolean saved = _context.router().saveConfig();
        if ( (_action != null) && ("Save changes".equals(_action)) ) {
            if (saved) 
                addFormNotice("Configuration saved successfully");
            else
                addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs");
        }
        
        if (restartRequired) {
            //addFormNotice("Performing a soft restart");
            //_context.router().restart();
            //addFormNotice("Soft restart complete");
            // Most of the time we aren't changing addresses, just enabling or disabling
            // things, so let's try just a new routerInfo and see how that works.
            // Maybe we should restart if we change addresses though?
            _context.router().rebuildRouterInfo();
            addFormNotice("Router Info rebuilt");
        }
    }

    private void hiddenSwitch() {
        // Full restart required to generate new keys
        _context.addShutdownTask(new UpdateWrapperManagerAndRekeyTask(Router.EXIT_GRACEFUL_RESTART));
        _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
    }
    
    private void updateRates() {
        boolean updated = false;
        if ( (_inboundRate != null) && (_inboundRate.length() > 0) ) {
            _context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, _inboundRate);
            updated = true;
        }
        if ( (_outboundRate != null) && (_outboundRate.length() > 0) ) {
            _context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, _outboundRate);
            updated = true;
        }
        if ( (_inboundBurstRate != null) && (_inboundBurstRate.length() > 0) ) {
            _context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH, _inboundBurstRate);
            updated = true;
        }
        if ( (_outboundBurstRate != null) && (_outboundBurstRate.length() > 0) ) {
            _context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH, _outboundBurstRate);
            updated = true;
        }
        
        String inBurstRate = _context.router().getConfigSetting(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH);
        
        if (_inboundBurst != null) {
            int rateKBps = 0;
            int burstSeconds = 0;
            try {
                rateKBps = Integer.parseInt(inBurstRate);
                burstSeconds = Integer.parseInt(_inboundBurst);
            } catch (NumberFormatException nfe) {
                // ignore
            }
            if ( (rateKBps > 0) && (burstSeconds > 0) ) {
                int kb = rateKBps * burstSeconds;
                _context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH_PEAK, "" + kb);
                updated = true;
            }
        }
        
        String outBurstRate = _context.router().getConfigSetting(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH);
        
        if (_outboundBurst != null) {
            int rateKBps = 0;
            int burstSeconds = 0;
            try {
                rateKBps = Integer.parseInt(outBurstRate);
                burstSeconds = Integer.parseInt(_outboundBurst);
            } catch (NumberFormatException nfe) {
                // ignore
            }
            if ( (rateKBps > 0) && (burstSeconds > 0) ) {
                int kb = rateKBps * burstSeconds;
                _context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH_PEAK, "" + kb);
                updated = true;
            }
        }
        
        if (updated && !_ratesOnly)
            _context.bandwidthLimiter().reinitialize();
            addFormNotice("Updated bandwidth limits");
    }
}
