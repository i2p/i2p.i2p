package org.bouncycastle.util;

/**
 * Utility methods for converting byte arrays into ints and longs, and back again.
 */
public abstract class Pack
{
    public static short bigEndianToShort(byte[] bs, int off)
    {
        int n = (bs[off] & 0xff) << 8;
        n |= (bs[++off] & 0xff);
        return (short)n;
    }

    public static int bigEndianToInt(byte[] bs, int off)
    {
        int n = bs[off] << 24;
        n |= (bs[++off] & 0xff) << 16;
        n |= (bs[++off] & 0xff) << 8;
        n |= (bs[++off] & 0xff);
        return n;
    }

    public static void bigEndianToInt(byte[] bs, int off, int[] ns)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = bigEndianToInt(bs, off);
            off += 4;
        }
    }

    public static void bigEndianToInt(byte[] bs, int off, int[] ns, int nsOff, int nsLen)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            ns[nsOff + i] = bigEndianToInt(bs, off);
            off += 4;
        }
    }

    public static byte[] intToBigEndian(int n)
    {
        byte[] bs = new byte[4];
        intToBigEndian(n, bs, 0);
        return bs;
    }

    public static void intToBigEndian(int n, byte[] bs, int off)
    {
        bs[off] = (byte)(n >>> 24);
        bs[++off] = (byte)(n >>> 16);
        bs[++off] = (byte)(n >>> 8);
        bs[++off] = (byte)(n);
    }

    public static byte[] intToBigEndian(int[] ns)
    {
        byte[] bs = new byte[4 * ns.length];
        intToBigEndian(ns, bs, 0);
        return bs;
    }

    public static void intToBigEndian(int[] ns, byte[] bs, int off)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            intToBigEndian(ns[i], bs, off);
            off += 4;
        }
    }

    public static void intToBigEndian(int[] ns, int nsOff, int nsLen, byte[] bs, int bsOff)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            intToBigEndian(ns[nsOff + i], bs, bsOff);
            bsOff += 4;
        }
    }

    public static long bigEndianToLong(byte[] bs, int off)
    {
        int hi = bigEndianToInt(bs, off);
        int lo = bigEndianToInt(bs, off + 4);
        return ((long)(hi & 0xffffffffL) << 32) | (long)(lo & 0xffffffffL);
    }

    public static void bigEndianToLong(byte[] bs, int off, long[] ns)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = bigEndianToLong(bs, off);
            off += 8;
        }
    }

    public static void bigEndianToLong(byte[] bs, int bsOff, long[] ns, int nsOff, int nsLen)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            ns[nsOff + i] = bigEndianToLong(bs, bsOff);
            bsOff += 8;
        }
    }

    public static long bigEndianToLong(byte[] bs, int off, int len)
    {
        long x = 0;
        for (int i = 0; i < len; ++i)
        {
            x |= (bs[i + off] & 0xFFL) << ((7 - i) << 3);
        }
        return x;
    }

    public static byte[] longToBigEndian(long n)
    {
        byte[] bs = new byte[8];
        longToBigEndian(n, bs, 0);
        return bs;
    }

    public static void longToBigEndian(long n, byte[] bs, int off)
    {
        intToBigEndian((int)(n >>> 32), bs, off);
        intToBigEndian((int)(n & 0xffffffffL), bs, off + 4);
    }

    public static byte[] longToBigEndian(long[] ns)
    {
        byte[] bs = new byte[8 * ns.length];
        longToBigEndian(ns, bs, 0);
        return bs;
    }

    public static void longToBigEndian(long[] ns, byte[] bs, int off)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            longToBigEndian(ns[i], bs, off);
            off += 8;
        }
    }

    public static void longToBigEndian(long[] ns, int nsOff, int nsLen, byte[] bs, int bsOff)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            longToBigEndian(ns[nsOff + i], bs, bsOff);
            bsOff += 8;
        }
    }

    /**
     * @param value The number
     * @param bs    The target.
     * @param off   Position in target to start.
     * @param bytes number of bytes to write.
     * @deprecated Will be removed
     */
    public static void longToBigEndian(long value, byte[] bs, int off, int bytes)
    {
        for (int i = bytes - 1; i >= 0; i--)
        {
            bs[i + off] = (byte)(value & 0xff);
            value >>>= 8;
        }
    }

    public static short littleEndianToShort(byte[] bs, int off)
    {
        int n = bs[off] & 0xff;
        n |= (bs[++off] & 0xff) << 8;
        return (short)n;
    }

    public static int littleEndianToInt(byte[] bs, int off)
    {
        int n = bs[off] & 0xff;
        n |= (bs[++off] & 0xff) << 8;
        n |= (bs[++off] & 0xff) << 16;
        n |= bs[++off] << 24;
        return n;
    }

    public static int littleEndianToInt_High(byte[] bs, int off, int len)
    {
        return littleEndianToInt_Low(bs, off, len) << ((4 - len) << 3);
    }

    public static int littleEndianToInt_Low(byte[] bs, int off, int len)
    {
//        assert 1 <= len && len <= 4;

        int result = bs[off] & 0xff;
        int pos = 0;
        for (int i = 1; i < len; ++i)
        {
            pos += 8;
            result |= (bs[off + i] & 0xff) << pos;
        }
        return result;
    }

    public static void littleEndianToInt(byte[] bs, int off, int[] ns)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = littleEndianToInt(bs, off);
            off += 4;
        }
    }

    public static void littleEndianToInt(byte[] bs, int bOff, int[] ns, int nOff, int count)
    {
        for (int i = 0; i < count; ++i)
        {
            ns[nOff + i] = littleEndianToInt(bs, bOff);
            bOff += 4;
        }
    }

    public static int[] littleEndianToInt(byte[] bs, int off, int count)
    {
        int[] ns = new int[count];
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = littleEndianToInt(bs, off);
            off += 4;
        }
        return ns;
    }

    public static byte[] shortToLittleEndian(short n)
    {
        byte[] bs = new byte[2];
        shortToLittleEndian(n, bs, 0);
        return bs;
    }

    public static void shortToLittleEndian(short n, byte[] bs, int off)
    {
        bs[off] = (byte)(n);
        bs[++off] = (byte)(n >>> 8);
    }


    public static byte[] shortToBigEndian(short n)
    {
        byte[] r = new byte[2];
        shortToBigEndian(n, r, 0);
        return r;
    }

    public static void shortToBigEndian(short n, byte[] bs, int off)
    {
        bs[off] = (byte)(n >>> 8);
        bs[++off] = (byte)(n);
    }


    public static byte[] intToLittleEndian(int n)
    {
        byte[] bs = new byte[4];
        intToLittleEndian(n, bs, 0);
        return bs;
    }

    public static void intToLittleEndian(int n, byte[] bs, int off)
    {
        bs[off] = (byte)(n);
        bs[++off] = (byte)(n >>> 8);
        bs[++off] = (byte)(n >>> 16);
        bs[++off] = (byte)(n >>> 24);
    }

    public static byte[] intToLittleEndian(int[] ns)
    {
        byte[] bs = new byte[4 * ns.length];
        intToLittleEndian(ns, bs, 0);
        return bs;
    }

    public static void intToLittleEndian(int[] ns, byte[] bs, int off)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            intToLittleEndian(ns[i], bs, off);
            off += 4;
        }
    }

    public static void intToLittleEndian(int[] ns, int nsOff, int nsLen, byte[] bs, int bsOff)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            intToLittleEndian(ns[nsOff + i], bs, bsOff);
            bsOff += 4;
        }
    }

    public static long littleEndianToLong(byte[] bs, int off)
    {
        int lo = littleEndianToInt(bs, off);
        int hi = littleEndianToInt(bs, off + 4);
        return ((long)(hi & 0xffffffffL) << 32) | (long)(lo & 0xffffffffL);
    }

    public static void littleEndianToLong(byte[] bs, int off, long[] ns)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = littleEndianToLong(bs, off);
            off += 8;
        }
    }

    public static long littleEndianToLong(byte[] input, int off, int len)
    {
        long result = 0;
        for (int i = 0; i < len; ++i)
        {
            result |= (input[off + i] & 0xFFL) << (i << 3);
        }
        return result;
    }

    public static void littleEndianToLong(byte[] bs, int bsOff, long[] ns, int nsOff, int nsLen)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            ns[nsOff + i] = littleEndianToLong(bs, bsOff);
            bsOff += 8;
        }
    }

    public static void longToLittleEndian_High(long n, byte[] bs, int off, int len)
    {
        //Debug.Assert(1 <= len && len <= 8);
        int pos = 56;
        bs[off] = (byte)(n >>> pos);
        for (int i = 1; i < len; ++i)
        {
            pos -= 8;
            bs[off + i] = (byte)(n >>> pos);
        }
    }

    public static void longToLittleEndian(long n, byte[] bs, int off, int len)
    {
        for (int i = 0; i < len; ++i)
        {
            bs[off + i] = (byte)(n >>> (i << 3));
        }
    }

//    public static void longToLittleEndian_Low(long n, byte[] bs, int off, int len)
//    {
//        longToLittleEndian_High(n << ((8 - len) << 3), bs, off, len);
//    }

    public static long littleEndianToLong_High(byte[] bs, int off, int len)
    {
        return littleEndianToLong_Low(bs, off, len) << ((8 - len) << 3);
    }

    public static long littleEndianToLong_Low(byte[] bs, int off, int len)
    {
        //Debug.Assert(1 <= len && len <= 8);
        long result = bs[off] & 0xFF;
        for (int i = 1; i < len; ++i)
        {
            result <<= 8;
            result |= bs[off + i] & 0xFF;
        }
        return result;
    }

    public static byte[] longToLittleEndian(long n)
    {
        byte[] bs = new byte[8];
        longToLittleEndian(n, bs, 0);
        return bs;
    }

    public static void longToLittleEndian(long n, byte[] bs, int off)
    {
        intToLittleEndian((int)(n & 0xffffffffL), bs, off);
        intToLittleEndian((int)(n >>> 32), bs, off + 4);
    }

    public static byte[] longToLittleEndian(long[] ns)
    {
        byte[] bs = new byte[8 * ns.length];
        longToLittleEndian(ns, bs, 0);
        return bs;
    }

    public static void longToLittleEndian(long[] ns, byte[] bs, int off)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            longToLittleEndian(ns[i], bs, off);
            off += 8;
        }
    }

    public static void longToLittleEndian(long[] ns, int nsOff, int nsLen, byte[] bs, int bsOff)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            longToLittleEndian(ns[nsOff + i], bs, bsOff);
            bsOff += 8;
        }
    }


}
