package com.maxmind.geoip2;

import com.maxmind.db.*;
import com.maxmind.db.Reader.FileMode;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Modified and simplified for I2P
 *
 * <p>
 * The class {@code DatabaseReader} provides a reader for the GeoIP2 database
 * format.
 * </p>
 * <h3>Usage</h3>
 * <p>
 * To use the database API, you must create a new {@code DatabaseReader} using
 * the {@code DatabaseReader.Builder}. You must provide the {@code Builder}
 * constructor either an {@code InputStream} or {@code File} for your GeoIP2
 * database. You may also specify the {@code fileMode} and the {@code locales}
 * fallback order using the methods on the {@code Builder} object. After you
 * have created the {@code DatabaseReader}, you may then call the appropriate
 * method (e.g., {@code city}) for your database, passing it the IP address
 * you want to look up.
 * </p>
 * <p>
 * If the lookup succeeds, the method call will return a response class for
 * the GeoIP2 lookup. The class in turn contains multiple record classes,
 * each of which represents part of the data returned by the database.
 * </p>
 * <p>
 * We recommend reusing the {@code DatabaseReader} object rather than creating
 * a new one for each lookup. The creation of this object is relatively
 * expensive as it must read in metadata for the file. It is safe to share the
 * object across threads.
 * </p>
 * <h4>Caching</h4>
 * <p>
 * The database API supports pluggable caching (by default, no caching is
 * performed). A simple implementation is provided by
 * {@code com.maxmind.db.CHMCache}.  Using this cache, lookup performance is
 * significantly improved at the cost of a small (~2MB) memory overhead.
 * </p>
 *
 * @since 0.9.38
 */
public class DatabaseReader implements Closeable {

    private final Reader reader;

    private final List<String> locales;

    private DatabaseReader(Builder builder) throws IOException {
        if (builder.stream != null) {
            this.reader = new Reader(builder.stream, builder.cache);
        } else if (builder.database != null) {
            this.reader = new Reader(builder.database, builder.mode, builder.cache);
        } else {
            // This should never happen. If it does, review the Builder class
            // constructors for errors.
            throw new IllegalArgumentException(
                    "Unsupported Builder configuration: expected either File or URL");
        }
        this.locales = builder.locales;
    }

    /**
     * <p>
     * Constructs a Builder for the {@code DatabaseReader}. The file passed to
     * it must be a valid GeoIP2 database file.
     * </p>
     * <p>
     * {@code Builder} creates instances of {@code DatabaseReader}
     * from values set by the methods.
     * </p>
     * <p>
     * Only the values set in the {@code Builder} constructor are required.
     * </p>
     */
    public static final class Builder {
        final File database;
        final InputStream stream;

        List<String> locales = Collections.singletonList("en");
        FileMode mode = FileMode.MEMORY_MAPPED;
        NodeCache cache = NoCache.getInstance();

        /**
         * @param stream the stream containing the GeoIP2 database to use.
         */
        public Builder(InputStream stream) {
            this.stream = stream;
            this.database = null;
        }

        /**
         * @param database the GeoIP2 database file to use.
         */
        public Builder(File database) {
            this.database = database;
            this.stream = null;
        }

        /**
         * @param val List of locale codes to use in name property from most
         *            preferred to least preferred.
         * @return Builder object
         */
        public Builder locales(List<String> val) {
            this.locales = val;
            return this;
        }

        /**
         * @param cache backing cache instance
         * @return Builder object
         */
        public Builder withCache(NodeCache cache) {
            this.cache = cache;
            return this;
        }

        /**
         * @param val The file mode used to open the GeoIP2 database
         * @return Builder object
         * @throws java.lang.IllegalArgumentException if you initialized the Builder with a URL, which uses
         *                                            {@link FileMode#MEMORY}, but you provided a different
         *                                            FileMode to this method.
         */
        public Builder fileMode(FileMode val) {
            if (this.stream != null && FileMode.MEMORY != val) {
                throw new IllegalArgumentException(
                        "Only FileMode.MEMORY is supported when using an InputStream.");
            }
            this.mode = val;
            return this;
        }

        /**
         * @return an instance of {@code DatabaseReader} created from the
         * fields set on this builder.
         * @throws IOException if there is an error reading the database
         */
        public DatabaseReader build() throws IOException {
            return new DatabaseReader(this);
        }
    }

    /**
     * Returns a map containing:
     *
     *<ul><li>continent: Map containing:
     *  <ul><li>code: String
     *      <li>names: Map of lang to translated name
     *      <li>geoname_id: Long
     *  </ul>
     *<ul><li>country: Map containing:
     *  <ul><li>iso_code: String
     *      <li>names: Map of lang to translated name
     *      <li>geoname_id: Long
     *  </ul>
     *<ul><li>registered_country: Map containing:
     *  <ul><li>iso_code: String
     *      <li>names: Map of lang to translated name
     *      <li>geoname_id: Long
     *  </ul>
     *</ul>
     *
     * @param ipAddress IPv4 or IPv6 address to lookup.
     * @return A Map with the data for the IP address
     * @throws IOException              if there is an error opening or reading from the file.
     */
    private Object get(InetAddress ipAddress,
                      String type) throws IOException {

        String databaseType = this.getMetadata().getDatabaseType();
        if (!databaseType.contains(type)) {
            String caller = Thread.currentThread().getStackTrace()[2]
                    .getMethodName();
            throw new UnsupportedOperationException(
                    "Invalid attempt to open a " + databaseType
                            + " database using the " + caller + " method");
        }

        return reader.get(ipAddress);
    }

    /**
     * <p>
     * Closes the database.
     * </p>
     * <p>
     * If you are using {@code FileMode.MEMORY_MAPPED}, this will
     * <em>not</em> unmap the underlying file due to a limitation in Java's
     * {@code MappedByteBuffer}. It will however set the reference to
     * the buffer to {@code null}, allowing the garbage collector to
     * collect it.
     * </p>
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        this.reader.close();
    }

    public String country(String ipAddress) throws IOException {
        InetAddress ia = InetAddress.getByName(ipAddress);
        Object o = get(ia, "Country");
        if (!(o instanceof Map))
            return null;
        Map m = (Map) o;
        o = m.get("country");
        if (!(o instanceof Map))
            return null;
        m = (Map) o;
        o = m.get("iso_code");
        if (!(o instanceof String))
            return null;
        return (String) o;

    }

    /**
     * @return the metadata for the open MaxMind DB file.
     */
    public Metadata getMetadata() {
        return this.reader.getMetadata();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: DatabaseReader geoip2-file.mmdb ip");
            System.exit(1);
        }
        File f = new File(args[0]);
        Builder b = new Builder(f);
        b.withCache(new CHMCache(256));
        DatabaseReader r = b.build();
        System.out.println("Database Metadata: " + r.getMetadata());
        String c = r.country(args[1]);
        System.out.println("IP: " + args[1] + " country: " + c);
    }
}
