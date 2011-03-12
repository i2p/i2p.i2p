package net.i2p.router.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.Shitlist;

/**
 *  Moved from Shitlist.java
 */
public class ShitlistRenderer {
    private RouterContext _context;

    public ShitlistRenderer(RouterContext context) {
        _context = context;
    }
    
    private static class HashComparator implements Comparator {
         public int compare(Object l, Object r) {
             return ((Hash)l).toBase64().compareTo(((Hash)r).toBase64());
        }
    }

    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        // move to the jsp
        //buf.append("<h2>Banned Peers</h2>");
        Map<Hash, Shitlist.Entry> entries = new TreeMap(new HashComparator());
        
        entries.putAll(_context.shitlist().getEntries());
        if (entries.isEmpty()) {
            buf.append("<i>").append(_("none")).append("</i>");
            out.write(buf.toString());
            return;
        }

        buf.append("<ul>");
        
        for (Map.Entry<Hash, Shitlist.Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Shitlist.Entry entry = e.getValue();
            buf.append("<li>").append(_context.commSystem().renderPeerHTML(key));
            buf.append(' ');
            long expires = entry.expireOn-_context.clock().now();
            String expireString = DataHelper.formatDuration2(expires);
            if (expires < 5l*24*60*60*1000)
                buf.append(_("Temporary ban expiring in {0}", expireString));
            else
                buf.append(_("Banned until restart or in {0}", expireString));
            Set transports = entry.transports;
            if ( (transports != null) && (!transports.isEmpty()) )
                buf.append(" on the following transport: ").append(transports);
            if (entry.cause != null) {
                buf.append("<br>\n");
                if (entry.causeCode != null)
                    buf.append(_(entry.cause, entry.causeCode));
                else
                    buf.append(_(entry.cause));
            }
            buf.append(" (<a href=\"configpeer?peer=").append(key.toBase64())
               .append("#unsh\">").append(_("unban now")).append("</a>)");
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        out.write(buf.toString());
        out.flush();
    }

    /** translate a string */
    private String _(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To tranlslate parameter also, use _("foo {0} bar", _("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
