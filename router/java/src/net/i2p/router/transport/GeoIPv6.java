package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Use at your own risk.
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 *  Generate compressed geoipv6.dat.gz file, and
 *  lookup entries in it.
 *
 *  Public only for command line use,
 *  not a public API, not for external use.
 *
 *  @since IPv6
 */
public class GeoIPv6 {

    private static final String GEOIP_DIR_DEFAULT = "geoip";
    private static final String GEOIP_FILE_DEFAULT = "geoipv6.dat.gz";
    private static final String MAGIC = "I2PGeoIPv6\0\001\0\0\0\0";
    private static final String COMMENT = "I2P compressed geoipv6 file. See GeoIPv6.java for format.";
    /** includes magic */
    private static final int HEADER_LEN = 256;

    /**
     * Lookup search items in the geoip file.
     * See below for format.
     *
     * @param search a sorted array of IPs to search
     * @return an array of country codes, same order as the search param,
     *         or a zero-length array on total failure.
     *         Individual array elements will be null for lookup failure of that item.
     */
    public static String[] readGeoIPFile(I2PAppContext context, Long[] search, Map<String, String> codeCache) {
        Log log = context.logManager().getLog(GeoIPv6.class);
        File geoFile = new File(context.getBaseDir(), GEOIP_DIR_DEFAULT);
        geoFile = new File(geoFile, GEOIP_FILE_DEFAULT);
        if (!geoFile.exists()) {
            if (log.shouldLog(Log.WARN))
                log.warn("GeoIP file not found: " + geoFile.getAbsolutePath());
            return new String[0];
        }
        return readGeoIPFile(geoFile, search, codeCache, log);
    }

    /**
     * Lookup search items in the geoip file.
     * See below for format.
     *
     * @param search a sorted array of IPs to search
     * @return an array of country codes, same order as the search param,
     *         or a zero-length array on total failure.
     *         Individual array elements will be null for lookup failure of that item.
     */
    private static String[] readGeoIPFile(File geoFile, Long[] search, Map<String, String> codeCache, Log log) {
        String[] rv = new String[search.length];
        int idx = 0;
        long start = System.currentTimeMillis();
        InputStream in = null;
        try {
            in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(geoFile)));
            byte[] magic = new byte[MAGIC.length()];
            DataHelper.read(in, magic);
            if (!DataHelper.eq(magic, DataHelper.getASCII(MAGIC)))
                throw new IOException("Not a IPv6 geoip data file");
            // skip timestamp and comments
            DataHelper.skip(in, HEADER_LEN - MAGIC.length());
            byte[] buf = new byte[18];
            while (idx < search.length) {
                try {
                    DataHelper.read(in, buf);
                } catch (EOFException eofe) {
                    // normal,
                    // we could hit the end before finding everything
                    break;
                }
                long ip1 = readLong(buf, 0);
                long ip2 = readLong(buf, 8);
                while (idx < search.length && search[idx].longValue() < ip1) {
                    idx++;
                }
                while (idx < search.length && search[idx].longValue() >= ip1 && search[idx].longValue() <= ip2) {
                    // written in lower case
                    String lc = new String(buf, 16, 2, "ISO-8859-1");
                    // replace the new string with the identical one from the cache
                    String cached = codeCache.get(lc);
                    if (cached == null)
                        cached = lc;
                    rv[idx++] = cached;
                }
            }
        } catch (IOException ioe) {
            if (log.shouldLog(Log.ERROR))
                log.error("Error reading the geoFile", ioe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }

        if (log.shouldLog(Log.INFO))
            log.info("GeoIPv6 processing finished, time: " + (System.currentTimeMillis() - start));
        return rv;
    }


   /**
    * Read in and parse multiple IPv6 geoip CSV files,
    * merge them, and write out a gzipped binary IPv6 geoip file.
    *
    * Acceptable input formats (IPv6 only):
    *<pre>
    *   #comment (# must be in column 1)
    *   "text IP", "text IP", "bigint IP", "bigint IP", "country code", "country name"
    *</pre>
    * Quotes and spaces optional. Sorting not required.
    * Country code case-insensitive.
    * Fields 1, 2, and 5 are used; fields 3, 4, and 6 are ignored.
    * This is identical to the format of the MaxMind GeoLite IPv6 file.
    *
    * Example:
    *<pre>
    *   "2001:200::", "2001:200:ffff:ffff:ffff:ffff:ffff:ffff", "42540528726795050063891204319802818560", "42540528806023212578155541913346768895", "JP", "Japan"
    *</pre>
    *
    *<pre>
    * Output format:
    *   Bytes 0-9: Magic number "I2PGeoIPv6"
    *   Bytes 10-11: version (0x0001)
    *   Bytes 12-15 flags (0)
    *   Bytes 16-23: Date (long)
    *   Bytes 24-xx: Comment (UTF-8)
    *   Bytes xx-255: null padding
    *   Bytes 256-: 18 byte records:
    *       8 byte from (/64)
    *       8 byte to (/64)
    *       2 byte country code LOWER case (ASCII)
    *   Data must be sorted (SIGNED twos complement), no overlap
    *</pre>
    *
    * SLOW. For preprocessing only!
    *
    * @return success
    */
    private static boolean compressGeoIPv6CSVFiles(List<File> inFiles, File outFile) {
        boolean DEBUG = false;
        List<V6Entry> entries = new ArrayList<V6Entry>(20000);
        for (File geoFile : inFiles) {
            int count = 0;
            InputStream in = null;
            BufferedReader br = null;
            try {
                in = new BufferedInputStream(new FileInputStream(geoFile));
                if (geoFile.getName().endsWith(".gz"))
                    in = new GZIPInputStream(in);
                String buf = null;
                br = new BufferedReader(new InputStreamReader(in, "ISO-8859-1"));
                while ((buf = br.readLine()) != null) {
                    try {
                        if (buf.charAt(0) == '#') {
                            continue;
                        }
                        String[] s = DataHelper.split(buf, ",");
                        String ips1 = s[0].replace("\"", "").trim();
                        String ips2 = s[1].replace("\"", "").trim();
                        byte[] ip1 = InetAddress.getByName(ips1).getAddress();
                        byte[] ip2 = InetAddress.getByName(ips2).getAddress();
                        String country = s[4].replace("\"", "").trim().toLowerCase(Locale.US);
                        entries.add(new V6Entry(ip1, ip2, country));
                        count++;
                    } catch (UnknownHostException uhe) {
                        uhe.printStackTrace();
                    } catch (RuntimeException re) {
                        re.printStackTrace();
                    }
                }
                System.err.println("Read " + count + " entries from " + geoFile);
            } catch (IOException ioe) {
                ioe.printStackTrace();
                //if (_log.shouldLog(Log.ERROR))
                //    _log.error("Error reading the geoFile", ioe);
                return false;
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
                if (br != null) try { br.close(); } catch (IOException ioe) {}
            }
        }
        Collections.sort(entries);
        // merge
        V6Entry old = null;
        for (int i = 0; i < entries.size(); i++) {
            V6Entry e = entries.get(i);
            if (DEBUG)
                System.out.println("proc " + e.toString());
            if (old != null) {
                if (e.from == old.from && e.to == old.to) {
                    // dup
                    if (DEBUG)
                        System.out.println("remove dup " + e);
                    entries.remove(i);
                    i--;
                    continue;
                }
                if (e.from <= old.to) {
                    // overlap
                    // truncate old
                    if (e.from < old.to) {
                        V6Entry rewrite = new V6Entry(old.from, e.from - 1, old.cc);
                        if (DEBUG)
                            System.out.println("rewrite old to " + rewrite);
                        entries.set(i - 1, rewrite);
                    }
                    if (e.to < old.to) {
                        // e inside old, add new after e
                        V6Entry insert = new V6Entry(e.to + 1, old.to, old.cc);
                        if (DEBUG)
                            System.out.println("insert " + insert);
                        int j = i + 1;
                        while (j < entries.size() && insert.compareTo(entries.get(j)) > 0) {
                            j++;
                        }
                        entries.add(j, insert);
                    }
                }
            }
            old = e;
        }
        OutputStream out = null;
        try {
            out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
            out.write(DataHelper.getASCII(MAGIC));
            writeLong(out, System.currentTimeMillis());
            byte[] comment = DataHelper.getUTF8(COMMENT);
            out.write(comment);
            out.write(new byte[256 - (16 + 8 + comment.length)]);
            for (V6Entry e : entries) {
                writeLong(out, e.from);
                writeLong(out, e.to);
                out.write(DataHelper.getASCII(e.cc));
            }
            System.err.println("Wrote " + entries.size() + " entries to " + outFile);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            //if (_log.shouldLog(Log.ERROR))
            //    _log.error("Error reading the geoFile", ioe);
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
        return true;
    }

    /**
     *  Used to temporarily hold, sort, and merge entries before compressing
     */
    private static class V6Entry implements Comparable<V6Entry> {
        public final long from, to;
        public final String cc;

        public V6Entry(byte[] f, byte[] t, String c) {
            if (f.length != 16 || t.length != 16 || c.length() != 2)
                throw new IllegalArgumentException();
            from = toLong(f);
            to = toLong(t);
            cc = c;
            if (to < from)
                throw new IllegalArgumentException(toString());
        }

        public V6Entry(long f, long t, String c) {
            from = f;
            to = t;
            cc = c;
            if (t < f)
                throw new IllegalArgumentException(toString());
        }

        /** twos complement */
        public int compareTo(V6Entry r) {
            if (from < r.from) return -1;
            if (r.from < from) return 1;
            if (to < r.to) return -1;
            if (r.to < to) return 1;
            return 0;
        }

        @Override
        public int hashCode() { return (((int) from) ^ ((int) to)); }

        @Override
        public boolean equals(Object o) { return (o instanceof V6Entry) && compareTo((V6Entry)o) == 0; }

        @Override
        public String toString() {
                return "0x" + Long.toHexString(from) + " -> 0x" + Long.toHexString(to) + " : " + cc;
        }
    }

    private static long toLong(byte ip[]) {
        long rv = 0;
        for (int i = 0; i < 8; i++)
            rv |= (ip[i] & 0xffL) << ((7-i)*8);
        return rv;
    }

    /** like DataHelper.writeLong(rawStream, 8, value) but allows negative values */
    private static void writeLong(OutputStream rawStream, long value) throws IOException {
        for (int i = 56; i >= 0; i -= 8) {
            byte cur = (byte) (value >> i);
            rawStream.write(cur);
        }
    }

    /** like DataHelper.readLong(src, offset, 8) but allows negative values */
    private static long readLong(byte[] src, int offset) throws IOException {
        long rv = 0;
        int limit = offset + 8;
        for (int i = offset; i < limit; i++) {
            rv <<= 8;
            rv |= src[i] & 0xFF;
        }
        return rv;
    }

    /**
     *  Merge and compress CSV files to I2P compressed format
     *
     *  GeoIPv6 infile1.csv[.gz] [infile2.csv[.gz]...] outfile.dat.gz
     *
     *  Used to create the file for distribution, do not comment out
     */
    public static void main(String args[]) {
        if (args.length < 2) {
            System.err.println("Usage: GeoIPv6 infile1.csv [infile2.csv...] outfile.dat.gz");
            System.exit(1);
        }
        List<File> infiles = new ArrayList<File>();
        for (int i = 0; i < args.length - 1; i++) {
            infiles.add(new File(args[i]));
        }
        File outfile = new File(args[args.length - 1]);
        boolean success = compressGeoIPv6CSVFiles(infiles, outfile);
        if (!success) {
            System.err.println("Failed");
            System.exit(1);
        }
        // readback for testing
        readGeoIPFile(outfile, new Long[] { Long.MAX_VALUE }, Collections.<String, String> emptyMap(), new Log(GeoIPv6.class));
    }
}
