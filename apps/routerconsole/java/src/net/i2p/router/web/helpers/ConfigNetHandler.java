package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.router.web.FormHandler;
import net.i2p.util.Addresses;

/**
 * Handler to deal with form submissions from the main config form and act
 * upon the values.
 *
 * Used for both /config and /confignet
 */
public class ConfigNetHandler extends FormHandler {
    private String _hostname;
    private boolean _saveRequested;
    private boolean _recheckReachabilityRequested;
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
    //private String _inboundBurstRate;
    //private String _inboundBurst;
    private String _outboundRate;
    //private String _outboundBurstRate;
    //private String _outboundBurst;
    private String _sharePct;
    private boolean _ratesOnly;
    private boolean _udpDisabled;
    private String _ipv6Mode;
    private boolean _ipv4Firewalled;
    private boolean _ipv6Firewalled;
    private final Map<String, String> changes = new HashMap<String, String>();
    private static final String PROP_HIDDEN = Router.PROP_HIDDEN_HIDDEN; // see Router for other choice
    
    @Override
    protected void processForm() {
        if (_saveRequested || ( (_action != null) && (_t("Save changes").equals(_action)) )) {
            saveChanges();
        //} else if (_recheckReachabilityRequested) {
        //    recheckReachability();
        } else {
            // noop
        }
    }
    
    public void setSave(String moo) { _saveRequested = true; }
    public void setRecheckReachability(String moo) { _recheckReachabilityRequested = true; }
    public void setRequireIntroductions(String moo) { _requireIntroductions = true; }
    public void setDynamicKeys(String moo) { _dynamicKeys = true; }
    public void setEnableloadtesting(String moo) { }
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

    /** @since 0.9.20 */
    public void setIPv4Firewalled(String moo) { _ipv4Firewalled = true; }

    /** @since 0.9.28 */
    public void setIPv6Firewalled(String moo) { _ipv6Firewalled = true; }
    
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
    public void setUdpPort(String port) { 
        _udpPort = (port != null ? port.trim() : null); 
    }
    public void setInboundrate(String rate) { 
        _inboundRate = (rate != null ? rate.trim() : null); 
    }

/*
    public void setInboundburstrate(String rate) { 
        _inboundBurstRate = (rate != null ? rate.trim() : null); 
    }
    public void setInboundburstfactor(String factor) { 
        _inboundBurst = (factor != null ? factor.trim() : null); 
    }
****/

    public void setOutboundrate(String rate) { 
        _outboundRate = (rate != null ? rate.trim() : null); 
    }

/*
    public void setOutboundburstrate(String rate) { 
        _outboundBurstRate = (rate != null ? rate.trim() : null); 
    }
    public void setOutboundburstfactor(String factor) { 
        _outboundBurst = (factor != null ? factor.trim() : null); 
    }
****/

    public void setSharePercentage(String pct) {
        _sharePct = (pct != null ? pct.trim() : null);
    }
    
    /** @since 0.8.12 */
    public void setRatesOnly(String foo) {
        _ratesOnly = true;
    }
    
    /** @since 0.8.13 */
    public void setDisableUDP(String foo) {
        _udpDisabled = "disabled".equals(foo);
    }
    
    /** @since IPv6 */
    public void setIpv6(String mode) {
        _ipv6Mode = mode;
    }
    
/****
    private void recheckReachability() {
        _context.commSystem().recheckReachability();
        addFormNotice(_t("Rechecking router reachability..."));
    }
****/
    
    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        boolean restartRequired = false;
        boolean error = false;
        List<String> removes = new ArrayList<String>();
        
        if (!_ratesOnly) {
            // IP Settings
            String oldUdp = _context.getProperty(UDPTransport.PROP_SOURCES,
                                                 _context.router().isHidden() ? "hidden" : UDPTransport.DEFAULT_SOURCES);
            String oldUHost = _context.getProperty(UDPTransport.PROP_EXTERNAL_HOST, "");
            // force change to fixed if user enters a host name/IP
            if (_udpHost1 != null && _udpHost1.length() > 0)
                _udpAutoIP = "fixed";
            if (_udpAutoIP != null) {
                String uhost = "";
                if (_udpAutoIP.equals("fixed")) {
                    if (_settings == null)
                        _settings = Collections.EMPTY_MAP;
                    Set<String> addrs = new TreeSet<String>();
                    for (Object o : _settings.keySet()) {
                        String k = (String) o;
                        if (k.startsWith("addr_")) {
                            String v = DataHelper.stripHTML(k.substring(5));
                            if (v.length() > 0)
                                addrs.add(v);
                        }
                    }
                    if (_udpHost1 != null && _udpHost1.length() > 0) {
                        if (verifyAddress(_udpHost1)) {
                            addrs.add(_udpHost1);
                        } else {
                            // verifyAddress() outputs form error
                            error = true;
                        }
                    }
                    int tot = addrs.size();
                    int i = 0;
                    if (tot > 0) {
                        StringBuilder buf = new StringBuilder(128);
                        for (String addr : addrs) {
                            buf.append(addr);
                            if (++i < tot)
                                buf.append(',');
                        }
                        uhost = buf.toString();
                        changes.put(UDPTransport.PROP_EXTERNAL_HOST, uhost);
                    } else {
                        _udpAutoIP = UDPTransport.DEFAULT_SOURCES;
                        removes.add(UDPTransport.PROP_EXTERNAL_HOST);
                    }
                } else {
                    // not fixed
                    if (oldUHost.length() > 0)
                        removes.add(UDPTransport.PROP_EXTERNAL_HOST);
                }
                changes.put(UDPTransport.PROP_SOURCES, _udpAutoIP);
                if ((!oldUdp.equals(_udpAutoIP)) || (!oldUHost.equals(uhost))) {
                   addFormNotice(_t("Updating IP address"));
                   restartRequired = true;
                }
            }
            if (_ipv6Mode != null) {
                // take care not to set default, as it will change
                String tcp6 = _context.getProperty(TransportUtil.NTCP_IPV6_CONFIG);
                if (tcp6 == null)
                    tcp6 = TransportUtil.DEFAULT_IPV6_CONFIG.toConfigString();
                String udp6 = _context.getProperty(TransportUtil.SSU_IPV6_CONFIG);
                if (udp6 == null)
                    udp6 = TransportUtil.DEFAULT_IPV6_CONFIG.toConfigString();
                boolean ch = false;
                if (!_ipv6Mode.equals(tcp6)) {
                    changes.put(TransportUtil.NTCP_IPV6_CONFIG, _ipv6Mode);
                    ch = true;
                }
                if (!_ipv6Mode.equals(udp6)) {
                    changes.put(TransportUtil.SSU_IPV6_CONFIG, _ipv6Mode);
                    ch = true;
                }
                if (ch)
                    addFormNotice(_t("Updating IPv6 setting"));
            }

            // NTCP Settings
            // Normalize some things to make the following code a little easier...
            String oldNHost = _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME, "");
            String oldNPort = _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_PORT, "");
            String oldAutoHost = _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_IP, "true");
            String sAutoPort = _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_PORT, "true");
            boolean oldAutoPort = Boolean.parseBoolean(sAutoPort);
            if (_ntcpHostname == null) _ntcpHostname = "";
            if (_ntcpPort == null) _ntcpPort = "";
            if (_ntcpAutoIP == null) _ntcpAutoIP = "true";

            if ((!oldAutoHost.equals(_ntcpAutoIP)) || ! oldNHost.equalsIgnoreCase(_ntcpHostname)) {
                boolean valid = true;
                if ("disabled".equals(_ntcpAutoIP)) {
                    addFormNotice(_t("Disabling TCP completely"));
                } else if ("false".equals(_ntcpAutoIP) && _ntcpHostname.length() > 0) {
                    valid = verifyAddress(_ntcpHostname);
                    if (valid) {
                        changes.put(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME, _ntcpHostname);
                        addFormNotice(_t("Updating TCP address to {0}", _ntcpHostname));
                    } else {
                        error = true;
                    }
                } else {
                    removes.add(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME);
                    if ("false".equals(_ntcpAutoIP))
                        addFormNotice(_t("Disabling inbound TCP"));
                    else
                        addFormNotice(_t("Updating inbound TCP address to auto")); // true or always
                }
                if (valid) {
                    changes.put(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_IP, _ntcpAutoIP);
                    changes.put(TransportManager.PROP_ENABLE_NTCP, "" + !"disabled".equals(_ntcpAutoIP));
                    restartRequired = true;
                }
            }
            if (oldAutoPort != _ntcpAutoPort || ! oldNPort.equals(_ntcpPort)) {
                if (_ntcpPort.length() > 0 && !_ntcpAutoPort) {
                    int port = Addresses.getPort(_ntcpPort);
                    if (port != 0) {
                        changes.put(ConfigNetHelper.PROP_I2NP_NTCP_PORT, _ntcpPort);
                        addFormNotice(_t("Updating TCP port to {0}", _ntcpPort));
                        if (port < 1024) {
                            addFormError(_t("Warning - ports less than 1024 are not recommended"));
                            error = true;
                        }
                    } else {
                        addFormError(_t("Invalid port") + ": " + _ntcpPort);
                        error = true;
                    }
                } else {
                    removes.add(ConfigNetHelper.PROP_I2NP_NTCP_PORT);
                    addFormNotice(_t("Updating inbound TCP port to auto"));
                }
                changes.put(ConfigNetHelper.PROP_I2NP_NTCP_AUTO_PORT, "" + _ntcpAutoPort);
                restartRequired = true;
            }

            // UDP Settings
            if ( (_udpPort != null) && (_udpPort.length() > 0) ) {
                String oldPort = _context.getProperty(UDPTransport.PROP_INTERNAL_PORT, "unset");
                if (!oldPort.equals(_udpPort)) {
                    int port = Addresses.getPort(_udpPort);
                    if (port != 0) {
                        changes.put(UDPTransport.PROP_INTERNAL_PORT, _udpPort);
                        changes.put(UDPTransport.PROP_EXTERNAL_PORT, _udpPort);
                        addFormNotice(_t("Updating UDP port to {0}", _udpPort));
                        if (port < 1024) {
                            addFormError(_t("Warning - ports less than 1024 are not recommended"));
                            error = true;
                        } else {
                            restartRequired = true;
                        }
                    } else {
                        addFormError(_t("Invalid port") + ": " + _udpPort);
                        error = true;
                    }
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
                    addFormError(_t("Gracefully restarting into Hidden Router Mode"));
                else
                    addFormError(_t("Gracefully restarting to exit Hidden Router Mode"));
            }

            changes.put(Router.PROP_REBUILD_KEYS, "" + switchRequired);
            changes.put(Router.PROP_DYNAMIC_KEYS, "" + _dynamicKeys);

            if (_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UPNP) !=
                _upnp) {
                // This is minor, don't set restartRequired
                if (_upnp)
                    addFormNotice(_t("Enabling UPnP"));
                else
                    addFormNotice(_t("Disabling UPnP"));
                addFormNotice(_t("Restart required to take effect"));
            }
            changes.put(TransportManager.PROP_ENABLE_UPNP, "" + _upnp);

            if (Boolean.parseBoolean(_context.getProperty(UDPTransport.PROP_LAPTOP_MODE)) !=
                _laptop) {
                // This is minor, don't set restartRequired
                if (_laptop)
                    addFormNotice(_t("Enabling laptop mode"));
                else
                    addFormNotice(_t("Disabling laptop mode"));
            }
            changes.put(UDPTransport.PROP_LAPTOP_MODE, "" + _laptop);

            if (Boolean.parseBoolean(_context.getProperty(TransportUtil.PROP_IPV4_FIREWALLED)) !=
                _ipv4Firewalled) {
                if (_ipv4Firewalled)
                    addFormNotice(_t("Disabling inbound IPv4"));
                else
                    addFormNotice(_t("Enabling inbound IPv4"));
                restartRequired = true;
            }
            changes.put(TransportUtil.PROP_IPV4_FIREWALLED, "" + _ipv4Firewalled);

            if (Boolean.parseBoolean(_context.getProperty(TransportUtil.PROP_IPV6_FIREWALLED)) !=
                _ipv6Firewalled) {
                if (_ipv6Firewalled)
                    addFormNotice(_t("Disabling inbound IPv6"));
                else
                    addFormNotice(_t("Enabling inbound IPv6"));
                restartRequired = true;
            }
            changes.put(TransportUtil.PROP_IPV6_FIREWALLED, "" + _ipv6Firewalled);

            if (_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP) !=
                !_udpDisabled) {
                if (_udpDisabled)
                    addFormNotice(_t("Disabling UDP"));
                else
                    addFormNotice(_t("Enabling UDP"));
                restartRequired = true;
            }
            changes.put(TransportManager.PROP_ENABLE_UDP, "" + (!_udpDisabled));

            if (_requireIntroductions) {
                changes.put(UDPTransport.PROP_FORCE_INTRODUCERS, "true");
                addFormNotice(_t("Requiring SSU introducers"));
            } else {
                removes.add(UDPTransport.PROP_FORCE_INTRODUCERS);
            }

            // Hidden in the GUI
            //LoadTestManager.setEnableLoadTesting(_context, _enableLoadTesting);
        }
        
        boolean saved = _context.router().saveConfig(changes, removes);
        if (saved) 
            addFormNotice(_t("Configuration saved successfully"));
        else
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));

        // this has to be after the save
        if (ratesUpdated)
            _context.bandwidthLimiter().reinitialize();
        
        if (saved && !error) {
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
                //addFormError(_t("Gracefully restarting I2P to change published router address"));
                //_context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
              //}
            }
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
        byte[] iab = Addresses.getIP(addr);
        if (iab == null) {
            addFormError(_t("Invalid address") + ": " + addr);
            return false;
        }
        // TODO set IPv6 arg based on configuration?
        boolean rv = TransportUtil.isPubliclyRoutable(iab, true);
        if (!rv)
            addFormError(_t("The hostname or IP {0} is not publicly routable", addr));
        return rv;
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
                addFormNotice(_t("Updating bandwidth share percentage"));
                updated = true;
            }
        }

        // Since burst is now hidden in the gui, set burst to +10% for 20 seconds (prior to 0.9.33)
        // As of 0.9.33, we set strict bandwidth limits. Specified rate is the burst rate,
        // and we set the standard rate to 50KB or 10% lower (whichever is less).
        if ( (_inboundRate != null) && (_inboundRate.length() > 0) &&
            !_inboundRate.equals(_context.getProperty(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH,
                                                      Integer.toString(FIFOBandwidthRefiller.DEFAULT_INBOUND_BURST_BANDWIDTH)))) {
            try {
                float rate = Integer.parseInt(_inboundRate) / 1.024f;
                float kb = DEF_BURST_TIME * rate;
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH, Integer.toString(Math.round(rate)));
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH_PEAK, Integer.toString(Math.round(kb)));
                rate -= Math.min(rate * DEF_BURST_PCT / 100, 50);
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, Integer.toString(Math.round(rate)));
	        bwUpdated = true;
            } catch (NumberFormatException nfe) {
                addFormError(_t("Invalid bandwidth"));
            }
        }
        if ( (_outboundRate != null) && (_outboundRate.length() > 0) &&
            !_outboundRate.equals(_context.getProperty(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH,
                                                       Integer.toString(FIFOBandwidthRefiller.DEFAULT_OUTBOUND_BURST_BANDWIDTH)))) {
            try {
                float rate = Integer.parseInt(_outboundRate) / 1.024f;
                float kb = DEF_BURST_TIME * rate;
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH, Integer.toString(Math.round(rate)));
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH_PEAK, Integer.toString(Math.round(kb)));
                rate -= Math.min(rate * DEF_BURST_PCT / 100, 50);
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, Integer.toString(Math.round(rate)));
	        bwUpdated = true;
            } catch (NumberFormatException nfe) {
                addFormError(_t("Invalid bandwidth"));
            }
        }

        if (bwUpdated) {
            addFormNotice(_t("Updated bandwidth limits"));
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
