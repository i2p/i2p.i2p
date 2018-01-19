package net.i2p.i2ptunnel.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import net.i2p.I2PException;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.i2ptunnel.ui.GeneralHelper;
import net.i2p.util.Addresses;

/**
 * Ugly little accessor for the edit page
 *
 * Warning - This class is not part of the i2ptunnel API,
 * it has been moved from the jar to the war.
 * Usage by classes outside of i2ptunnel.war is deprecated.
 */
public class EditBean extends IndexBean {
    public EditBean() { super(); }
    
    /**
     *  Is it a client or server in the UI and I2P side?
     *  Note that a streamr client is a UI and I2P client but a server on the localhost side.
     *  Note that a streamr server is a UI and I2P server but a client on the localhost side.
     */
    public static boolean staticIsClient(int tunnel) {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return false;
        List<TunnelController> controllers = group.getControllers();
        if (controllers.size() > tunnel) {
            TunnelController cur = controllers.get(tunnel);
            if (cur == null) return false;
            return isClient(cur.getType());
        } else {
            return false;
        }
    }
    
    public String getTargetHost(int tunnel) {
        return DataHelper.escapeHTML(_helper.getTargetHost(tunnel));
    }

    public String getTargetPort(int tunnel) {
        int port = _helper.getTargetPort(tunnel);
        return port > 0 ? "" + port : "";
    }

    public String getPrivateKeyFile(int tunnel) {
        return _helper.getPrivateKeyFile(tunnel);
    }

    /**
     *  @return path or ""
     *  @since 0.9.30
     */
    public String getAltPrivateKeyFile(int tunnel) {
        return _helper.getAltPrivateKeyFile(tunnel);
    }
    
/****
    public String getNameSignature(int tunnel) {
        String spoof = getSpoofedHost(tunnel);
        if (spoof.length() <= 0)
            return "";
        TunnelController tun = getController(tunnel);
        if (tun == null)
            return "";
        String keyFile = tun.getPrivKeyFile();
        if (keyFile != null && keyFile.trim().length() > 0) {
            File f = new File(keyFile);
            if (!f.isAbsolute())
                f = new File(_context.getConfigDir(), keyFile);
            PrivateKeyFile pkf = new PrivateKeyFile(f);
            try {
                Destination d = pkf.getDestination();
                if (d == null)
                    return "";
                SigningPrivateKey privKey = pkf.getSigningPrivKey();
                if (privKey == null)
                    return "";
                Signature sig = _context.dsa().sign(spoof.getBytes("UTF-8"), privKey);
                if (sig == null)
                    return "";
                return Base64.encode(sig.getData());
            } catch (I2PException e) {
            } catch (IOException e) {}
        }
        return "";
    }
****/
    
    /**
     *  @since 0.9.26
     *  @return key or null
     */
    public SigningPrivateKey getSigningPrivateKey(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null)
            return null;
        String keyFile = tun.getPrivKeyFile();
        if (keyFile != null && keyFile.trim().length() > 0) {
            File f = new File(keyFile);
            if (!f.isAbsolute())
                f = new File(_context.getConfigDir(), keyFile);
            PrivateKeyFile pkf = new PrivateKeyFile(f);
            return pkf.getSigningPrivKey();
        }
        return null;
    }
    
    public boolean startAutomatically(int tunnel) {
        return _helper.shouldStartAutomatically(tunnel);
    }
    
    public boolean isSharedClient(int tunnel) {
        return _helper.isSharedClient(tunnel);
    }
    
    public boolean shouldDelay(int tunnel) {
        return _helper.shouldDelayConnect(tunnel);
    }
    
    public boolean isInteractive(int tunnel) {
        return _helper.isInteractive(tunnel);
    }
    
    /** in or both in/out */
    public int getTunnelDepth(int tunnel, int defaultLength) {
        return _helper.getTunnelDepth(tunnel, defaultLength);
    }
    
    /** in or both in/out */
    public int getTunnelQuantity(int tunnel, int defaultQuantity) {
        return _helper.getTunnelQuantity(tunnel, defaultQuantity);
    }
   
    /** in or both in/out */
    public int getTunnelBackupQuantity(int tunnel, int defaultBackupQuantity) {
        return _helper.getTunnelBackupQuantity(tunnel, defaultBackupQuantity);
    }
  
    /** in or both in/out */
    public int getTunnelVariance(int tunnel, int defaultVariance) {
        return _helper.getTunnelVariance(tunnel, defaultVariance);
    }
    
    /** @since 0.9.33 */
    public int getTunnelDepthOut(int tunnel, int defaultLength) {
        return _helper.getTunnelDepthOut(tunnel, defaultLength);
    }
    
    /** @since 0.9.33 */
    public int getTunnelQuantityOut(int tunnel, int defaultQuantity) {
        return _helper.getTunnelQuantityOut(tunnel, defaultQuantity);
    }
   
    /** @since 0.9.33 */
    public int getTunnelBackupQuantityOut(int tunnel, int defaultBackupQuantity) {
        return _helper.getTunnelBackupQuantityOut(tunnel, defaultBackupQuantity);
    }
  
    /** @since 0.9.33 */
    public int getTunnelVarianceOut(int tunnel, int defaultVariance) {
        return _helper.getTunnelVarianceOut(tunnel, defaultVariance);
    }
    
    public boolean getReduce(int tunnel) {
        return _helper.getReduceOnIdle(tunnel, false);
    }
    
    public int getReduceCount(int tunnel) {
        return _helper.getReduceCount(tunnel, 1);
    }
    
    public int getReduceTime(int tunnel) {
        return _helper.getReduceTime(tunnel, 20);
    }
    
    public int getCert(int tunnel) {
        return _helper.getCert(tunnel);
    }
    
    public int getEffort(int tunnel) {
        return _helper.getEffort(tunnel);
    }
    
    public String getSigner(int tunnel) {
        return _helper.getSigner(tunnel);
    }
    
    public boolean getEncrypt(int tunnel) {
        return _helper.getEncrypt(tunnel);
    }
    
    /**
     *  @param newTunnelType used if tunnel &lt; 0
     *  @since 0.9.12
     */
    public int getSigType(int tunnel, String newTunnelType) {
        return _helper.getSigType(tunnel, newTunnelType);
    }
    
    /** @since 0.9.12 */
    public boolean isSigTypeAvailable(int code) {
        return SigType.isAvailable(code);
    }

    /** @since 0.9.33 */
    public boolean canChangeSigType(int tunnel) {
        if (tunnel < 0)
            return true;
        if (getDestination(tunnel) != null)
            return false;
        return getTunnelStatus(tunnel) == GeneralHelper.NOT_RUNNING;
    }

    /**
     *  Random keys, hidden in forms
     *  @since 0.9.18
     */
    public String getKey1(int tunnel) {
        return _helper.getInboundRandomKey(tunnel);
    }

    public String getKey2(int tunnel) {
        return _helper.getOutboundRandomKey(tunnel);
    }

    public String getKey3(int tunnel) {
        return _helper.getLeaseSetSigningPrivateKey(tunnel);
    }

    public String getKey4(int tunnel) {
        return _helper.getLeaseSetPrivateKey(tunnel);
    }

    /** @since 0.8.9 */
    public boolean getDCC(int tunnel) {
        return _helper.getDCC(tunnel);
    }

    public String getEncryptKey(int tunnel) {
        return _helper.getEncryptKey(tunnel);
    }
    
    public String getAccessMode(int tunnel) {
        return Integer.toString(_helper.getAccessMode(tunnel));
    }
    
    public String getAccessList(int tunnel) {
        return _helper.getAccessList(tunnel);
    }
    
    public String getJumpList(int tunnel) {
        return _helper.getJumpList(tunnel);
    }
    
    public boolean getClose(int tunnel) {
        return _helper.getCloseOnIdle(tunnel, false);
    }
    
    public int getCloseTime(int tunnel) {
        return _helper.getCloseTime(tunnel, 30);
    }
    
    public boolean getNewDest(int tunnel) {
        return _helper.getNewDest(tunnel);
    }
    
    public boolean getPersistentClientKey(int tunnel) {
        return _helper.getPersistentClientKey(tunnel);
    }
    
    public boolean getDelayOpen(int tunnel) {
        return _helper.getDelayOpen(tunnel);
    }

    /** @since 0.9.14 */
    public boolean getAllowUserAgent(int tunnel) {
        return _helper.getAllowUserAgent(tunnel);
    }

    /** @since 0.9.14 */
    public boolean getAllowReferer(int tunnel) {
        return _helper.getAllowReferer(tunnel);
    }

    /** @since 0.9.14 */
    public boolean getAllowAccept(int tunnel) {
        return _helper.getAllowAccept(tunnel);
    }

    /** @since 0.9.14 */
    public boolean getAllowInternalSSL(int tunnel) {
        return _helper.getAllowInternalSSL(tunnel);
    }

    /** @since 0.9.18 */
    public boolean getMultihome(int tunnel) {
        return _helper.getMultihome(tunnel);
    }

    /** @since 0.9.25 */
    public String getUserAgents(int tunnel) {
        return _helper.getUserAgents(tunnel);
    }
    
    /** all proxy auth @since 0.8.2 */
    public boolean getProxyAuth(int tunnel) {
        return _helper.getProxyAuth(tunnel) != "false";
    }
    // TODO think
    public boolean getOutproxyAuth(int tunnel) {
        return _helper.getOutproxyAuth(tunnel) &&
               getOutproxyUsername(tunnel).length() > 0 &&
               getOutproxyPassword(tunnel).length() > 0;
    }
    
    public String getOutproxyUsername(int tunnel) {
        return _helper.getOutproxyUsername(tunnel);
    }
    
    public String getOutproxyPassword(int tunnel) {
        return _helper.getOutproxyPassword(tunnel);
    }

    /** @since 0.9.11 */
    public String getSslProxies(int tunnel) {
        return _helper.getSslProxies(tunnel);
    }
    
    /**
     *  Default true
     *  @since 0.9.11
     */
    public boolean getUseOutproxyPlugin(int tunnel) {
        return _helper.getUseOutproxyPlugin(tunnel);
    }

    /** all of these are @since 0.8.3 */
    public int getLimitMinute(int tunnel) {
        return _helper.getLimitMinute(tunnel);
    }

    public int getLimitHour(int tunnel) {
        return _helper.getLimitHour(tunnel);
    }

    public int getLimitDay(int tunnel) {
        return _helper.getLimitDay(tunnel);
    }

    public int getTotalMinute(int tunnel) {
        return _helper.getTotalMinute(tunnel);
    }

    public int getTotalHour(int tunnel) {
        return _helper.getTotalHour(tunnel);
    }

    public int getTotalDay(int tunnel) {
        return _helper.getTotalDay(tunnel);
    }

    public int getMaxStreams(int tunnel) {
        return _helper.getMaxStreams(tunnel);
    }

    /**
     * POST limits
     * @since 0.9.9
     */
    public int getPostMax(int tunnel) {
        return _helper.getPostMax(tunnel);
    }

    public int getPostTotalMax(int tunnel) {
        return _helper.getPostTotalMax(tunnel);
    }

    public int getPostCheckTime(int tunnel) {
        return _helper.getPostCheckTime(tunnel);
    }

    public int getPostBanTime(int tunnel) {
        return _helper.getPostBanTime(tunnel);
    }

    public int getPostTotalBanTime(int tunnel) {
        return _helper.getPostTotalBanTime(tunnel);
    }
    
    /** @since 0.9.13 */
    public boolean getUniqueLocal(int tunnel) {
        return _helper.getUniqueLocal(tunnel);
    }
    
    /** @since 0.8.3 */
    public boolean isRouterContext() {
        return _context.isRouterContext();
    }

    /** @since 0.8.3 */
    public Set<String> interfaceSet() {
        return Addresses.getAllAddresses();
    }

    /** @since 0.9.12 */
    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
    }

    public String getI2CPHost(int tunnel) {
        if (_context.isRouterContext())
            return _t("internal");
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getI2CPHost();
        else
            return "127.0.0.1";
    }
    
    public String getI2CPPort(int tunnel) {
        if (_context.isRouterContext())
            return _t("internal");
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getI2CPPort();
        else
            return "7654";
    }

    public String getCustomOptions(int tunnel) {
        return _helper.getCustomOptionsString(tunnel);
    }

    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final int DFLT_QUANTITY = 2;
    private static final int MAX_CLIENT_QUANTITY = 3;
    private static final int MAX_SERVER_QUANTITY = 6;
    private static final int MAX_ADVANCED_QUANTITY = 16;

    /**
     *  @param mode 0=both, 1=in, 2=out
     *  @since 0.9.7
     */
    public String getQuantityOptions(int tunnel, int mode) {
        int tunnelQuantity = mode == 2 ? getTunnelQuantityOut(tunnel, DFLT_QUANTITY)
                                       : getTunnelQuantity(tunnel, DFLT_QUANTITY);
        boolean advanced = _context.getBooleanProperty(PROP_ADVANCED);
        int maxQuantity = advanced ? MAX_ADVANCED_QUANTITY :
                                     (isClient(tunnel) ? MAX_CLIENT_QUANTITY : MAX_SERVER_QUANTITY);
        if (tunnelQuantity > maxQuantity)
            maxQuantity = tunnelQuantity;
        StringBuilder buf = new StringBuilder(256);
        for (int i = 1; i <= maxQuantity; i++) {
             buf.append("<option value=\"").append(i).append('"');
             if (i == tunnelQuantity)
                 buf.append(" selected=\"selected\"");
             buf.append('>');
             if (mode == 1)
                 buf.append(ngettext("{0} inbound tunnel", "{0} inbound tunnels", i));
             else if (mode == 2)
                 buf.append(ngettext("{0} outbound tunnel", "{0} outbound tunnels", i));
             else
                 buf.append(ngettext("{0} inbound, {0} outbound tunnel", "{0} inbound, {0} outbound tunnels", i));
             if (i <= 3) {
                 buf.append(" (");
                 if (i == 1)
                     buf.append(_t("lower bandwidth and reliability"));
                 else if (i == 2)
                     buf.append(_t("standard bandwidth and reliability"));
                 else if (i == 3)
                     buf.append(_t("higher bandwidth and reliability"));
                 buf.append(')');
             }
             buf.append("</option>\n");
        }
        return buf.toString();
    }
}
