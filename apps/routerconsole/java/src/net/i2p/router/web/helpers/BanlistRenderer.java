package net.i2p.router.web.helpers;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.Banlist;
import net.i2p.router.web.Messages;

/**
 *  Moved from Banlist.java
 */
class BanlistRenderer {
    private final RouterContext _context;

    public BanlistRenderer(RouterContext context) {
        _context = context;
    }
    
    /**
     *  As of 0.9.29, sorts in true binary order, not base64 string
     */
    private static class HashComparator implements Comparator<Hash>, Serializable {
         public int compare(Hash l, Hash r) {
             return DataHelper.compareTo(l.getData(), r.getData());
        }
    }

    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        // move to the jsp
        //buf.append("<h2>Banned Peers</h2>");
        Map<Hash, Banlist.Entry> entries = new TreeMap<Hash, Banlist.Entry>(new HashComparator());
        
        entries.putAll(_context.banlist().getEntries());
        if (entries.isEmpty()) {
            buf.append("<i>").append(_t("none")).append("</i>");
            out.write(buf.toString());
            return;
        }

        buf.append("<ul id=\"banlist\">");
        
        for (Map.Entry<Hash, Banlist.Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();
            long expires = entry.expireOn-_context.clock().now();
            if (expires <= 0)
                continue;
            buf.append("<li>").append(_context.commSystem().renderPeerHTML(key));
            buf.append(' ');
            String expireString = DataHelper.formatDuration2(expires);
            if (key.equals(Hash.FAKE_HASH))
                buf.append(_t("Permanently banned"));
            else if (expires < 5l*24*60*60*1000)
                buf.append(_t("Temporary ban expiring in {0}", expireString));
            else
                buf.append(_t("Banned until restart or in {0}", expireString));
            Set<String> transports = entry.transports;
            if ( (transports != null) && (!transports.isEmpty()) )
                buf.append(" on the following transport: ").append(transports);
            if (entry.cause != null) {
                buf.append("<br>\n");
                if (entry.causeCode != null)
                    buf.append(_t(entry.cause, entry.causeCode));
                else
                    buf.append(_t(entry.cause));
            }
            if (!key.equals(Hash.FAKE_HASH)) {
                buf.append(" <a href=\"configpeer?peer=").append(key.toBase64())
                   .append("#unsh\">[").append(_t("unban now")).append("]</a>");
            }
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        out.write(buf.toString());
        out.flush();
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
