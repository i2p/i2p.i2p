package net.i2p.desktopgui.router.configuration;

import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.desktopgui.router.RouterHelper;

/**
 *
 * @author mathias
 */
public class SpeedHandler {

    public static void setInboundBandwidth(int kbytes) {
        context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, "" + kbytes);
        context.router().saveConfig();
    }
    
    public static void setOutboundBandwidth(int kbytes) {
        context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, "" + kbytes);
        context.router().saveConfig();
    }
    
    public static void setInboundBurstBandwidth(int kbytes) {
        context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH, "" + kbytes);
        context.router().saveConfig();
    }
    
    public static void setOutboundBurstBandwidth(int kbytes) {
        context.router().setConfigSetting(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH, "" + kbytes);
        context.router().saveConfig();
    }
    
    private static final RouterContext context = RouterHelper.getContext();
}
