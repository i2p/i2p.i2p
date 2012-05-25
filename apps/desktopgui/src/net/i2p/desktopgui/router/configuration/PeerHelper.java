package net.i2p.desktopgui.router.configuration;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import net.i2p.data.RouterAddress;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.ntcp.NTCPAddress;
import net.i2p.desktopgui.router.RouterHelper;

/**
 * Part of the code imported and adapted from the I2P Router Console (which is licensed as public domain)
 */
public class PeerHelper {
    public static String getReachability() {
        RouterContext context = RouterHelper.getContext();
        if (context.router().getUptime() > 60*1000
                && (!context.router().gracefulShutdownInProgress())
                && !context.clientManager().isAlive())
            return "ERROR: Client Manager I2CP Error - check logs";  // not a router problem but the user should know
        if (!context.clock().getUpdatedSuccessfully())
            return "ERROR: ClockSkew";
        if (context.router().isHidden())
            return "Hidden";

        int status = context.commSystem().getReachabilityStatus();
        switch (status) {
            case CommSystemFacade.STATUS_OK:
                RouterAddress ra = context.router().getRouterInfo().getTargetAddress("NTCP");
                if (ra == null || (new NTCPAddress(ra)).isPubliclyRoutable())
                    return "OK";
                return "ERROR: Private TCP Address";
            case CommSystemFacade.STATUS_DIFFERENT:
                return "ERROR: You are behind a symmetric NAT.";
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
                if (context.router().getRouterInfo().getTargetAddress("NTCP") != null)
                    return "WARNING: You are behind a firewall and have Inbound TCP Enabled";
                if (((FloodfillNetworkDatabaseFacade)context.netDb()).floodfillEnabled())
                    return "WARNING: You are behind a firewall and are a floodfill router";
                if (context.router().getRouterInfo().getCapabilities().indexOf('O') >= 0)
                    return "WARNING: You are behind a firewall and are a fast router";
                return "Firewalled";
            case CommSystemFacade.STATUS_HOSED:
                return "ERROR: The UDP port is already in use. Set i2np.udp.internalPort=xxxx to a different value in the advanced config and restart";
            case CommSystemFacade.STATUS_UNKNOWN: // fallthrough
            default:
                ra = context.router().getRouterInfo().getTargetAddress("SSU");
                if (ra == null && context.router().getUptime() > 5*60*1000) {
                    if (context.getProperty(PROP_I2NP_NTCP_HOSTNAME) == null ||
                        context.getProperty(PROP_I2NP_NTCP_PORT) == null)
                        return "ERROR: UDP is disabled and the inbound TCP host/port combination is not set";
                    else
                        return "WARNING: You are behind a firewall and have UDP Disabled";
                }
                return "Testing";
        }
    }
    
    /**
     * How many peers we are talking to now
     *
     */
    public static int getActivePeers() {
        RouterContext context = RouterHelper.getContext();
        if (context == null) 
            return 0;
        else
            return context.commSystem().countActivePeers();
    }
    
    public static void addActivePeerListener(ActionListener listener) {
        synchronized(activePeerListeners) {
            activePeerListeners.add(listener);
            if(activePeerTimer == null) {
                activePeerTimer = new Timer();
                TimerTask t = new TimerTask() {
                    private int activePeers = 0;

                    @Override
                    public void run() {
                        int newActivePeers = getActivePeers();
                        if(!(activePeers == newActivePeers)) {
                            synchronized(activePeerListeners) {
                                for(int i=0; i<activePeerListeners.size(); i++) {
                                    activePeerListeners.get(i).actionPerformed(new ActionEvent(this, 0, ""));
                                }
                            }
                            activePeers = newActivePeers;
                        }
                    }
                };
                activePeerTimer.schedule(t, 0, 60*1000);
            }
        }
    }
    
    public static void removeActivePeerListener(ActionListener listener) {
        synchronized(activePeerListeners) {
            activePeerListeners.remove(listener);
            if(activePeerListeners.size() == 0) {
                activePeerTimer.cancel();
                activePeerTimer = null;
            }
        }
    }
    
    
    
    public static void addReachabilityListener(ActionListener listener) {
        synchronized(reachabilityListeners) {
            reachabilityListeners.add(listener);
            if(reachabilityTimer == null) {
                reachabilityTimer = new Timer();
                TimerTask t = new TimerTask() {
                    
                    private String reachability = "";

                    @Override
                    public void run() {
                        String newReachability = getReachability();
                        if(!reachability.equals(newReachability)) {
                            synchronized(reachabilityListeners) {
                                for(int i=0; i<reachabilityListeners.size(); i++) {
                                    reachabilityListeners.get(i).actionPerformed(new ActionEvent(this, 0, ""));
                                }
                            }
                            reachability = newReachability;
                        }
                    }
                    
                };
                reachabilityTimer.schedule(t, 0, 60*1000);
            }
        }
    }
    
    public static void removeReachabilityListener(ActionListener listener) {
        synchronized(reachabilityListeners) {
            reachabilityListeners.remove(listener);
            if(reachabilityListeners.size() == 0) {
                reachabilityTimer.cancel();
                reachabilityTimer = null;
            }
        }
    }
    
    private static List<ActionListener> reachabilityListeners = new ArrayList<ActionListener>();
    private static Timer reachabilityTimer = null;
    
    private static List<ActionListener> activePeerListeners = new ArrayList<ActionListener>();
    private static Timer activePeerTimer = null;
    
    /** copied from various private components */
    public final static String PROP_I2NP_UDP_PORT = "i2np.udp.port";
    public final static String PROP_I2NP_INTERNAL_UDP_PORT = "i2np.udp.internalPort";
    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
    public final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";
}
