package org.bouncycastle.pqc.crypto.mlkem;

final class CBD
{

    public static void mlkemCBD(Poly r, byte[] bytes, int eta)
    {
        long t, d;
        int a, b;

        switch (eta)
        {
        case 3:
            for (int i = 0; i < MLKEMEngine.KyberN / 4; i++)
            {
                t = convertByteTo24BitUnsignedInt(bytes, 3 * i);
                d = t & 0x00249249;
                d = d + ((t >> 1) & 0x00249249);
                d = d + ((t >> 2) & 0x00249249);
                for (int j = 0; j < 4; j++)
                {
                    a = (short)((d >> (6 * j + 0)) & 0x7);
                    b = (short)((d >> (6 * j + 3)) & 0x7);
                    // System.out.printf("a = %d, b = %d\n", a, b);
                    r.setCoeffIndex(4 * i + j, (short)(a - b));
                }
            }
            break;
        default:
            // Only for Kyber512 where eta = 2
            for (int i = 0; i < MLKEMEngine.KyberN / 8; i++)
            {
                t = convertByteTo32BitUnsignedInt(bytes, 4 * i); // ? Problem
                d = t & 0x55555555;
                d = d + ((t >> 1) & 0x55555555);
                for (int j = 0; j < 8; j++)
                {
                    a = (short)((d >> (4 * j + 0)) & 0x3);
                    b = (short)((d >> (4 * j + eta)) & 0x3);
                    r.setCoeffIndex(8 * i + j, (short)(a - b));
                }
            }
        }
    }

    /**
     * Converts an Array of Bytes to a 32-bit Unsigned Integer
     * Returns a 32-bit unsigned integer as a long
     *
     * @param x
     * @return
     */
    private static long convertByteTo32BitUnsignedInt(byte[] x, int offset)
    {
        // Convert first byte to an unsigned integer 
        // byte x & 0xFF allows us to grab the last 8 bits
        long r = (long)(x[offset] & 0xFF);

        // Perform the same operation then left bit shift to store the next 8 bits without
        // altering the previous bits
        r = r | (long)((long)(x[offset + 1] & 0xFF) << 8);
        r = r | (long)((long)(x[offset + 2] & 0xFF) << 16);
        r = r | (long)((long)(x[offset + 3] & 0xFF) << 24);
        return r;
    }

    /**
     * Converts an Array of Bytes to a 24-bit Unsigned Integer
     * Returns a 24-bit unsigned integer as a long from byte x
     *
     * @param x
     * @return
     */
    private static long convertByteTo24BitUnsignedInt(byte[] x, int offset)
    {
        // Refer to convertByteTo32-BitUnsignedInt for explanation
        long r = (long)(x[offset] & 0xFF);
        r = r | (long)((long)(x[offset + 1] & 0xFF) << 8);
        r = r | (long)((long)(x[offset + 2] & 0xFF) << 16);
        return r;
    }


}
