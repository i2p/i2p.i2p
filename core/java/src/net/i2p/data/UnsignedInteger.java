package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;

import net.i2p.util.Log;

/**
 * Manage an arbitrarily large unsigned integer, using the first bit and first byte
 * as the most significant one.  Also allows the exporting to byte arrays with whatever
 * padding is requested.
 *
 * WARNING: Range is currently limited to 0 through 2^63-1, due to Java's two's complement
 *          format.  Fix when we need it.
 *
 * @author jrandom
 */
public class UnsignedInteger {
    private final static Log _log = new Log(UnsignedInteger.class);
    private byte[] _data;
    private long _value;

    /**
     * Construct the integer from the bytes given, making the value accessible 
     * immediately.
     *
     * @param data unsigned number in network byte order (first bit, first byte 
     *          is the most significant)
     */
    public UnsignedInteger(byte[] data) {
        // strip off excess bytes
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                start++;
            } else {
                break;
            }
        }
        _data = new byte[data.length - start];
        for (int i = 0; i < _data.length; i++)
            _data[i] = data[i + start];
        // done stripping excess bytes, now calc
        _value = calculateValue(_data);
    }

    /**
     * Construct the integer with the java number given, making the bytes
     * available immediately.
     *
     * @param value number to represent
     * @throws IllegalArgumentException if the value is negative
     */
    public UnsignedInteger(long value) throws IllegalArgumentException {
        _value = value;
        _data = calculateBytes(value);
    }

    /**
     * Calculate the value of the array of bytes, treating it as an unsigned integer 
     * with the most significant bit and byte first
     *
     */
    private static long calculateValue(byte[] data) {
        if (data == null) {
            _log.error("Null data to be calculating for", new Exception("Argh"));
            return 0;
        } else if (data.length == 0) { return 0; }
        BigInteger bi = new BigInteger(1, data);
        return bi.longValue();
    }

    /**
     * hexify the byte array
     *
     */
    private final static String toString(byte[] val) {
        return "0x" + DataHelper.toString(val, val.length);
    }

    /**
     * Calculate the bytes as an unsigned integer with the most significant 
     * bit and byte in the first position.  The return value always has at least
     * one byte in it.
     *
     * @throws IllegalArgumentException if the value is negative
     */
    private static byte[] calculateBytes(long value) throws IllegalArgumentException {
        if (value < 0) 
            throw new IllegalArgumentException("unsigned integer, and you want a negative? " + value);
        byte val[] = new byte[8];
        val[0] = (byte)(value >>> 56);
        val[1] = (byte)(value >>> 48);
        val[2] = (byte)(value >>> 40);
        val[3] = (byte)(value >>> 32);
        val[4] = (byte)(value >>> 24);
        val[5] = (byte)(value >>> 16);
        val[6] = (byte)(value >>> 8);
        val[7] = (byte)value;
        
        int firstNonZero = -1;
        for (int i = 0; i < val.length; i++) {
            if (val[i] != 0x00) {
                firstNonZero = i;
                break;
            }
        }
        
        if (firstNonZero == 0) 
            return val; 
        if (firstNonZero == -1)
            return new byte[1]; // initialized as 0
        
        byte rv[] = new byte[8-firstNonZero];
        System.arraycopy(val, firstNonZero, rv, 0, rv.length);
        return rv;
        /*
        BigInteger bi = new BigInteger("" + value);
        byte buf[] = bi.toByteArray();
        if ((buf == null) || (buf.length <= 0))
            throw new IllegalArgumentException("Value [" + value + "] cannot be transformed");
        int trim = 0;
        while ((trim < buf.length) && (buf[trim] == 0x00))
            trim++;
        byte rv[] = new byte[buf.length - trim];
        System.arraycopy(buf, trim, rv, 0, rv.length);
        return rv;
         */
    }

    /**
     * Get the unsigned bytes, most significant bit and bytes first, without any padding
     *
     */
    public byte[] getBytes() {
        return _data;
    }

    /**
     * Get the unsigned bytes, most significant bit and bytes first, zero padded to the
     * specified number of bytes
     *
     * @throws IllegalArgumentException if numBytes < necessary number of bytes
     */
    public byte[] getBytes(int numBytes) throws IllegalArgumentException {
        if ((_data == null) || (numBytes < _data.length))
            throw new IllegalArgumentException("Value (" + _value + ") is greater than the requested number of bytes ("
                                               + numBytes + ")");

        if (numBytes == _data.length) return _data;
        
        byte[] data = new byte[numBytes];
        System.arraycopy(_data, 0, data, numBytes - _data.length, _data.length);
        return data;
    }

    public BigInteger getBigInteger() {
        return new BigInteger(1, _data);
    }

    public long getLong() {
        return _value;
    }

    public int getInt() {
        return (int) _value;
    }

    public short getShort() {
        return (short) _value;
    }

    public boolean equals(Object obj) {
        if ((obj != null) && (obj instanceof UnsignedInteger)) {
            return DataHelper.eq(_data, ((UnsignedInteger) obj)._data)
                   && DataHelper.eq(_value, ((UnsignedInteger) obj)._value);
        } else {
            return false;
        }
    }

    public int hashCode() {
        return DataHelper.hashCode(_data) + (int) _value;
    }

    public String toString() {
        return "UnsignedInteger: " + getLong() + "/" + toString(getBytes());
    }

    public static void main(String args[]) {
        _log.debug("Testing 1024");
        testNum(1024L);
        _log.debug("Testing 1025");
        testNum(1025L);
        _log.debug("Testing 2Gb-1");
        testNum(1024 * 1024 * 1024 * 2L - 1L);
        _log.debug("Testing 4Gb-1");
        testNum(1024 * 1024 * 1024 * 4L - 1L);
        _log.debug("Testing 4Gb");
        testNum(1024 * 1024 * 1024 * 4L);
        _log.debug("Testing 4Gb+1");
        testNum(1024 * 1024 * 1024 * 4L + 1L);
        _log.debug("Testing MaxLong");
        testNum(Long.MAX_VALUE);
        try {
            Thread.sleep(1000);
        } catch (Throwable t) {
        }
    }

    private static void testNum(long num) {
        UnsignedInteger i = new UnsignedInteger(num);
        _log.debug(num + " turned into an unsigned integer: " + i + " (" + i.getLong() + "/" + toString(i.getBytes())
                   + ")");
        _log.debug(num + " turned into an BigInteger: " + i.getBigInteger());
        byte[] val = i.getBytes();
        UnsignedInteger val2 = new UnsignedInteger(val);
        _log.debug(num + " turned into a byte array and back again: " + val2 + " (" + val2.getLong() + "/"
                   + toString(val2.getBytes()) + ")");
        _log.debug(num + " As an 8 byte array: " + toString(val2.getBytes(8)));
        BigInteger bi = new BigInteger(num+"");
        _log.debug(num + " As a bigInteger: 0x" + bi.toString(16));
        BigInteger tbi = new BigInteger(1, calculateBytes(num));
        _log.debug(num + " As a shifted   : 0x" + tbi.toString(16));
    }
}