package net.i2p.router.web;

import java.io.File;

/**
 *  If news file does not exist, use file from the initialNews directory
 *  in $I2P
 *
 *  @since 0.8.2
 */
public class NewsHelper extends ContentHelper {
    
    @Override
    public String getContent() {
        File news = new File(_page);
        if (!news.exists())
            _page = (new File(_context.getBaseDir(), "docs/initialNews/initialNews.xml")).getAbsolutePath();
        return super.getContent();
    }

    /** @since 0.9.1 */
    public String getNewsHeadings() {
        StringBuilder buf = new StringBuilder(512);
        String consoleNonce = System.getProperty("router.consoleNonce");
        if (consoleNonce != null) {
            // Set up string containing <a> to show news.
            String newsUrl = "<a href=\"/?news=1&amp;consoleNonce=" + consoleNonce + "\">";
            // Set up title and pre-headings stuff.
            buf.append("<h3><a href=\"/configupdate\">").append(_("News & Updates"))
            .append("</a></h3><hr class=\"b\"><div class=\"newsheadings\">\n");
            // Get news content.
            String newsContent = getContent();
            if (newsContent != "") {
                buf.append("<ul>\n");
                // Parse news content for headings.
                int start = newsContent.indexOf("<h3>");
                while (start >= 0) {
                    // Add offset to start:
                    // 4 - gets rid of <h3>
                    // 16 - gets rid of the date as well (assuming form "<h3>yyyy-mm-dd: Foobarbaz...")
                    newsContent = newsContent.substring(start+16, newsContent.length());
                    int end = newsContent.indexOf("</h3>");
                    if (end >= 0) {
                        String heading = newsContent.substring(0, end);
                        buf.append("<li>").append(heading).append("</li>\n");
                    }
                    start = newsContent.indexOf("<h3>");
                }
                buf.append("</ul>\n");
                buf.append(newsUrl).append(Messages.getString("Show news", _context)).append("</a>\n");
            } else {
                buf.append("<center><i>").append(_("none")).append("</i></center>");
            }
            // Add post-headings stuff.
            buf.append("</div>\n");
        }
        return buf.toString();
    }

    /** @since 0.8.12 */
    public boolean shouldShowNews() {
        return NewsFetcher.getInstance(_context).shouldShowNews();
    }
}
