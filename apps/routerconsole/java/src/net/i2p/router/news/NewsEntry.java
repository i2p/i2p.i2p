package net.i2p.router.news;

/**
 *  One news item.
 *  Any String fields may be null.
 *
 *  @since 0.9.17
 */
public class NewsEntry implements Comparable<NewsEntry> {
    public String title;
    public String link;
    public String id;
    public long updated;
    public String summary;
    public String content;
    public String contentType; // attribute of content
    public String authorName;  // subnode of author

    /** reverse, newest first */
    public int compareTo(NewsEntry e) {
        if (updated > e.updated)
            return -1;
        if (updated < e.updated)
            return 1;
        return 0;
    }
}
