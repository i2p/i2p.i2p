package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Iterator;
import java.util.Set;
import java.util.Properties;
import java.util.TreeMap;

import net.i2p.util.Log;

import net.i2p.data.Destination;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;

public class ConfigTunnelsHelper {
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

    public ConfigTunnelsHelper() {}
    
    
    public String getForm() {
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<table border=\"1\">\n");
        TunnelPoolSettings exploratoryIn = _context.tunnelManager().getInboundSettings();
        TunnelPoolSettings exploratoryOut = _context.tunnelManager().getOutboundSettings();
        
        buf.append("<input type=\"hidden\" name=\"pool.0\" value=\"exploratory\" >");
        renderForm(buf, 0, "exploratory", "Exploratory tunnels", exploratoryIn, exploratoryOut);
        
        int cur = 1;
        Set clients = _context.clientManager().listClients();
        for (Iterator iter = clients.iterator(); iter.hasNext(); ) {
            Destination dest = (Destination)iter.next();
            TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(dest.calculateHash());
            TunnelPoolSettings out = _context.tunnelManager().getOutboundSettings(dest.calculateHash());
            
            if ( (in == null) || (out == null) ) continue;
            
            String name = in.getDestinationNickname();
            if (name == null)
                name = out.getDestinationNickname();
            if (name == null)
                name = dest.calculateHash().toBase64().substring(0,6);
        
            String prefix = dest.calculateHash().toBase64().substring(0,4);
            buf.append("<input type=\"hidden\" name=\"pool.").append(cur).append("\" value=\"");
            buf.append(dest.calculateHash().toBase64()).append("\" >");    
            renderForm(buf, cur, prefix, "Client tunnels for " + name, in, out);
            cur++;
        }
        
        buf.append("</table>\n");
        return buf.toString();
    }

    private void renderForm(StringBuffer buf, int index, String prefix, String name, TunnelPoolSettings in, TunnelPoolSettings out) {

        buf.append("<tr><td colspan=\"3\"><b><a name=\"").append(prefix).append("\">");
        buf.append(name).append("</a></b></td></tr>\n");
        buf.append("<tr><td></td><td><b>Inbound</b></td><td><b>Outbound</b></td></tr>\n");
        
        // tunnel depth
        buf.append("<tr><td>Depth</td>\n");
        buf.append("<td><select name=\"").append(index).append(".depthInbound\">\n");
        buf.append("<option value=\"0\" ");
        if (in.getLength() <= 0) buf.append(" selected=\"true\" ");
        buf.append(">0 hops</option>\n");
        buf.append("<option value=\"1\" ");
        if (in.getLength() == 1) buf.append(" selected=\"true\" ");
        buf.append(">1 hop</option>\n");
        buf.append("<option value=\"2\" ");
        if (in.getLength() == 2) buf.append(" selected=\"true\" ");
        buf.append(">2 hops</option>\n");
        buf.append("<option value=\"3\" ");
        if (in.getLength() == 3) buf.append(" selected=\"true\" ");
        buf.append(">3 hops</option>\n");
        if (in.getLength() > 3)
            buf.append("<option value=\"").append(in.getLength()).append("\">").append(in.getLength()).append(" hops</option>\n");
        buf.append("</td>\n");

        buf.append("<td><select name=\"").append(index).append(".depthOutbound\">\n");
        buf.append("<option value=\"0\" ");
        if (out.getLength() <= 0) buf.append(" selected=\"true\" ");
        buf.append(">0 hops</option>\n");
        buf.append("<option value=\"1\" ");
        if (out.getLength() == 1) buf.append(" selected=\"true\" ");
        buf.append(">1 hop</option>\n");
        buf.append("<option value=\"2\" ");
        if (out.getLength() == 2) buf.append(" selected=\"true\" ");
        buf.append(">2 hops</option>\n");
        buf.append("<option value=\"3\" ");
        if (out.getLength() == 3) buf.append(" selected=\"true\" ");
        buf.append(">3 hops</option>\n");
        if (out.getLength() > 3)
            buf.append("<option value=\"").append(out.getLength()).append("\">").append(out.getLength()).append(" hops</option>\n");
        buf.append("</td>\n");
        buf.append("</tr>\n");

        // tunnel depth variance
        buf.append("<tr><td>Variance</td>\n");
        buf.append("<td><select name=\"").append(index).append(".varianceInbound\">\n");
        buf.append("<option value=\"0\" ");
        if (in.getLengthVariance() == 0) buf.append(" selected=\"true\" ");
        buf.append(">0 hops</option>\n");
        buf.append("<option value=\"-1\" ");
        if (in.getLengthVariance() == -1) buf.append(" selected=\"true\" ");
        buf.append(">+/- 0-1 hops</option>\n");
        buf.append("<option value=\"-2\" ");
        if (in.getLengthVariance() == -2) buf.append(" selected=\"true\" ");
        buf.append(">+/- 0-2 hops</option>\n");
        buf.append("<option value=\"1\" ");
        if (in.getLengthVariance() == 1) buf.append(" selected=\"true\" ");
        buf.append(">+ 0-1 hops</option>\n");
        buf.append("<option value=\"2\" ");
        if (in.getLengthVariance() == 2) buf.append(" selected=\"true\" ");
        buf.append(">+ 0-2 hops</option>\n");
        if (in.getLengthVariance() < -2)
            buf.append("<option value=\"").append(in.getLengthVariance()).append("\">+/- 0-").append(in.getLengthVariance()).append(" hops</option>\n");
        if (in.getLengthVariance() > 2)
            buf.append("<option value=\"").append(in.getLengthVariance()).append("\">+ 0-").append(in.getLengthVariance()).append(" hops</option>\n");
        buf.append("</td>\n");

        buf.append("<td><select name=\"").append(index).append(".varianceOutbound\">\n");
        buf.append("<option value=\"0\" ");
        if (out.getLengthVariance() == 0) buf.append(" selected=\"true\" ");
        buf.append(">0 hops</option>\n");
        buf.append("<option value=\"-1\" ");
        if (out.getLengthVariance() == -1) buf.append(" selected=\"true\" ");
        buf.append(">+/- 0-1 hops</option>\n");
        buf.append("<option value=\"-2\" ");
        if (out.getLengthVariance() == -2) buf.append(" selected=\"true\" ");
        buf.append(">+/- 0-2 hops</option>\n");
        buf.append("<option value=\"1\" ");
        if (out.getLengthVariance() == 1) buf.append(" selected=\"true\" ");
        buf.append(">+ 0-1 hops</option>\n");
        buf.append("<option value=\"2\" ");
        if (out.getLengthVariance() == 2) buf.append(" selected=\"true\" ");
        buf.append(">+ 0-2 hops</option>\n");
        if (out.getLengthVariance() < -2)
            buf.append("<option value=\"").append(out.getLengthVariance()).append("\">+/- 0-").append(out.getLengthVariance()).append(" hops</option>\n");
        if (out.getLengthVariance() > 2)
            buf.append("<option value=\"").append(out.getLengthVariance()).append("\">+ 0-").append(out.getLengthVariance()).append(" hops</option>\n");
        buf.append("</td>\n");

        // tunnel quantity
        buf.append("<tr><td>Quantity</td>\n");
        buf.append("<td><select name=\"").append(index).append(".quantityInbound\">\n");
        buf.append("<option value=\"1\" ");
        if (in.getQuantity() <= 1) buf.append(" selected=\"true\" ");
        buf.append(">1 tunnel</option>\n");
        buf.append("<option value=\"2\" ");
        if (in.getQuantity() == 2) buf.append(" selected=\"true\" ");
        buf.append(">2 tunnels</option>\n");
        buf.append("<option value=\"3\" ");
        if (in.getQuantity() == 3) buf.append(" selected=\"true\" ");
        buf.append(">3 tunnels</option>\n");
        if (in.getQuantity() > 3)
            buf.append("<option value=\"").append(in.getQuantity()).append("\">").append(in.getQuantity()).append(" tunnels</option>\n");
        buf.append("</td>\n");

        buf.append("<td><select name=\"").append(index).append(".quantityOutbound\">\n");
        buf.append("<option value=\"1\" ");
        if (out.getQuantity() <= 1) buf.append(" selected=\"true\" ");
        buf.append(">1 tunnel</option>\n");
        buf.append("<option value=\"2\" ");
        if (out.getQuantity() == 2) buf.append(" selected=\"true\" ");
        buf.append(">2 tunnels</option>\n");
        buf.append("<option value=\"3\" ");
        if (out.getQuantity() == 3) buf.append(" selected=\"true\" ");
        buf.append(">3 tunnels</option>\n");
        if (out.getQuantity() > 3)
            buf.append("<option value=\"").append(out.getQuantity()).append("\">").append(out.getQuantity()).append(" tunnels</option>\n");
        buf.append("</td>\n");
        buf.append("</tr>\n");

        // tunnel backup quantity
        buf.append("<tr><td>Backup quantity</td>\n");
        buf.append("<td><select name=\"").append(index).append(".backupInbound\">\n");
        buf.append("<option value=\"0\" ");
        if (in.getBackupQuantity() <= 0) buf.append(" selected=\"true\" ");
        buf.append(">0 tunnels</option>\n");
        buf.append("<option value=\"1\" ");
        if (in.getBackupQuantity() == 1) buf.append(" selected=\"true\" ");
        buf.append(">1 tunnel</option>\n");
        buf.append("<option value=\"2\" ");
        if (in.getBackupQuantity() == 2) buf.append(" selected=\"true\" ");
        buf.append(">2 tunnels</option>\n");
        buf.append("<option value=\"3\" ");
        if (in.getBackupQuantity() == 3) buf.append(" selected=\"true\" ");
        buf.append(">3 tunnels</option>\n");
        if (in.getBackupQuantity() > 3)
            buf.append("<option value=\"").append(in.getBackupQuantity()).append("\">").append(in.getBackupQuantity()).append(" tunnels</option>\n");
        buf.append("</td>\n");

        buf.append("<td><select name=\"").append(index).append(".backupOutbound\">\n");
        buf.append("<option value=\"0\" ");
        if (out.getBackupQuantity() <= 0) buf.append(" selected=\"true\" ");
        buf.append(">0 tunnel</option>\n");
        buf.append("<option value=\"1\" ");
        if (out.getBackupQuantity() == 1) buf.append(" selected=\"true\" ");
        buf.append(">1 tunnel</option>\n");
        buf.append("<option value=\"2\" ");
        if (out.getBackupQuantity() == 2) buf.append(" selected=\"true\" ");
        buf.append(">2 tunnels</option>\n");
        buf.append("<option value=\"3\" ");
        if (out.getBackupQuantity() == 3) buf.append(" selected=\"true\" ");
        buf.append(">3 tunnels</option>\n");
        if (out.getBackupQuantity() > 3)
            buf.append("<option value=\"").append(out.getBackupQuantity()).append("\">").append(out.getBackupQuantity()).append(" tunnels</option>\n");
        buf.append("</td>\n");
        buf.append("</tr>\n");

        // custom options
        buf.append("<tr><td>Inbound options:</td>\n");
        buf.append("<td colspan=\"2\"><input name=\"").append(index);
        buf.append(".inboundOptions\" type=\"text\" size=\"40\" ");
        buf.append("value=\"");
        Properties props = in.getUnknownOptions();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String prop = (String)iter.next();
            String val = (String)props.getProperty(prop);
            buf.append(prop).append("=").append(val).append(" ");
        }
        buf.append("\"/></td></tr>\n");
        buf.append("<tr><td>Outbound options:</td>\n");
        buf.append("<td colspan=\"2\"><input name=\"").append(index);
        buf.append(".outboundOptions\" type=\"text\" size=\"40\" ");
        buf.append("value=\"");
        props = in.getUnknownOptions();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String prop = (String)iter.next();
            String val = (String)props.getProperty(prop);
            buf.append(prop).append("=").append(val).append(" ");
        }
        buf.append("\"/></td></tr>\n");
        buf.append("<tr><td colspan=\"3\"><hr /></td></tr>\n");
    }
}
