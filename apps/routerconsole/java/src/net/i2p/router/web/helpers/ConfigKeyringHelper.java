package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import net.i2p.data.BlindData;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
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
     *  @param local true for local (Enc. LS1 only), false for remote (all types)
     */
    private void render(StringBuilder buf, boolean local) {
        buf.append("\n<table class=\"configtable\"><tr>");
        if (!local)
            buf.append("<th align=\"left\">").append(_t("Delete"));
        buf.append("<th align=\"left\">").append(_t("Destination"))
           .append("<th align=\"left\">").append(_t("Name"));
        if (!local)
            buf.append("<th align=\"left\">").append(_t("Type"));
        buf.append("<th align=\"left\">").append(_t("Encryption Key"));
        if (!local)
            buf.append("<th align=\"left\">").append(_t("Lookup Password"));
        buf.append("</tr>");
        // Enc. LS1
        for (Map.Entry<Hash, SessionKey> e : _context.keyRing().entrySet()) {
            Hash h = e.getKey();
            if (local != _context.clientManager().isLocal(h))
                continue;
            buf.append("\n<tr><td>");
            String b32 = h.toBase32();
            if (!local)
                buf.append("<input value=\"").append(b32).append("\" type=\"checkbox\" name=\"revokeClient\" class=\"tickbox\"/></td><td>");
            buf.append(b32);
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
            if (!local)
                buf.append(_t("Encrypted")).append(" (AES)</td><td>");
            SessionKey sk = e.getValue();
            buf.append(sk.toBase64());
            if (!local)
                buf.append("</td><td>");
            buf.append("</td>\n");
        }
        // LS2
        if (!local) {
            List<BlindData> bdata = _context.netDb().getBlindData();
            // TODO sort by hostname
            for (BlindData bd : bdata) {
                buf.append("\n<tr><td>");
                String b32 = bd.toBase32();
                if (!local)
                    buf.append("<input value=\"").append(b32).append("\" type=\"checkbox\" name=\"revokeClient\" class=\"tickbox\"/></td><td>");
                buf.append(b32);
                buf.append("</td><td>");
                Hash h = bd.getDestHash();
                if (h != null) {
                    String host = _context.namingService().reverseLookup(h);
                    if (host != null)
                        buf.append(host);
                }
                buf.append("</td><td>");
                int type = bd.getAuthType();
                PrivateKey pk = bd.getAuthPrivKey();
                String secret = bd.getSecret();
                String s;
                if (type == BlindData.AUTH_DH) {
                    if (secret != null)
                        s = _t("Encrypted with lookup password") + " (DH)";
                    else
                        s = _t("Encrypted") + " (DH)";
                } else if (type == BlindData.AUTH_PSK) {
                    if (secret != null)
                        s = _t("Encrypted with lookup password") + " (PSK)";
                    else
                        s = _t("Encrypted") + " (PSK)";
                } else  {
                    if (secret != null)
                        s = _t("Blinded with lookup password");
                    else
                        s = _t("Blinded");
                }
                buf.append(s);
                buf.append("</td><td>");
                if (pk != null) {
                    // display pubkey for DH for sharing with server
                    if (type == BlindData.AUTH_DH)
                        buf.append(pk.toPublic().toBase64());
                    else
                        buf.append(pk.toBase64());
                }
                buf.append("</td><td>");
                if (secret != null)
                    buf.append(secret);
                buf.append("</td><tr>");
            }
        }
        buf.append("</table>\n");
    }
}
