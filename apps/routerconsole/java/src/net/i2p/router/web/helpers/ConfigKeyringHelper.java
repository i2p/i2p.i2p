package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.web.HelperBase;


public class ConfigKeyringHelper extends HelperBase {
    public ConfigKeyringHelper() {}
    
    public String getSummary() {
        StringWriter sw = new StringWriter(4*1024);
        try {
            renderStatusHTML(sw);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return sw.toString();
    }

    /**
     *  @since 0.9.33 moved from PersistentKeyRing
     */
    private void renderStatusHTML(StringWriter out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h3>").append(_t("Local encrypted destinations")).append("</h3>");
        render(buf, true);
        buf.append("<h3>").append(_t("Remote encrypted destinations")).append("</h3>");
        render(buf, false);
        out.write(buf.toString());
        out.flush();
    }

    /**
     *  @since 0.9.33 moved from PersistentKeyRing
     */
    private void render(StringBuilder buf, boolean local) {
        buf.append("\n<table class=\"configtable\"><tr><th align=\"left\">").append(_t("Destination"))
           .append("<th align=\"left\">").append(_t("Name"))
           .append("<th align=\"left\">").append(_t("Encryption Key"))
           .append("</tr>");
        for (Map.Entry<Hash, SessionKey> e : _context.keyRing().entrySet()) {
            Hash h = e.getKey();
            if (local != _context.clientManager().isLocal(h))
                continue;
            buf.append("\n<tr><td>");
            buf.append(h.toBase32());
            buf.append("</td><td>");
            Destination dest = _context.netDb().lookupDestinationLocally(h);
            if (dest != null && local) {
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(h);
                if (in != null && in.getDestinationNickname() != null)
                    buf.append(in.getDestinationNickname());
            } else {
                String host = _context.namingService().reverseLookup(h);
                if (host != null)
                    buf.append(host);
            }
            buf.append("</td><td>");
            SessionKey sk = e.getValue();
            buf.append(sk.toBase64());
            buf.append("</td>\n");
        }
        buf.append("</table>\n");
    }
}
