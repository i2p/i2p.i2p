package net.i2p.desktopgui.router.configuration;

import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.desktopgui.router.RouterHelper;

/**
 *
 * @author mathias
 */
public class SpeedHelper {
    public static final String USERTYPE_BROWSING = "Browsing";
    public static final String USERTYPE_DOWNLOADING = "Downloading";
    
    public static int calculateSpeed(String capable, String profile) {
        int capableSpeed = Integer.parseInt(capable);
        int advisedSpeed = capableSpeed;
        if(capableSpeed > 1000) {
            if(profile.equals(USERTYPE_BROWSING)) //Don't overdo usage for people just wanting to browse (we don't want to drive them away due to resource hogging)
                advisedSpeed *= 0.6;
            else if(profile.equals(USERTYPE_DOWNLOADING))
                advisedSpeed *= 0.8;
        }
        else
            advisedSpeed *= 0.6; //Lower available bandwidth: don't hog all the bandwidth
        return advisedSpeed;
    }
    
    public static int calculateMonthlyUsage(int kbytes) {
        return (int) ((((long)kbytes)*3600*24*31)/1000000);
    }
    
    public static int calculateSpeed(int gigabytes) {
        return (int) (((long)gigabytes)*1000000/31/24/3600);
    }
    
    public static String getInboundBandwidth() {
        return RouterHelper.getContext().router().getConfigSetting(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH);
    }

    public static String getOutboundBandwidth() {
        return RouterHelper.getContext().router().getConfigSetting(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH);
    }
}
