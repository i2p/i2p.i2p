package net.i2p.router.web;

import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;

public class ConfigTunnelsHelper extends HelperBase {
    public ConfigTunnelsHelper() {}
    
    
    public String getForm() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
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

    private static final int WARN_LENGTH = 4;
    private static final int MAX_LENGTH = 4;
    private static final int WARN_QUANTITY = 5;
    private static final int MAX_QUANTITY = 6;
    private static final int MAX_BACKUP_QUANTITY = 3;
    private static final int MAX_VARIANCE = 2;
    private static final int MIN_NEG_VARIANCE = -1;
    private void renderForm(StringBuilder buf, int index, String prefix, String name, TunnelPoolSettings in, TunnelPoolSettings out) {

        buf.append("<tr><th colspan=\"3\"><a name=\"").append(prefix).append("\">");
        buf.append(name).append("</a></th></tr>\n");
        if (in.getLength() <= 0 ||
            in.getLength() + in.getLengthVariance() <= 0 ||
            out.getLength() <= 0 ||
            out.getLength() + out.getLengthVariance() <= 0)
            buf.append("<tr><th colspan=\"3\"><font color=\"red\">ANONYMITY WARNING - Settings include 0-hop tunnels.</font></th></tr>");
        else if (in.getLength() <= 1 ||
            in.getLength() + in.getLengthVariance() <= 1 ||
            out.getLength() <= 1 ||
            out.getLength() + out.getLengthVariance() <= 1)
            buf.append("<tr><th colspan=\"3\"><font color=\"red\">ANONYMITY WARNING - Settings include 1-hop tunnels.</font></th></tr>");
        if (in.getLength() + Math.abs(in.getLengthVariance()) >= WARN_LENGTH ||
            out.getLength() + Math.abs(out.getLengthVariance()) >= WARN_LENGTH)
            buf.append("<tr><th colspan=\"3\"><font color=\"red\">PERFORMANCE WARNING - Settings include very long tunnels.</font></th></tr>");
        if (in.getQuantity() + in.getBackupQuantity() >= WARN_QUANTITY ||
            out.getQuantity() + out.getBackupQuantity() >= WARN_QUANTITY)
            buf.append("<tr><th colspan=\"3\"><font color=\"red\">PERFORMANCE WARNING - Settings include high tunnel quantities.</font></th></tr>");

buf.append("<tr><th></th><th><img src=\"/themes/console/images/inbound.png\" alt=\"Inbound\" title=\"Inbound Tunnels\"/>&nbsp;&nbsp;Inbound</th><th><img src=\"/themes/console/images/outbound.png\" alt=\"Outbound Tunnels\" title=\"Outbound\"/>&nbsp;&nbsp;Outbound</th></tr>\n");

//        buf.append("<tr><th></th><th>Inbound</th><th>Outbound</th></tr>\n");
        
        // tunnel depth
        buf.append("<tr><td align=\"right\" class=\"mediumtags\">Depth:</td>\n");
        buf.append("<td align=\"center\"><select name=\"").append(index).append(".depthInbound\">\n");
        int now = in.getLength();
        renderOptions(buf, 0, MAX_LENGTH, now, "", "hop");
        if (now > MAX_LENGTH)
            renderOptions(buf, now, now, now, "", "hop");
        buf.append("</select></td>\n");

        buf.append("<td align=\"center\"><select name=\"").append(index).append(".depthOutbound\">\n");
        now = out.getLength();
        renderOptions(buf, 0, MAX_LENGTH, now, "", "hop");
        if (now > MAX_LENGTH)
            renderOptions(buf, now, now, now, "", "hop");
        buf.append("</select></td>\n");
        buf.append("</tr>\n");

        // tunnel depth variance
        buf.append("<tr><td align=\"right\" class=\"mediumtags\">Randomization:</td>\n");
        buf.append("<td align=\"center\"><select name=\"").append(index).append(".varianceInbound\">\n");
        now = in.getLengthVariance();
        renderOptions(buf, 0, 0, now, "", "hop");
        renderOptions(buf, 1, MAX_VARIANCE, now, "+ 0-", "hop");
        renderOptions(buf, MIN_NEG_VARIANCE, -1, now, "+/- 0", "hop");
        if (now > MAX_VARIANCE)
            renderOptions(buf, now, now, now, "+ 0-", "hop");
        else if (now < MIN_NEG_VARIANCE)
            renderOptions(buf, now, now, now, "+/- 0", "hop");
        buf.append("</select></td>\n");

        buf.append("<td align=\"center\"><select name=\"").append(index).append(".varianceOutbound\">\n");
        now = out.getLengthVariance();
        renderOptions(buf, 0, 0, now, "", "hop");
        renderOptions(buf, 1, MAX_VARIANCE, now, "+ 0-", "hop");
        renderOptions(buf, MIN_NEG_VARIANCE, -1, now, "+/- 0", "hop");
        if (now > MAX_VARIANCE)
            renderOptions(buf, now, now, now, "+ 0-", "hop");
        else if (now < MIN_NEG_VARIANCE)
            renderOptions(buf, now, now, now, "+/- 0", "hop");
        buf.append("</select></td>\n");

        // tunnel quantity
        buf.append("<tr><td align=\"right\" class=\"mediumtags\">Quantity:</td>\n");
        buf.append("<td align=\"center\"><select name=\"").append(index).append(".quantityInbound\">\n");
        now = in.getQuantity();
        renderOptions(buf, 1, MAX_QUANTITY, now, "", "tunnel");
        if (now > MAX_QUANTITY)
            renderOptions(buf, now, now, now, "", "tunnel");
        buf.append("</select></td>\n");

        buf.append("<td align=\"center\"><select name=\"").append(index).append(".quantityOutbound\">\n");
        now = out.getQuantity();
        renderOptions(buf, 1, MAX_QUANTITY, now, "", "tunnel");
        if (now > MAX_QUANTITY)
            renderOptions(buf, now, now, now, "", "tunnel");
        buf.append("</select></td>\n");
        buf.append("</tr>\n");

        // tunnel backup quantity
        buf.append("<tr><td align=\"right\" class=\"mediumtags\">Backup quantity:</td>\n");
        buf.append("<td align=\"center\"><select name=\"").append(index).append(".backupInbound\">\n");
        now = in.getBackupQuantity();
        renderOptions(buf, 0, MAX_BACKUP_QUANTITY, now, "", "tunnel");
        if (now > MAX_BACKUP_QUANTITY)
            renderOptions(buf, now, now, now, "", "tunnel");
        buf.append("</select></td>\n");

        buf.append("<td align=\"center\"><select name=\"").append(index).append(".backupOutbound\">\n");
        now = out.getBackupQuantity();
        renderOptions(buf, 0, MAX_BACKUP_QUANTITY, now, "", "tunnel");
        if (now > MAX_BACKUP_QUANTITY)
            renderOptions(buf, now, now, now, "", "tunnel");
        buf.append("</select></td>\n");
        buf.append("</tr>\n");

        // custom options
        buf.append("<tr><td align=\"right\" class=\"mediumtags\">Inbound options:</td>\n");
        buf.append("<td colspan=\"2\" align=\"center\"><input name=\"").append(index);
        buf.append(".inboundOptions\" type=\"text\" size=\"32\" ");
        buf.append("value=\"");
        Properties props = in.getUnknownOptions();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String prop = (String)iter.next();
            String val = (String)props.getProperty(prop);
            buf.append(prop).append("=").append(val).append(" ");
        }
        buf.append("\"/></td></tr>\n");
        buf.append("<tr><td align=\"right\" class=\"mediumtags\">Outbound options:</td>\n");
        buf.append("<td colspan=\"2\" align=\"center\"><input name=\"").append(index);
        buf.append(".outboundOptions\" type=\"text\" size=\"32\" ");
        buf.append("value=\"");
        props = in.getUnknownOptions();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String prop = (String)iter.next();
            String val = (String)props.getProperty(prop);
            buf.append(prop).append("=").append(val).append(" ");
        }
        buf.append("\"/></td></tr>\n");
//        buf.append("<tr><td colspan=\"3\"><hr /></td></tr>\n");
    }

    private void renderOptions(StringBuilder buf, int min, int max, int now, String prefix, String name) {
        for (int i = min; i <= max; i++) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (i == now)
                buf.append("selected=\"true\" ");
            buf.append(">").append(prefix).append(i).append(' ').append(name);
            if (i != 1 && i != -1)
                buf.append('s');
            buf.append("</option>\n");
        }
    }
}
