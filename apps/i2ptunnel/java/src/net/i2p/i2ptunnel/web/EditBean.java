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

import net.i2p.I2PAppContext;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.util.Log;

/**
 * Ugly little accessor for the edit page
 */
public class EditBean extends IndexBean {
    public EditBean() { super(); }
    
    public static boolean staticIsClient(int tunnel) {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        List controllers = group.getControllers();
        if (controllers.size() > tunnel) {
            TunnelController cur = (TunnelController)controllers.get(tunnel);
            if (cur == null) return false;
            return ( ("client".equals(cur.getType())) || 
            		 ("httpclient".equals(cur.getType()))||
            		 ("ircclient".equals(cur.getType())));
        } else {
            return false;
        }
    }
    
    public String getTargetHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getTargetHost();
        else
            return "";
    }
    public String getTargetPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getTargetPort();
        else
            return "";
    }
    public String getSpoofedHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getSpoofedHost();
        else
            return "";
    }
    public String getPrivateKeyFile(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getPrivKeyFile();
        else
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
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String delay = opts.getProperty("i2p.streaming.connectDelay");
                if ( (delay == null) || ("0".equals(delay)) )
                    return false;
                else
                    return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }
    
    public boolean isInteractive(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String wsiz = opts.getProperty("i2p.streaming.maxWindowSize");
                if ( (wsiz == null) || (!"1".equals(wsiz)) )
                    return false;
                else
                    return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }
    
    public int getTunnelDepth(int tunnel, int defaultLength) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String len = opts.getProperty("inbound.length");
                if (len == null) return defaultLength;
                try {
                    return Integer.parseInt(len);
                } catch (NumberFormatException nfe) {
                    return defaultLength;
                }
            } else {
                return defaultLength;
            }
        } else {
            return defaultLength;
        }
    }
    
    public int getTunnelQuantity(int tunnel, int defaultQuantity) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String len = opts.getProperty("inbound.quantity");
                if (len == null) return defaultQuantity;
                try {
                    return Integer.parseInt(len);
                } catch (NumberFormatException nfe) {
                    return defaultQuantity;
                }
            } else {
                return defaultQuantity;
            }
        } else {
            return defaultQuantity;
        }
    }
   
    public int getTunnelBackupQuantity(int tunnel, int defaultBackupQuantity) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String len = opts.getProperty("inbound.backupQuantity");
                if (len == null) return defaultBackupQuantity;
                try {
                    return Integer.parseInt(len);
                } catch (NumberFormatException nfe) {
                    return defaultBackupQuantity;
                }
            } else {
                return defaultBackupQuantity;
            }
        } else {
            return defaultBackupQuantity;
        }
    }
  
    public int getTunnelVariance(int tunnel, int defaultVariance) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String len = opts.getProperty("inbound.lengthVariance");
                if (len == null) return defaultVariance;
                try {
                    return Integer.parseInt(len);
                } catch (NumberFormatException nfe) {
                    return defaultVariance;
                }
            } else {
                return defaultVariance;
            }
        } else {
            return defaultVariance;
        }
    }
    
    public String getI2CPHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getI2CPHost();
        else
            return "localhost";
    }
    
    public String getI2CPPort(int tunnel) {
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
            StringBuffer buf = new StringBuffer(64);
            int i = 0;
            for (Iterator iter = opts.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = opts.getProperty(key);
                if ("inbound.length".equals(key)) continue;
                if ("outbound.length".equals(key)) continue;
                if ("inbound.lengthVariance".equals(key)) continue;
                if ("outbound.lengthVariance".equals(key)) continue;
                if ("inbound.backupQuantity".equals(key)) continue;
                if ("outbound.backupQuantity".equals(key)) continue;
                if ("inbound.quantity".equals(key)) continue;
                if ("outbound.quantity".equals(key)) continue;
                if ("inbound.nickname".equals(key)) continue;
                if ("outbound.nickname".equals(key)) continue;
                if ("i2p.streaming.connectDelay".equals(key)) continue;
                if ("i2p.streaming.maxWindowSize".equals(key)) continue;
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