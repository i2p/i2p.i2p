package net.i2p.router.web;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;

import java.net.URL;
import java.net.URLConnection;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.time.Timestamper;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.Router;
import net.i2p.router.LoadTestManager;
import net.i2p.data.RouterInfo;
import net.i2p.router.web.ConfigServiceHandler.UpdateWrapperManagerTask;
import net.i2p.router.web.ConfigServiceHandler.UpdateWrapperManagerAndRekeyTask;

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
    private String _inboundRate;
    private String _inboundBurstRate;
    private String _inboundBurst;
    private String _outboundRate;
    private String _outboundBurstRate;
    private String _outboundBurst;
    private String _reseedFrom;
    private boolean _enableLoadTesting;
    private String _sharePct;
    private boolean _ratesOnly;
    
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
    public void setUpdateratesonly(String moo) { _ratesOnly = true; }
    public void setEnableloadtesting(String moo) { _enableLoadTesting = true; }
    
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
            if ( (_hostname != null) && (_hostname.length() > 0) ) {
                String oldHost = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_HOSTNAME);
                if ( (oldHost == null) || (!oldHost.equalsIgnoreCase(_hostname)) ) {
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_HOSTNAME, _hostname);
                    addFormNotice("Updating hostname from " + oldHost + " to " + _hostname);
                    restartRequired = true;
                }
            }
            if ( (_tcpPort != null) && (_tcpPort.length() > 0) ) {
                String oldPort = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_PORT);
                if ( (oldPort == null) && (_tcpPort.equals("8887")) ) {
                    // still on default.. noop
                } else if ( (oldPort == null) || (!oldPort.equalsIgnoreCase(_tcpPort)) ) {
                    // its not the default OR it has changed
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_PORT, _tcpPort);
                    addFormNotice("Updating TCP port from " + oldPort + " to " + _tcpPort);
                    restartRequired = true;
                }
            }
            
            if ( (_ntcpHostname != null) && (_ntcpHostname.length() > 0) && (_ntcpPort != null) && (_ntcpPort.length() > 0) ) {
                String oldHost = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME);
                String oldPort = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_PORT);
                if ( (oldHost == null) || (!oldHost.equalsIgnoreCase(_ntcpHostname)) ||
                     (oldPort == null) || (!oldPort.equalsIgnoreCase(_ntcpPort)) ) {
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME, _ntcpHostname);
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_PORT, _ntcpPort);
                    addFormNotice("Updating inbound TCP settings from " + oldHost + ":" + oldPort 
                                  + " to " + _ntcpHostname + ":" + _ntcpPort);
                    restartRequired = true;
                }
            } else {
                String oldHost = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME);
                String oldPort = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_PORT);
                if ( (oldHost != null) || (oldPort != null) ) {
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME);
                    _context.router().removeConfigSetting(ConfigNetHelper.PROP_I2NP_NTCP_PORT);
                    addFormNotice("Updating inbound TCP settings from " + oldHost + ":" + oldPort 
                                  + " so that we no longer receive inbound TCP connections");
                    restartRequired = true;
                }
            }

            if ( (_udpPort != null) && (_udpPort.length() > 0) ) {
                String oldPort = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_UDP_PORT);
                if ( (oldPort == null) && (_udpPort.equals("8887")) ) {
                    // still on default.. noop
                } else if ( (oldPort == null) || (!oldPort.equalsIgnoreCase(_udpPort)) ) {
                    // its not the default OR it has changed
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_PORT, _udpPort);
                    addFormNotice("Updating UDP port from " + oldPort + " to " + _udpPort);
                    restartRequired = true;
                }
            }

        }
        
        updateRates();
        
        if (!_ratesOnly) {
            if (_sharePct != null) {
                String old = _context.router().getConfigSetting(ConfigNetHelper.PROP_SHARE_PERCENTAGE);
                if ( (old == null) || (!old.equalsIgnoreCase(_sharePct)) ) {
                    _context.router().setConfigSetting(ConfigNetHelper.PROP_SHARE_PERCENTAGE, _sharePct);
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

            if (_dynamicKeys) {
                _context.router().setConfigSetting(Router.PROP_DYNAMIC_KEYS, "true");
            } else {
                _context.router().removeConfigSetting(Router.PROP_DYNAMIC_KEYS);
            }

            if (_requireIntroductions) {
                _context.router().setConfigSetting(UDPTransport.PROP_FORCE_INTRODUCERS, "true");
                addFormNotice("Requiring SSU introduers");
            } else {
                _context.router().removeConfigSetting(UDPTransport.PROP_FORCE_INTRODUCERS);
            }

            if (true || _timeSyncEnabled) {
                // Time sync enable, means NOT disabled 
                _context.router().setConfigSetting(Timestamper.PROP_DISABLED, "false");
            } else {
                _context.router().setConfigSetting(Timestamper.PROP_DISABLED, "true");
            }
            
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
            addFormNotice("Performing a soft restart");
            _context.router().restart();
            addFormNotice("Soft restart complete");
        }
    }

    private void hiddenSwitch() {
        // Full restart required to generate new keys
        _context.router().addShutdownTask(new UpdateWrapperManagerAndRekeyTask(Router.EXIT_GRACEFUL_RESTART));
        _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
    }
    
    private void updateRates() {
        boolean updated = false;
        if ( (_inboundRate != null) && (_inboundRate.length() > 0) ) {
            _context.router().setConfigSetting(ConfigNetHelper.PROP_INBOUND_KBPS, _inboundRate);
            updated = true;
        }
        if ( (_outboundRate != null) && (_outboundRate.length() > 0) ) {
            _context.router().setConfigSetting(ConfigNetHelper.PROP_OUTBOUND_KBPS, _outboundRate);
            updated = true;
        }
        if ( (_inboundBurstRate != null) && (_inboundBurstRate.length() > 0) ) {
            _context.router().setConfigSetting(ConfigNetHelper.PROP_INBOUND_BURST_KBPS, _inboundBurstRate);
            updated = true;
        }
        if ( (_outboundBurstRate != null) && (_outboundBurstRate.length() > 0) ) {
            _context.router().setConfigSetting(ConfigNetHelper.PROP_OUTBOUND_BURST_KBPS, _outboundBurstRate);
            updated = true;
        }
        
        String inBurstRate = _context.router().getConfigSetting(ConfigNetHelper.PROP_INBOUND_BURST_KBPS);
        
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
                _context.router().setConfigSetting(ConfigNetHelper.PROP_INBOUND_BURST, "" + kb);
                updated = true;
            }
        }
        
        String outBurstRate = _context.router().getConfigSetting(ConfigNetHelper.PROP_OUTBOUND_BURST_KBPS);
        
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
                _context.router().setConfigSetting(ConfigNetHelper.PROP_OUTBOUND_BURST, "" + kb);
                updated = true;
            }
        }
        
        if (updated && !_ratesOnly)
            addFormNotice("Updated bandwidth limits");
    }
}
