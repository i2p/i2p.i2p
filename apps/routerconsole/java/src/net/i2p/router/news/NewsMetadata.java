package net.i2p.router.news;

import java.util.List;

import net.i2p.data.DataHelper;
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

    /** I2P metadata */
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
    
        /**
         *  For findbugs.
         *  Warning, not a complete comparison.
         *  Must be enhanced before using in a Map or Set.
         *  @since 0.9.21
         */
        @Override
        public boolean equals(Object o) {
            if (o == null)
                return false;
            if (!(o instanceof Release))
                return false;
            Release r = (Release) o;
            return DataHelper.eq(i2pVersion, r.i2pVersion);
        }
        
        /**
         *  For findbugs.
         *  @since 0.9.21
         */
        @Override
        public int hashCode() {
            return DataHelper.hashCode(i2pVersion);
        }
    }

    public static class Update implements Comparable<Update> {
        public String type;
        public String torrent;
        /**
         *  Stored as of 0.9.52, but there is no registered handler
         */
        public List<String> clearnet;
        /**
         *  Stored as of 0.9.52, but there is no registered handler
         */
        public List<String> ssl;
        /**
         *  In-net URLs
         *  @since 0.9.52
         */
        public List<String> i2pnet;

        @Override
        public int compareTo(Update other) {
            return getTypeOrder() - other.getTypeOrder();
        }

        /** lower is preferred */
        protected int getTypeOrder() {
            if ("su3".equalsIgnoreCase(type))
                return 1;
            else if ("su2".equalsIgnoreCase(type))
                return 2;
            else
                return 3;
        }
    
        /**
         *  For findbugs.
         *  Warning, not a complete comparison.
         *  Must be enhanced before using in a Map or Set.
         *  @since 0.9.21
         */
        @Override
        public boolean equals(Object o) {
            if (o == null)
                return false;
            if (!(o instanceof Update))
                return false;
            Update u = (Update) o;
            return getTypeOrder() == u.getTypeOrder();
        }
        
        /**
         *  For findbugs.
         *  @since 0.9.21
         */
        @Override
        public int hashCode() {
            return getTypeOrder();
        }
    }
}
