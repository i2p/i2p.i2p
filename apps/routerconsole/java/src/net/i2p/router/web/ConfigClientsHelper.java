package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Iterator;
import java.util.TreeMap;

import net.i2p.util.Log;

import net.i2p.router.RouterContext;
import net.i2p.router.ClientTunnelSettings;

public class ConfigClientsHelper {
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** copied from the package private {@link net.i2p.router.tunnelmanager.TunnelPool} */
    public final static String TARGET_CLIENTS_PARAM = "router.targetClients";
    /** copied from the package private {@link net.i2p.router.tunnelmanager.TunnelPool} */
    public final static int TARGET_CLIENTS_DEFAULT = 3;

    public ConfigClientsHelper() {}
    
    public String getClientCountSelectBox() {
        int count = TARGET_CLIENTS_DEFAULT;
        String val = _context.router().getConfigSetting(TARGET_CLIENTS_PARAM);
        if (val != null) {
            try {
                count = Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                // ignore, use default from above
            }
        }
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<select name=\"clientcount\">\n");
        for (int i = 0; i < 5; i++) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (count == i)
                buf.append("selected=\"true\" ");
            buf.append(">").append(i).append("</option>\n");
        }
        if (count >= 5) {
            buf.append("<option value=\"").append(count);
            buf.append("\" selected>").append(count);
            buf.append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
    
    public String getTunnelCountSelectBox() {
        int count = ClientTunnelSettings.DEFAULT_NUM_INBOUND;
        String val = _context.router().getConfigSetting(ClientTunnelSettings.PROP_NUM_INBOUND);
        if (val != null) {
            try {
                count = Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                // ignore, use default from above
            }
        }
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<select name=\"tunnelcount\">\n");
        for (int i = 0; i < 4; i++) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (count == i)
                buf.append("selected=\"true\" ");
            buf.append(">").append(i).append("</option>\n");
        }
        if (count >= 4) {
            buf.append("<option value=\"").append(count);
            buf.append("\" selected>").append(count);
            buf.append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
    
    public String getTunnelDepthSelectBox() {
        int count = ClientTunnelSettings.DEFAULT_DEPTH_INBOUND;
        String val = _context.router().getConfigSetting(ClientTunnelSettings.PROP_DEPTH_INBOUND);
        if (val != null) {
            try {
                count = Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                // ignore, use default from above
            }
        }
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<select name=\"tunneldepth\">\n");
        for (int i = 0; i < 4; i++) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (count == i)
                buf.append("selected=\"true\" ");
            buf.append(">").append(i).append("</option>\n");
        }
        if (count >= 4) {
            buf.append("<option value=\"").append(count);
            buf.append("\" selected>").append(count);
            buf.append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
}
