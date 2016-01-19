package net.i2p.router.web;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.data.DataHelper;
import net.i2p.router.news.NewsEntry;
import net.i2p.router.news.NewsManager;
import net.i2p.util.SystemVersion;


/**
 *  HTML-formatted full news entries
 *
 *  @since 0.9.23
 */
public class NewsFeedHelper extends HelperBase {
    
    private int _start = 0;
    private int _limit = 2;

    /**
     *  @param limit less than or equal to zero means all
     */
    public void setLimit(int limit) {
        _limit = limit;
    }

    public void setStart(int start) {
        _start = start;
    }

    public String getEntries() {
        return getEntries(_context, _start, _limit);
    }

    /**
     *  @param max less than or equal to zero means all
     *  @return non-null, "" if none
     */
    static String getEntries(I2PAppContext ctx, int start, int max) {
        if (max <= 0)
            max = Integer.MAX_VALUE;
        StringBuilder buf = new StringBuilder(512);
        List<NewsEntry> entries = Collections.emptyList();
        ClientAppManager cmgr = ctx.clientAppManager();
        if (cmgr != null) {
            NewsManager nmgr = (NewsManager) cmgr.getRegisteredApp(NewsManager.APP_NAME);
            if (nmgr != null)
                entries = nmgr.getEntries();
        }
        if (!entries.isEmpty()) {
            DateFormat fmt = DateFormat.getDateInstance(DateFormat.SHORT);
            // the router sets the JVM time zone to UTC but saves the original here so we can get it
            fmt.setTimeZone(SystemVersion.getSystemTimeZone(ctx));
            int i = 0;
            for (NewsEntry entry : entries) {
                if (i++ < start)
                    continue;
                buf.append("<div class=\"newsentry\"><h3>");
                if (entry.updated > 0) {
                    Date date = new Date(entry.updated);
                    buf.append("<font size=\"-1\">")
                       .append(fmt.format(date))
                       .append(":</font> ");
                }
                if (entry.link != null)
                    buf.append("<a href=\"").append(DataHelper.escapeHTML(entry.link)).append("\">");
                buf.append(entry.title);
                if (entry.link != null)
                    buf.append("</a>");
                if (entry.authorName != null) {
                    buf.append(" <font size=\"-2\">(<i>")
                       .append(Messages.getString("by {0}", DataHelper.escapeHTML(entry.authorName), ctx))
                       .append("</i>)</font>\n");
                }
                buf.append("</h3>\n<div class=\"newscontent\">\n")
                   .append(entry.content)
                   .append("\n</div></div>\n");
                if (i >= start + max)
                    break;
            }
        }
        return buf.toString();
    }
}
