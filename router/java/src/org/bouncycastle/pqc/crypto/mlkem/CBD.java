package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.util.Pack;

class CBD
{
    static void eta2(Poly r, byte[] bytes)
    {
        for (int i = 0; i < MLKEMEngine.N / 8; i++)
        {
            int t = Pack.littleEndianToInt(bytes, 4 * i);
            int d = t & 0x55555555;
            d += (t >>> 1) & 0x55555555;
            for (int j = 0; j < 8; j++)
            {
                int a = (short)((d >>> (4 * j + 0)) & 0x3);
                int b = (short)((d >>> (4 * j + 2)) & 0x3);
                r.setCoeffIndex(8 * i + j, (short)(a - b));
            }
        }
    }

    static void eta3(Poly r, byte[] bytes)
    {
        for (int i = 0; i < MLKEMEngine.N / 4; i++)
        {
            int t = Pack.littleEndianToInt24(bytes, 3 * i);
            int d = t & 0x00249249;
            d += (t >>> 1) & 0x00249249;
            d += (t >>> 2) & 0x00249249;
            for (int j = 0; j < 4; j++)
            {
                int a = (short)((d >>> (6 * j + 0)) & 0x7);
                int b = (short)((d >>> (6 * j + 3)) & 0x7);
                r.setCoeffIndex(4 * i + j, (short)(a - b));
            }
        }
    }
}
