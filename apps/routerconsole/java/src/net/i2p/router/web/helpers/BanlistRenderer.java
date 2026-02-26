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
    static final int PAGE_SIZE = 2048;
    private int _pageSize = PAGE_SIZE;
    private int _page;

    private final RouterContext _context;

    public BanlistRenderer(RouterContext context) {
        _context = context;
    }

    /**
     * @param page 0-based
     * @since 0.9.69
     */
    public void setPage(int page) {
        _page = page;
    }

    /**
     * @since 0.9.69
     */
    public void setPageSize(int ps) {
        _pageSize = ps;
    }

    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(2048);
        Map<Hash, Banlist.Entry> entries = new TreeMap<Hash, Banlist.Entry>(HashComparator.getInstance());
        
        _context.banlist().getEntries(entries);
        buf.append("<h3 id=\"bannedpeers\">").append(_t("Banned Peers"));
        int sz = entries.size();
        if (sz == 0) {
            buf.append("</h3><i>").append(_t("none")).append("</i>");
            out.append(buf);
            return;
        } else {
            buf.append(" (").append(sz).append(")</h3>");
        }

        boolean morePages = false;
        int toSkip = _pageSize * _page;
        int last = Math.min(toSkip + _pageSize, sz);
        if (last < sz)
            morePages = true;
        if (_page > 0 || morePages)
            outputPageLinks(buf, _page, _pageSize, morePages);

        buf.append("<ul id=\"banlist\">");
        
        String unban = _t("unban now");
        int i = 0;
        for (Map.Entry<Hash, Banlist.Entry> e : entries.entrySet()) {
            if (i++ < toSkip)
                continue;
            if (i > last)
                break;
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();
            long expires = entry.expireOn-_context.clock().now();
            if (expires <= 0)
                continue;
            buf.append("<li>").append(_context.commSystem().renderPeerHTML(key));
            buf.append(' ');
            String expireString = DataHelper.formatDuration2(expires);
            if (key.equals(Hash.FAKE_HASH) || key.equals(Banlist.HASH_ZERORI))
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
                // note: CSS hides anchor text
                buf.append(" <a href=\"configpeer?peer=").append(key.toBase64())
                   .append("#unsh\" title=\"").append(unban).append("\">[").append(unban).append("]</a>");
            }
            buf.append("</li>\n");
            if (buf.length() > 1024) {
                out.append(buf);
                buf.setLength(0);
            }
        }
        buf.append("</ul>\n");
        if (_page > 0 || morePages)
            outputPageLinks(buf, _page, _pageSize, morePages);
        out.append(buf);
        out.flush();
    }

    /**
     *  @since 0.9.69
     */
    private void outputPageLinks(StringBuilder buf, int page, int pageSize, boolean morePages) {
        buf.append("<div class=\"netdbnotfound\">");
        if (page > 0) {
            buf.append("<a href=\"/profiles?f=3&amp;pg=").append(page)
               .append("&amp;ps=").append(pageSize).append("\">");
            buf.append(_t("Previous Page"));
            buf.append("</a>&nbsp;&nbsp;&nbsp;");
        }
        buf.append(_t("Page")).append(' ').append(page + 1);
        if (morePages) {
            buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/profiles?f=3&amp;pg=").append(page + 2)
               .append("&amp;ps=").append(pageSize).append("\">");
            buf.append(_t("Next Page"));
            buf.append("</a>");
        }
        buf.append("</div>");
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
