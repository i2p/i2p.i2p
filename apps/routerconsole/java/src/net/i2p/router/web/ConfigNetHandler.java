package net.i2p.router.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.router.Router;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.ConfigServiceHandler;

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
    private String _udpHost1;
    private String _udpHost2;
    private String _udpPort;
    private String _udpAutoIP;
    private String _ntcpAutoIP;
    private boolean _ntcpAutoPort;
    private boolean _upnp;
    private boolean _laptop;
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
    private boolean _udpDisabled;
    private final Map<String, String> changes = new HashMap();
    private static final String PROP_HIDDEN = Router.PROP_HIDDEN_HIDDEN; // see Router for other choice
    
    @Override
    protected void processForm() {
        if (_saveRequested || ( (_action != null) && (_("Save changes").equals(_action)) )) {
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
    public void setDynamicKeys(String moo) { _dynamicKeys = true; }
    public void setEnableloadtesting(String moo) { _enableLoadTesting = true; }
    public void setUdpAutoIP(String mode) {
        _udpAutoIP = mode;
        _hiddenMode = "hidden".equals(mode);
    }
    public void setNtcpAutoIP(String mode) {
        _ntcpAutoIP = mode;
    }
    public void setNtcpAutoPort(String mode) {
        _ntcpAutoPort = mode.equals("2");
    }
    public void setUpnp(String moo) { _upnp = true; }
    public void setLaptop(String moo) { _laptop = true; }
    
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
    public void setUdpHost1(String host) { 
        _udpHost1 = (host != null ? host.trim() : null); 
    }
    public void setUdpHost2(String host) { 
        _udpHost2 = (host != null ? host.trim() : null); 
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
    
    /** @since 0.8.12 */
    public void setRatesOnly(String foo) {
        _ratesOnly = true;
    }
    
    /** @since 0.8.13 */
    public void setDisableUDP(String foo) {
        _udpDisabled = true;
    }
    
    private void recheckReachability() {
        _context.commSystem().recheckReachability();
        addFormNotice(_("Rechecking router reachability..."));
    }
    
    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        boolean restartRequired = false;
        List<String> removes = new ArrayList();
        
        if (!_ratesOnly) {
            // IP Settings
            String oldUdp = _context.getProperty(UDPTransport.PROP_SOURCES,
                                                 _context.router().isHidden() ? "hidden" : UDPTransport.DEFAULT_SOURCES);
            String oldUHost = _context.getProperty(UDPTransport.PROP_EXTERNAL_HOST, "");
            if (_udpAutoIP != null) {
                String uhost = "";
                if (_udpAutoIP.equals("fixed")) {
                    if (_udpHost1 != null && _udpHost1.length() > 0)
                        uhost =  _udpHost1;
                    else if (_udpHost2 != null && _udpHost2.length() > 0)
                        uhost =  _udpHost2;
                    else
                        _udpAutoIP = UDPTransport.DEFAULT_SOURCES;
                }
                changes.put(UDPTransport.PROP_SOURCES, _udpAutoIP);
                boolean valid = true;
                if (uhost.length() > 0) {
                    valid = verifyAddress(uhost);
                    if (valid) {
                        changes.put(UDPTransport.PROP_EXTERNAL_HOST, uhost);
                    }
                } else {
                    removes.add(UDPTransport.PROP_EXTERNAL_HOST);
                }
                if (valid && ((!oldUdp.equals(_udpAutoIP)) || (!oldUHost.equals(uhost)))) {
                   addFormNotice(_("Updating IP address"));
                   restartRequired = true;
                }
            }

            // NTCP Settings
            // Normalize some things to make the following code a little easier...
            String oldNHost = _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME, "");
            String oldNPort = _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_PORT, "");
            String oldAutoHost = _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_IP, "true");
            String sAutoPort = _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_PORT, "true");
            boolean oldAutoPort = Boolean.valueOf(sAutoPort).booleanValue();
            if (_ntcpHostname == null) _ntcpHostname = "";
            if (_ntcpPort == null) _ntcpPort = "";
            if (_ntcpAutoIP == null) _ntcpAutoIP = "true";

            if ((!oldAutoHost.equals(_ntcpAutoIP)) || ! oldNHost.equalsIgnoreCase(_ntcpHostname)) {
                boolean valid = true;
                if ("disabled".equals(_ntcpAutoIP)) {
                    addFormNotice(_("Disabling TCP completely"));
                } else if ("false".equals(_ntcpAutoIP) && _ntcpHostname.length() > 0) {
                    valid = verifyAddress(_ntcpHostname);
                    if (valid) {
                        changes.put(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME, _ntcpHostname);
                        addFormNotice(_("Updating inbound TCP address to") + " " + _ntcpHostname);
                    }
                } else {
                    removes.add(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME);
                    if ("false".equals(_ntcpAutoIP))
                        addFormNotice(_("Disabling inbound TCP"));
                    else
                        addFormNotice(_("Updating inbound TCP address to auto")); // true or always
                }
                if (valid) {
                    changes.put(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_IP, _ntcpAutoIP);
                    changes.put(TransportManager.PROP_ENABLE_NTCP, "" + !"disabled".equals(_ntcpAutoIP));
                    restartRequired = true;
                }
            }
            if (oldAutoPort != _ntcpAutoPort || ! oldNPort.equals(_ntcpPort)) {
                if (_ntcpPort.length() > 0 && !_ntcpAutoPort) {
                    changes.put(ConfigNetHelper.PROP_I2NP_NTCP_PORT, _ntcpPort);
                    addFormNotice(_("Updating inbound TCP port to") + " " + _ntcpPort);
                } else {
                    removes.add(ConfigNetHelper.PROP_I2NP_NTCP_PORT);
                    addFormNotice(_("Updating inbound TCP port to auto"));
                }
                changes.put(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_PORT, "" + _ntcpAutoPort);
                restartRequired = true;
            }

            // UDP Settings
            if ( (_udpPort != null) && (_udpPort.length() > 0) ) {
                String oldPort = "" + _context.getProperty(UDPTransport.PROP_INTERNAL_PORT, UDPTransport.DEFAULT_INTERNAL_PORT);
                if (!oldPort.equals(_udpPort)) {
                    changes.put(UDPTransport.PROP_INTERNAL_PORT, _udpPort);
                    changes.put(UDPTransport.PROP_EXTERNAL_PORT, _udpPort);
                    addFormNotice(_("Updating UDP port from") + " " + oldPort + " " + _("to") + " " + _udpPort);
                    restartRequired = true;
                }
            }

        }
        
        boolean ratesUpdated = updateRates();
        
        boolean switchRequired = false;
        if (!_ratesOnly) {
            // If hidden mode value changes, restart is required
            switchRequired = _hiddenMode != _context.router().isHidden();
            if (switchRequired) {
                changes.put(PROP_HIDDEN, "" + _hiddenMode);
                if (_hiddenMode)
                    addFormError(_("Gracefully restarting into Hidden Router Mode"));
                else
                    addFormError(_("Gracefully restarting to exit Hidden Router Mode"));
            }

            changes.put(Router.PROP_DYNAMIC_KEYS, "" + _dynamicKeys);

            if (Boolean.valueOf(_context.getProperty(TransportManager.PROP_ENABLE_UPNP)).booleanValue() !=
                _upnp) {
                // This is minor, don't set restartRequired
                if (_upnp)
                    addFormNotice(_("Enabling UPnP, restart required to take effect"));
                else
                    addFormNotice(_("Disabling UPnP, restart required to take effect"));
            }
            changes.put(TransportManager.PROP_ENABLE_UPNP, "" + _upnp);

            if (Boolean.valueOf(_context.getProperty(UDPTransport.PROP_LAPTOP_MODE)).booleanValue() !=
                _laptop) {
                // This is minor, don't set restartRequired
                if (_laptop)
                    addFormNotice(_("Enabling laptop mode"));
                else
                    addFormNotice(_("Disabling laptop mode"));
            }
            changes.put(UDPTransport.PROP_LAPTOP_MODE, "" + _laptop);

            if (_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP) !=
                !_udpDisabled) {
                if (_udpDisabled)
                    addFormNotice(_("Disabling UDP"));
                else
                    addFormNotice(_("Enabling UDP"));
                restartRequired = true;
            }
            changes.put(TransportManager.PROP_ENABLE_UDP, "" + (!_udpDisabled));

            if (_requireIntroductions) {
                changes.put(UDPTransport.PROP_FORCE_INTRODUCERS, "true");
                addFormNotice(_("Requiring SSU introducers"));
            } else {
                removes.add(UDPTransport.PROP_FORCE_INTRODUCERS);
            }

            // Time sync enable, means NOT disabled 
            // Hmm router sets this at startup, not required here
            //changes.put(Timestamper.PROP_DISABLED, "false");
            
            // Hidden in the GUI
            //LoadTestManager.setEnableLoadTesting(_context, _enableLoadTesting);
        }
        
        boolean saved = _context.router().saveConfig(changes, removes);
        if (saved) 
            addFormNotice(_("Configuration saved successfully"));
        else
            addFormError(_("Error saving the configuration (applied but not saved) - please see the error logs"));

        // this has to be after the save
        if (ratesUpdated)
            _context.bandwidthLimiter().reinitialize();
        
        if (switchRequired) {
            hiddenSwitch();
        } else if (restartRequired) {
            //if (_context.hasWrapper()) {
                // Wow this dumps all conns immediately and really isn't nice
                addFormNotice("Performing a soft restart");
                _context.router().restart();
                // restart() returns immediately now
                //addFormNotice("Soft restart complete");

                // Most of the time we aren't changing addresses, just enabling or disabling
                // things, so let's try just a new routerInfo and see how that works.
                // Maybe we should restart if we change addresses though?
                // No, this doesn't work well, really need to call SSU Transport externalAddressReceived(),
                // but that's hard to get to, and doesn't handle port changes, etc.
                // So don't do this...
                //_context.router().rebuildRouterInfo();
                //addFormNotice("Router Info rebuilt");
            //} else {
                // There's a few changes that don't really require restart (e.g. enabling inbound TCP)
                // But it would be hard to get right, so just do a restart.
                //addFormError(_("Gracefully restarting I2P to change published router address"));
                //_context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            //}
        }
    }

    /**
     *  Do basic verification of address here to prevent problems later
     *  @return valid
     *  @since 0.8.9
     */
    private boolean verifyAddress(String addr) {
        if (addr == null || addr.length() <= 0)
            return false;
        try {
            InetAddress ia = InetAddress.getByName(addr);
            byte[] iab = ia.getAddress();
            boolean rv = TransportImpl.isPubliclyRoutable(iab);
            if (!rv)
                addFormError(_("The hostname or IP {0} is not publicly routable", addr));
            return rv;
        } catch (UnknownHostException uhe) {
            addFormError(_("The hostname or IP {0} is invalid", addr) + ": " + uhe);
            return false;
        }
    }

    private void hiddenSwitch() {
        // Full restart required to generate new keys
        // FIXME don't call wrapper if not present, only rekey
        ConfigServiceHandler.registerWrapperNotifier(_context, Router.EXIT_GRACEFUL_RESTART, false);
        _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
    }
    
    private static final int DEF_BURST_PCT = 10;
    private static final int DEF_BURST_TIME = 20;

    /**
     *  @return changed
     */
    private boolean updateRates() {
        boolean updated = false;
        boolean bwUpdated = false;

        if (_sharePct != null) {
            String old = _context.router().getConfigSetting(Router.PROP_BANDWIDTH_SHARE_PERCENTAGE);
            if ( (old == null) || (!old.equals(_sharePct)) ) {
                changes.put(Router.PROP_BANDWIDTH_SHARE_PERCENTAGE, _sharePct);
                addFormNotice(_("Updating bandwidth share percentage"));
                updated = true;
            }
        }

        // Since burst is now hidden in the gui, set burst to +10% for 20 seconds
        if ( (_inboundRate != null) && (_inboundRate.length() > 0) &&
            !_inboundRate.equals(_context.getProperty(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, "" + FIFOBandwidthRefiller.DEFAULT_INBOUND_BANDWIDTH))) {
            changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, _inboundRate);
            try {
                int rate = Integer.parseInt(_inboundRate) * (100 + DEF_BURST_PCT) / 100;
                int kb = DEF_BURST_TIME * rate;
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH, "" + rate);
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH_PEAK, "" + kb);
            } catch (NumberFormatException nfe) {}
            bwUpdated = true;
        }
        if ( (_outboundRate != null) && (_outboundRate.length() > 0) &&
            !_outboundRate.equals(_context.getProperty(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, "" + FIFOBandwidthRefiller.DEFAULT_OUTBOUND_BANDWIDTH))) {
            changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, _outboundRate);
            try {
                int rate = Integer.parseInt(_outboundRate) * (100 + DEF_BURST_PCT) / 100;
                int kb = DEF_BURST_TIME * rate;
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH, "" + rate);
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH_PEAK, "" + kb);
            } catch (NumberFormatException nfe) {}
            bwUpdated = true;
        }

        if (bwUpdated) {
            addFormNotice(_("Updated bandwidth limits"));
            updated = true;
        }

/******* These aren't in the GUI for now

        if ( (_inboundBurstRate != null) && (_inboundBurstRate.length() > 0) &&
            !_inboundBurstRate.equals(_context.getProperty(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH, "" + FIFOBandwidthRefiller.DEFAULT_INBOUND_BURST_BANDWIDTH))) {
            changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH, _inboundBurstRate);
            updated = true;
        }
        if ( (_outboundBurstRate != null) && (_outboundBurstRate.length() > 0) &&
            !_outboundBurstRate.equals(_context.getProperty(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH, "" + FIFOBandwidthRefiller.DEFAULT_OUTBOUND_BURST_BANDWIDTH))) {
            changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH, _outboundBurstRate);
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
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH_PEAK, "" + kb);
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
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH_PEAK, "" + kb);
                updated = true;
            }
        }

***********/

        return updated; 
    }
}
