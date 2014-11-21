package net.i2p.router.news;

import java.util.List;
import net.i2p.util.VersionComparator;

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

    // I2P metadata
    public List<Release> releases;

    public static class Release implements Comparable<Release> {
        public long date;
        public String minVersion;
        public String minJavaVersion;
        public String i2pVersion;
        public List<Update> updates;

        @Override
        public int compareTo(Release other) {
            // Sort latest version first.
            return VersionComparator.comp(other.i2pVersion, i2pVersion);
        }
    }

    public static class Update implements Comparable<Update> {
        public String type;
        public String torrent;
        public List<String> clearnet;
        public List<String> ssl;

        @Override
        public int compareTo(Update other) {
            return Integer.compare(getTypeOrder(), other.getTypeOrder());
        }

        protected int getTypeOrder() {
            if ("su3".equalsIgnoreCase(type))
                return 1;
            else if ("su2".equalsIgnoreCase(type))
                return 2;
            else
                return 3;
        }
    }
}
