package net.i2p.i2ptunnel.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;

/**
 * Ugly little accessor for the edit page
 *
 * Warning - This class is not part of the i2ptunnel API, and at some point
 * it will be moved from the jar to the war.
 * Usage by classes outside of i2ptunnel.war is deprecated.
 */
public class EditBean extends IndexBean {
    public EditBean() { super(); }
    
    public static boolean staticIsClient(int tunnel) {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        List controllers = group.getControllers();
        if (controllers.size() > tunnel) {
            TunnelController cur = (TunnelController)controllers.get(tunnel);
            if (cur == null) return false;
            return isClient(cur.getType());
        } else {
            return false;
        }
    }
    
    public String getTargetHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getTargetHost() != null)
            return tun.getTargetHost();
        else
            return "127.0.0.1";
    }
    public String getTargetPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getTargetPort() != null)
            return tun.getTargetPort();
        else
            return "";
    }
    public String getSpoofedHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getSpoofedHost() != null)
            return tun.getSpoofedHost();
        else
            return "";
    }
    public String getPrivateKeyFile(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getPrivKeyFile() != null)
            return tun.getPrivKeyFile();
        if (tunnel < 0)
            tunnel = _group.getControllers().size();
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
            return "true".equalsIgnoreCase(tun.getSharedClient());
        else
            return true;
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
    
    /** all proxy auth @since 0.8.2 */
    public boolean getProxyAuth(int tunnel) {
        return getBooleanProperty(tunnel, I2PTunnelHTTPClientBase.PROP_AUTH) &&
               getProxyUsername(tunnel).length() > 0 &&
               getProxyPassword(tunnel).length() > 0;
    }
    
    public String getProxyUsername(int tunnel) {
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_USER, "");
    }
    
    public String getProxyPassword(int tunnel) {
        if (getProxyUsername(tunnel).length() <= 0)
            return "";
        return getProperty(tunnel, I2PTunnelHTTPClientBase.PROP_PW, "");
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
    
    /** all of these are @since 0.8.3 */
    public String getLimitMinute(int tunnel) {
        return getProperty(tunnel, PROP_MAX_CONNS_MIN, "0");
    }

    public String getLimitHour(int tunnel) {
        return getProperty(tunnel, PROP_MAX_CONNS_HOUR, "0");
    }

    public String getLimitDay(int tunnel) {
        return getProperty(tunnel, PROP_MAX_CONNS_DAY, "0");
    }

    public String getTotalMinute(int tunnel) {
        return getProperty(tunnel, PROP_MAX_TOTAL_CONNS_MIN, "0");
    }

    public String getTotalHour(int tunnel) {
        return getProperty(tunnel, PROP_MAX_TOTAL_CONNS_HOUR, "0");
    }

    public String getTotalDay(int tunnel) {
        return getProperty(tunnel, PROP_MAX_TOTAL_CONNS_DAY, "0");
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
            if (opts != null)
                return opts.getProperty(prop, def);
        }
        return def;
    }
    
    /** default is false */
    private boolean getBooleanProperty(int tunnel, String prop) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null)
                return Boolean.valueOf(opts.getProperty(prop)).booleanValue();
        }
        return false;
    }
    
    /** @since 0.8.3 */
    public boolean isRouterContext() {
        return _context.isRouterContext();
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
            StringBuilder buf = new StringBuilder(64);
            int i = 0;
            for (Iterator iter = opts.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                if (_noShowSet.contains(key))
                    continue;
                String val = opts.getProperty(key);
                if (i != 0) buf.append(' ');
                buf.append(key).append('=').append(val);
                i++;
            }
            return buf.toString();
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
        String opts = controller.getClientOptions();
        StringTokenizer tok = new StringTokenizer(opts);
        Properties props = new Properties();
        while (tok.hasMoreTokens()) {
            String pair = tok.nextToken();
            int eq = pair.indexOf('=');
            if ( (eq <= 0) || (eq >= pair.length()) )
                continue;
            String key = pair.substring(0, eq);
            String val = pair.substring(eq+1);
            props.setProperty(key, val);
        }
        return props;
    }
}
