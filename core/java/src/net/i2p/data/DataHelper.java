package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 * Defines some simple IO routines for dealing with marshalling data structures
 *
 * @author jrandom
 */
public class DataHelper {
    private final static String _equal = "="; // in UTF-8
    private final static String _semicolon = ";"; // in UTF-8

    /** Read a mapping from the stream, as defined by the I2P data structure spec,
     * and store it into a Properties object.
     *
     * A mapping is a set of key / value pairs. It starts with a 2 byte Integer (ala readLong(rawStream, 2))
     * defining how many bytes make up the mapping.  After that comes that many bytes making
     * up a set of UTF-8 encoded characters. The characters are organized as key=value;.
     * The key is a String (ala readString(rawStream)) unique as a key within the current
     * mapping that does not include the UTF-8 characters '=' or ';'.  After the key
     * comes the literal UTF-8 character '='.  After that comes a String (ala readString(rawStream))
     * for the value. Finally after that comes the literal UTF-8 character ';'. This key=value;
     * is repeated until there are no more bytes (not characters!) left as defined by the
     * first two byte integer.
     * @param rawStream stream to read the mapping from
     * @throws DataFormatException if the format is invalid
     * @throws IOException if there is a problem reading the data
     * @return mapping
     */
    public static Properties readProperties(InputStream rawStream) 
        throws DataFormatException, IOException {
        Properties props = new OrderedProperties();
        long size = readLong(rawStream, 2);
        byte data[] = new byte[(int) size];
        int read = read(rawStream, data);
        if (read != size) throw new DataFormatException("Not enough data to read the properties");
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        byte eqBuf[] = _equal.getBytes();
        byte semiBuf[] = _semicolon.getBytes();
        while (in.available() > 0) {
            String key = readString(in);
            read = read(in, eqBuf);
            if ((read != eqBuf.length) || (!eq(new String(eqBuf), _equal))) {
                break;
            }
            String val = readString(in);
            read = read(in, semiBuf);
            if ((read != semiBuf.length) || (!eq(new String(semiBuf), _semicolon))) {
                break;
            }
            props.put(key, val);
        }
        return props;
    }

    /**
     * Write a mapping to the stream, as defined by the I2P data structure spec,
     * and store it into a Properties object.  See readProperties for the format.
     *
     * @param rawStream stream to write to
     * @param props properties to write out
     * @throws DataFormatException if there is not enough valid data to write out
     * @throws IOException if there is an IO error writing out the data
     */
    public static void writeProperties(OutputStream rawStream, Properties props) 
            throws DataFormatException, IOException {
        OrderedProperties p = new OrderedProperties();
        if (props != null) p.putAll(props);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
        for (Iterator iter = p.keySet().iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            String val = p.getProperty(key);
            // now make sure they're in UTF-8
            key = new String(key.getBytes(), "UTF-8");
            val = new String(val.getBytes(), "UTF-8");
            writeString(baos, key);
            baos.write(_equal.getBytes());
            writeString(baos, val);
            baos.write(_semicolon.getBytes());
        }
        baos.close();
        byte propBytes[] = baos.toByteArray();
        writeLong(rawStream, 2, propBytes.length);
        rawStream.write(propBytes);
    }

    /**
     * Pretty print the mapping
     *
     */
    public static String toString(Properties options) {
        StringBuffer buf = new StringBuffer();
        if (options != null) {
            for (Iterator iter = options.keySet().iterator(); iter.hasNext();) {
                String key = (String) iter.next();
                String val = options.getProperty(key);
                buf.append("[").append(key).append("] = [").append(val).append("]");
            }
        } else {
            buf.append("(null properties map)");
        }
        return buf.toString();
    }

    /**
     * Pretty print the collection
     *
     */
    public static String toString(Collection col) {
        StringBuffer buf = new StringBuffer();
        if (col != null) {
            for (Iterator iter = col.iterator(); iter.hasNext();) {
                Object o = iter.next();
                buf.append("[").append(o).append("]");
                if (iter.hasNext()) buf.append(", ");
            }
        } else {
            buf.append("null");
        }
        return buf.toString();
    }

    public static String toString(byte buf[]) {
        if (buf == null)
            return "";
        else
            return toString(buf, buf.length);
    }

    public static String toString(byte buf[], int len) {
        if (buf == null) buf = "".getBytes();
        StringBuffer out = new StringBuffer();
        if (len > buf.length) {
            for (int i = 0; i < len - buf.length; i++)
                out.append("00");
        }
        for (int i = 0; i < buf.length && i < len; i++) {
            StringBuffer temp = new StringBuffer(Integer.toHexString((int) buf[i]));
            while (temp.length() < 2) {
                temp.insert(0, '0');
            }
            temp = new StringBuffer(temp.substring(temp.length() - 2));
            out.append(temp.toString());
        }
        return out.toString();
    }

    public static String toDecimalString(byte buf[], int len) {
        if (buf == null) buf = "".getBytes();
        BigInteger val = new BigInteger(1, buf);
        return val.toString(10);
    }

    public final static String toHexString(byte data[]) {
        if ((data == null) || (data.length <= 0)) return "00";
        BigInteger bi = new BigInteger(1, data);
        return bi.toString(16);
    }

    public final static byte[] fromHexString(String val) {
        BigInteger bv = new BigInteger(val, 16);
        return bv.toByteArray();
    }

    /** Read the stream for an integer as defined by the I2P data structure specification.
     * Integers are a fixed number of bytes (numBytes), stored as unsigned integers in network byte order.
     * @param rawStream stream to read from
     * @param numBytes number of bytes to read and format into a number
     * @throws DataFormatException if the stream doesn't contain a validly formatted number of that many bytes
     * @throws IOException if there is an IO error reading the number
     * @return number
     */
    public static long readLong(InputStream rawStream, int numBytes) 
        throws DataFormatException, IOException {
        if (numBytes > 8)
            throw new DataFormatException("readLong doesn't currently support reading numbers > 8 bytes [as thats bigger than java's long]");
        byte data[] = new byte[numBytes];
        int num = read(rawStream, data);
        if (num != numBytes)
            throw new DataFormatException("Not enough bytes [" + num + "] as required for the field [" + numBytes + "]");

        UnsignedInteger val = new UnsignedInteger(data);
        return val.getLong();
    }

    /** Write an integer as defined by the I2P data structure specification to the stream.
     * Integers are a fixed number of bytes (numBytes), stored as unsigned integers in network byte order.
     * @param value value to write out
     * @param rawStream stream to write to
     * @param numBytes number of bytes to write the number into (padding as necessary)
     * @throws DataFormatException if the stream doesn't contain a validly formatted number of that many bytes
     * @throws IOException if there is an IO error writing to the stream
     */
    public static void writeLong(OutputStream rawStream, int numBytes, long value) 
        throws DataFormatException, IOException {
        try {
            UnsignedInteger i = new UnsignedInteger(value);
            rawStream.write(i.getBytes(numBytes));
        } catch (IllegalArgumentException iae) {
            throw new DataFormatException("Invalid value (must be positive)", iae);
        }
    }

    /** Read in a date from the stream as specified by the I2P data structure spec.
     * A date is an 8 byte unsigned integer in network byte order specifying the number of
     * milliseconds since midnight on January 1, 1970 in the GMT timezone. If the number is
     * 0, the date is undefined or null. (yes, this means you can't represent midnight on 1/1/1970)
     * @param in stream to read from
     * @throws DataFormatException if the stream doesn't contain a validly formatted date
     * @throws IOException if there is an IO error reading the date
     * @return date read, or null
     */
    public static Date readDate(InputStream in) throws DataFormatException, IOException {
        long date = readLong(in, 8);
        if (date == 0L)
            return null;
        else
            return new Date(date);
    }

    /** Write out a date to the stream as specified by the I2P data structure spec.
     * @param out stream to write to
     * @param date date to write (can be null)
     * @throws DataFormatException if the date is not valid
     * @throws IOException if there is an IO error writing the date
     */
    public static void writeDate(OutputStream out, Date date) 
        throws DataFormatException, IOException {
        if (date == null)
            writeLong(out, 8, 0L);
        else
            writeLong(out, 8, date.getTime());
    }

    /** Read in a string from the stream as specified by the I2P data structure spec.
     * A string is 1 or more bytes where the first byte is the number of bytes (not characters!)
     * in the string and the remaining 0-255 bytes are the non-null terminated UTF-8 encoded character array.
     * @param in stream to read from
     * @throws DataFormatException if the stream doesn't contain a validly formatted string
     * @throws IOException if there is an IO error reading the string
     * @return UTF-8 string
     */
    public static String readString(InputStream in) throws DataFormatException, IOException {
        int size = (int) readLong(in, 1);
        byte raw[] = new byte[size];
        int read = read(in, raw);
        if (read != size) throw new DataFormatException("Not enough bytes to read the string");
        return new String(raw);
    }

    /** Write out a string to the stream as specified by the I2P data structure spec.  Note that the max
     * size for a string allowed by the spec is 255 bytes.
     *
     * @param out stream to write string
     * @param string string to write out: null strings are perfectly valid, but strings of excess length will
     *               cause a DataFormatException to be thrown
     * @throws DataFormatException if the string is not valid
     * @throws IOException if there is an IO error writing the string
     */
    public static void writeString(OutputStream out, String string) 
        throws DataFormatException, IOException {
        if (string == null) {
            writeLong(out, 1, 0);
        } else {
            if (string.length() > 255)
                throw new DataFormatException("The I2P data spec limits strings to 255 bytes or less, but this is "
                                              + string.length() + " [" + string + "]");
            byte raw[] = string.getBytes();
            writeLong(out, 1, raw.length);
            out.write(raw);
        }
    }

    /** Read in a boolean as specified by the I2P data structure spec.
     * A boolean is 1 byte that is either 0 (false), 1 (true), or 2 (null)
     * @param in stream to read from
     * @throws DataFormatException if the boolean is not valid
     * @throws IOException if there is an IO error reading the boolean
     * @return boolean value, or null
     */
    public static Boolean readBoolean(InputStream in) throws DataFormatException, IOException {
        int val = (int) readLong(in, 1);
        switch (val) {
        case 0:
            return Boolean.FALSE;
        case 1:
            return Boolean.TRUE;
        case 2:
            return null;
        default:
            throw new DataFormatException("Uhhh.. readBoolean read a value that isn't a known ternary val (0,1,2): "
                                          + val);
        }
    }

    /** Write out a boolean as specified by the I2P data structure spec.
     * A boolean is 1 byte that is either 0 (false), 1 (true), or 2 (null)
     * @param out stream to write to
     * @param bool boolean value, or null
     * @throws DataFormatException if the boolean is not valid
     * @throws IOException if there is an IO error writing the boolean
     */
    public static void writeBoolean(OutputStream out, Boolean bool) 
        throws DataFormatException, IOException {
        if (bool == null)
            writeLong(out, 1, 2);
        else if (Boolean.TRUE.equals(bool))
            writeLong(out, 1, 1);
        else
            writeLong(out, 1, 0);
    }

    //
    // The following comparator helpers make it simpler to write consistently comparing
    // functions for objects based on their value, not JVM memory address
    //

    /**
     * Helper util to compare two objects, including null handling.
     * <p />
     *
     * This treats (null == null) as true, and (null == (!null)) as false.
     */
    public final static boolean eq(Object lhs, Object rhs) {
        try {
            boolean eq = (((lhs == null) && (rhs == null)) || ((lhs != null) && (lhs.equals(rhs))));
            return eq;
        } catch (ClassCastException cce) {
            return false;
        }
    }

    /**
     * Run a deep comparison across the two collections.  
     * <p />
     *
     * This treats (null == null) as true, (null == (!null)) as false, and then 
     * comparing each element via eq(object, object). <p />
     *
     * If the size of the collections are not equal, the comparison returns false.
     * The collection order should be consistent, as this simply iterates across both and compares
     * based on the value of each at each step along the way.
     *
     */
    public final static boolean eq(Collection lhs, Collection rhs) {
        if ((lhs == null) && (rhs == null)) return true;
        if ((lhs == null) || (rhs == null)) return false;
        if (lhs.size() != rhs.size()) return false;
        Iterator liter = lhs.iterator();
        Iterator riter = rhs.iterator();
        while ((liter.hasNext()) && (riter.hasNext()))
            if (!(eq(liter.next(), riter.next()))) return false;
        return true;
    }

    /**
     * Run a comparison on the byte arrays, byte by byte.  <p />
     *
     * This treats (null == null) as true, (null == (!null)) as false, 
     * and unequal length arrays as false.
     *
     */
    public final static boolean eq(byte lhs[], byte rhs[]) {
        boolean eq = (((lhs == null) && (rhs == null)) || ((lhs != null) && (rhs != null) && (Arrays.equals(lhs, rhs))));
        return eq;
    }

    /**
     * Compare two integers, really just for consistency.
     */
    public final static boolean eq(int lhs, int rhs) {
        return lhs == rhs;
    }

    /**
     * Compare two longs, really just for consistency.
     */
    public final static boolean eq(long lhs, long rhs) {
        return lhs == rhs;
    }

    /**
     * Compare two bytes, really just for consistency.
     */
    public final static boolean eq(byte lhs, byte rhs) {
        return lhs == rhs;
    }

    public final static int compareTo(byte lhs[], byte rhs[]) {
        if ((rhs == null) && (lhs == null)) return 0;
        if (lhs == null) return -1;
        if (rhs == null) return 1;
        if (rhs.length < lhs.length) return 1;
        if (rhs.length > lhs.length) return -1;
        for (int i = 0; i < rhs.length; i++) {
            if (rhs[i] > lhs[i])
                return -1;
            else if (rhs[i] < lhs[i]) return 1;
        }
        return 0;
    }

    public final static byte[] xor(byte lhs[], byte rhs[]) {
        if ((lhs == null) || (rhs == null) || (lhs.length != rhs.length)) return null;
        byte diff[] = new byte[lhs.length];
        for (int i = 0; i < lhs.length; i++)
            diff[i] = (byte) (lhs[i] ^ rhs[i]);
        return diff;
    }

    //
    // The following hashcode helpers make it simpler to write consistently hashing
    // functions for objects based on their value, not JVM memory address
    //

    /**
     * Calculate the hashcode of the object, using 0 for null
     * 
     */
    public static int hashCode(Object obj) {
        if (obj == null)
            return 0;
        else
            return obj.hashCode();
    }

    /**
     * Calculate the hashcode of the date, using 0 for null
     * 
     */
    public static int hashCode(Date obj) {
        if (obj == null)
            return 0;
        else
            return (int) obj.getTime();
    }

    /**
     * Calculate the hashcode of the byte array, using 0 for null
     * 
     */
    public static int hashCode(byte b[]) {
        int rv = 0;
        if (b != null) {
            for (int i = 0; i < b.length && i < 8; i++)
                rv += b[i];
        }
        return rv;
    }

    /**
     * Calculate the hashcode of the collection, using 0 for null
     * 
     */
    public static int hashCode(Collection col) {
        if (col == null) return 0;
        int c = 0;
        for (Iterator iter = col.iterator(); iter.hasNext();)
            c = 7 * c + hashCode(iter.next());
        return c;
    }

    public static int read(InputStream in, byte target[]) throws IOException {
        int cur = 0;
        while (cur < target.length) {
            int numRead = in.read(target, cur, target.length - cur);
            if (numRead == -1) {
                if (cur == 0)
                    return -1; // throw new EOFException("EOF Encountered during reading");
                else
                    return cur;
            }
            cur += numRead;
        }
        return cur;
    }

    public static List sortStructures(Collection dataStructures) {
        if (dataStructures == null) return new ArrayList();
        ArrayList rv = new ArrayList(dataStructures.size());
        TreeMap tm = new TreeMap();
        for (Iterator iter = dataStructures.iterator(); iter.hasNext();) {
            DataStructure struct = (DataStructure) iter.next();
            tm.put(struct.calculateHash().toString(), struct);
        }
        for (Iterator iter = tm.keySet().iterator(); iter.hasNext();) {
            Object k = iter.next();
            rv.add(tm.get(k));
        }
        return rv;
    }

    public static String formatDuration(long ms) {
        if (ms < 30 * 1000) {
            return ms + "ms";
        } else if (ms < 5 * 60 * 1000) {
            return (ms / 1000) + "s";
        } else if (ms < 90 * 60 * 1000) {
            return (ms / (60 * 1000)) + "m";
        } else if (ms < 3 * 24 * 60 * 60 * 1000) {
            return (ms / (60 * 60 * 1000)) + "h";
        } else {
            return (ms / (24 * 60 * 60 * 1000)) + "d";
        }
    }

    /** compress the data and return a new GZIP compressed array */
    public static byte[] compress(byte orig[]) {
        if ((orig == null) || (orig.length <= 0)) return orig;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(orig.length);
            GZIPOutputStream out = new GZIPOutputStream(baos, orig.length);
            out.write(orig);
            out.finish();
            out.flush();
            byte rv[] = baos.toByteArray();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Compression of " + orig.length + " into " + rv.length + " (or " + 100.0d
            //               * (((double) orig.length) / ((double) rv.length)) + "% savings)");
            return rv;
        } catch (IOException ioe) {
            //_log.error("Error compressing?!", ioe);
            return null;
        }
    }

    /** decompress the GZIP compressed data (returning null on error) */
    public static byte[] decompress(byte orig[]) {
        if ((orig == null) || (orig.length <= 0)) return orig;
        try {
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(orig), orig.length);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(orig.length * 2);
            byte buf[] = new byte[4 * 1024];
            while (true) {
                int read = in.read(buf);
                if (read == -1) break;
                baos.write(buf, 0, read);
            }
            byte rv[] = baos.toByteArray();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Decompression of " + orig.length + " into " + rv.length + " (or " + 100.0d
            //               * (((double) rv.length) / ((double) orig.length)) + "% savings)");
            return rv;
        } catch (IOException ioe) {
            //_log.error("Error decompressing?", ioe);
            return null;
        }
    }
}