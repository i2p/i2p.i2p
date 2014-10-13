package net.i2p.i2ptunnel.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.client.I2PClient;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.i2ptunnel.I2PTunnelHTTPServer;
import net.i2p.i2ptunnel.I2PTunnelIRCClient;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
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
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getTargetHost() != null)
            return DataHelper.escapeHTML(tun.getTargetHost());
        else
            return "127.0.0.1";
    }

    public String getTargetPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getTargetPort() != null)
            return DataHelper.escapeHTML(tun.getTargetPort());
        else
            return "";
    }

    public String getSpoofedHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getSpoofedHost() != null)
            return DataHelper.escapeHTML(tun.getSpoofedHost());
        else
            return "";
    }

    public String getPrivateKeyFile(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getPrivKeyFile() != null)
            return tun.getPrivKeyFile();
        if (tunnel < 0)
            tunnel = _group == null ? 999 : _group.getControllers().size();
        return "i2ptunnel" + tunnel + "-privKeys.dat";
    }
    
    public String getNameSignature(int tunnel) {
        String spoof = getSpoofedHost(tunnel);
        if (spoof.length() <= 0)
            return "";
        TunnelController tun = getController(tunnel);
        if (tun == null)
            return "";
        String keyFile = tun.getPrivKeyFile();
        if (keyFile != null && keyFile.trim().length() > 0) {
            PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
            try {
                Destination d = pkf.getDestination();
                if (d == null)
                    return "";
                SigningPrivateKey privKey = pkf.getSigningPrivKey();
                if (privKey == null)
                    return "";
                //System.err.println("Signing " + spoof + " with " + Base64.encode(privKey.getData()));
                Signature sig = _context.dsa().sign(spoof.getBytes("UTF-8"), privKey);
                return Base64.encode(sig.getData());
            } catch (Exception e) {}
        }
        return "";
    }
    
    public boolean startAutomatically(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getStartOnLoad();
        else
            return false;
    }
    
    public boolean isSharedClient(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return Boolean.parseBoolean(tun.getSharedClient());
        else
            return false;
    }
    
    public boolean shouldDelay(int tunnel) {
        return getProperty(tunnel, "i2p.streaming.connectDelay", 0) > 0;
    }
    
    public boolean isInteractive(int tunnel) {
        return getProperty(tunnel, "i2p.streaming.maxWindowSize", 128) == 16;
    }
    
    public int getTunnelDepth(int tunnel, int defaultLength) {
        return getProperty(tunnel, "inbound.length", defaultLength);
    }
    
    public int getTunnelQuantity(int tunnel, int defaultQuantity) {
        return getProperty(tunnel, "inbound.quantity", defaultQuantity);
    }
   
    public int getTunnelBackupQuantity(int tunnel, int defaultBackupQuantity) {
        return getProperty(tunnel, "inbound.backupQuantity", defaultBackupQuantity);
    }
  
    public int getTunnelVariance(int tunnel, int defaultVariance) {
        return getProperty(tunnel, "inbound.lengthVariance", defaultVariance);
    }
    
    public boolean getReduce(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.reduceOnIdle");
    }
    
    public int getReduceCount(int tunnel) {
        return getProperty(tunnel, "i2cp.reduceQuantity", 1);
    }
    
    public int getReduceTime(int tunnel) {
        return getProperty(tunnel, "i2cp.reduceIdleTime", 20*60*1000) / (60*1000);
    }
    
    public int getCert(int tunnel) {
        return 0;
    }
    
    public int getEffort(int tunnel) {
        return 23;
    }
    
    public String getSigner(int tunnel) {
        return "";
    }
    
    public boolean getEncrypt(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.encryptLeaseSet");
    }
    
    /**
     *  @param newTunnelType used if tunnel < 0
     *  @since 0.9.12
     */
    public int getSigType(int tunnel, String newTunnelType) {
        SigType type;
        String ttype;
        boolean isShared;
        if (tunnel >= 0) {
            String stype = getProperty(tunnel, I2PClient.PROP_SIGTYPE, null);
            type = stype != null ? SigType.parseSigType(stype) : null;
            ttype = getTunnelType(tunnel);
            isShared = isSharedClient(tunnel);
        } else {
            type = null;
            ttype = newTunnelType;
            isShared = false;
        }
        if (type == null) {
            // same default logic as in TunnelController.setConfig()
            if ((TunnelController.TYPE_IRC_CLIENT.equals(ttype) ||
                 TunnelController.TYPE_SOCKS_IRC.equals(ttype) ||
                 TunnelController.TYPE_STD_CLIENT.equals(ttype)) &&
                !isShared &&
                SigType.ECDSA_SHA256_P256.isAvailable())
                type = SigType.ECDSA_SHA256_P256;
            else
                type = SigType.DSA_SHA1;
        }
        return type.getCode();
    }
    
    /** @since 0.9.12 */
    public boolean isSigTypeAvailable(int code) {
        return SigType.isAvailable(code);
    }
    
    /** @since 0.8.9 */
    public boolean getDCC(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelIRCClient.PROP_DCC);
    }

    public String getEncryptKey(int tunnel) {
        return getProperty(tunnel, "i2cp.leaseSetKey", "");
    }
    
    public String getAccessMode(int tunnel) {
        if (getBooleanProperty(tunnel, PROP_ENABLE_ACCESS_LIST))
            return "1";
        if (getBooleanProperty(tunnel, PROP_ENABLE_BLACKLIST))
            return "2";
        return "0";
    }
    
    public String getAccessList(int tunnel) {
        return getProperty(tunnel, "i2cp.accessList", "").replace(",", "\n");
    }
    
    public String getJumpList(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClient.PROP_JUMP_SERVERS,
                           I2PTunnelHTTPClient.DEFAULT_JUMP_SERVERS).replace(",", "\n");
    }
    
    public boolean getClose(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.closeOnIdle");
    }
    
    public int getCloseTime(int tunnel) {
        return getProperty(tunnel, "i2cp.closeIdleTime", 30*60*1000) / (60*1000);
    }
    
    public boolean getNewDest(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.newDestOnResume") &&
               getBooleanProperty(tunnel, "i2cp.closeOnIdle") &&
               !getBooleanProperty(tunnel, "persistentClientKey");
    }
    
    public boolean getPersistentClientKey(int tunnel) {
        return getBooleanProperty(tunnel, "persistentClientKey");
    }
    
    public boolean getDelayOpen(int tunnel) {
        return getBooleanProperty(tunnel, "i2cp.delayOpen");
    }

    /** @since 0.9.14 */
    public boolean getAllowUserAgent(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_USER_AGENT);
    }

    /** @since 0.9.14 */
    public boolean getAllowReferer(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_REFERER);
    }

    /** @since 0.9.14 */
    public boolean getAllowAccept(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_ACCEPT);
    }

    /** @since 0.9.14 */
    public boolean getAllowInternalSSL(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClient.PROP_INTERNAL_SSL);
    }

    /** all proxy auth @since 0.8.2 */
    public boolean getProxyAuth(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_AUTH, "false") != "false";
    }
    
    public boolean getOutproxyAuth(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH) &&
               getOutproxyUsername(tunnel).length() > 0 &&
               getOutproxyPassword(tunnel).length() > 0;
    }
    
    public String getOutproxyUsername(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER, "");
    }
    
    public String getOutproxyPassword(int tunnel) {
        if (getOutproxyUsername(tunnel).length() <= 0)
            return "";
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW, "");
    }

    /** @since 0.9.11 */
    public String getSslProxies(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClient.PROP_SSL_OUTPROXIES, "");
    }
    
    /**
     *  Default true
     *  @since 0.9.11
     */
    public boolean getUseOutproxyPlugin(int tunnel) {
        return Boolean.parseBoolean(getProperty(tunnel, I2PTunnelHTTPClient.PROP_USE_OUTPROXY_PLUGIN, "true"));
    }

    /** all of these are @since 0.8.3 */
    public int getLimitMinute(int tunnel) {
        return getProperty(tunnel, PROP_MAX_CONNS_MIN, 0);
    }

    public int getLimitHour(int tunnel) {
        return getProperty(tunnel, PROP_MAX_CONNS_HOUR, 0);
    }

    public int getLimitDay(int tunnel) {
        return getProperty(tunnel, PROP_MAX_CONNS_DAY, 0);
    }

    public int getTotalMinute(int tunnel) {
        return getProperty(tunnel, PROP_MAX_TOTAL_CONNS_MIN, 0);
    }

    public int getTotalHour(int tunnel) {
        return getProperty(tunnel, PROP_MAX_TOTAL_CONNS_HOUR, 0);
    }

    public int getTotalDay(int tunnel) {
        return getProperty(tunnel, PROP_MAX_TOTAL_CONNS_DAY, 0);
    }

    public int getMaxStreams(int tunnel) {
        return getProperty(tunnel, PROP_MAX_STREAMS, 0);
    }

    /**
     * POST limits
     * @since 0.9.9
     */
    public int getPostMax(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_MAX, 0);
    }

    public int getPostTotalMax(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_TOTAL_MAX, 0);
    }

    public int getPostCheckTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_WINDOW, I2PTunnelHTTPServer.DEFAULT_POST_WINDOW) / 60;
    }

    public int getPostBanTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_BAN_TIME, I2PTunnelHTTPServer.DEFAULT_POST_BAN_TIME) / 60;
    }

    public int getPostTotalBanTime(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPServer.OPT_POST_TOTAL_BAN_TIME, I2PTunnelHTTPServer.DEFAULT_POST_TOTAL_BAN_TIME) / 60;
    }
    
    /** @since 0.9.13 */
    public boolean getUniqueLocal(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelServer.PROP_UNIQUE_LOCAL);
    }

    private int getProperty(int tunnel, String prop, int def) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String s = opts.getProperty(prop);
                if (s == null) return def;
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException nfe) {}
            }
        }
        return def;
    }
    
    private String getProperty(int tunnel, String prop, String def) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String rv = opts.getProperty(prop);
                if (rv != null)
                    return DataHelper.escapeHTML(rv);
            }
        }
        return def;
    }
    
    /** default is false */
    private boolean getBooleanProperty(int tunnel, String prop) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null)
                return Boolean.parseBoolean(opts.getProperty(prop));
        }
        return false;
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
            return _("internal");
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getI2CPHost();
        else
            return "127.0.0.1";
    }
    
    public String getI2CPPort(int tunnel) {
        if (_context.isRouterContext())
            return _("internal");
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getI2CPPort();
        else
            return "7654";
    }

    public String getCustomOptions(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts == null) return "";
            boolean isMD5Proxy = TunnelController.TYPE_HTTP_CLIENT.equals(tun.getType()) ||
                                 TunnelController.TYPE_CONNECT.equals(tun.getType());
            Map<String, String> sorted = new TreeMap<String, String>();
            for (Map.Entry<Object, Object> e : opts.entrySet()) {
                String key = (String)e.getKey();
                if (_noShowSet.contains(key))
                    continue;
                // leave in for HTTP and Connect so it can get migrated to MD5
                // hide for SOCKS until migrated to MD5
                if ((!isMD5Proxy) &&
                    _nonProxyNoShowSet.contains(key))
                    continue;
                sorted.put(key, (String)e.getValue());
            }
            if (sorted.isEmpty())
                return "";
            StringBuilder buf = new StringBuilder(64);
            boolean space = false;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (space)
                    buf.append(' ');
                else
                    space = true;
                buf.append(e.getKey()).append('=').append(e.getValue());
            }
            return DataHelper.escapeHTML(buf.toString());
        } else {
            return "";
        }
    }

    /**
     * Retrieve the client options from the tunnel
     *
     * @return map of name=val to be used as I2P session options
     */
    private static Properties getOptions(TunnelController controller) {
        if (controller == null) return null;
        return controller.getClientOptionProps();
    }

    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final int DFLT_QUANTITY = 2;
    private static final int MAX_CLIENT_QUANTITY = 3;
    private static final int MAX_SERVER_QUANTITY = 6;
    private static final int MAX_ADVANCED_QUANTITY = 16;

    /**
     *  @since 0.9.7
     */
    public String getQuantityOptions(int tunnel) {
        int tunnelQuantity = getTunnelQuantity(tunnel, DFLT_QUANTITY);
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
             buf.append(ngettext("{0} inbound, {0} outbound tunnel", "{0} inbound, {0} outbound tunnels", i));
             if (i <= 3) {
                 buf.append(" (");
                 if (i == 1)
                     buf.append(_("lower bandwidth and reliability"));
                 else if (i == 2)
                     buf.append(_("standard bandwidth and reliability"));
                 else if (i == 3)
                     buf.append(_("higher bandwidth and reliability"));
                 buf.append(')');
             }
             buf.append("</option>\n");
        }
        return buf.toString();
    }
}
