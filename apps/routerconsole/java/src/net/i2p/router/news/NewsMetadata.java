package net.i2p.router.news;

import java.util.List;

/**
 *  The update metadata.
 *  Any String or List fields may be null.
 *
 *  @since 0.9.17
 */
public class NewsMetadata {
    // Standard Atom feed metadata
    public String feedTitle;
    public String feedSubtitle;
    public String feedID;
    public long feedUpdated;

    // I2P update metadata
    public long date;
    public String minVersion;
    public String minJavaVersion;
    public String i2pVersion;
    public String sudTorrent;
    public String su2Torrent;
    public String su3Torrent;
    public List<String> su3Clearnet;
    public List<String> su3SSL;
}
