package org.bouncycastle.pqc.crypto.mlkem;

class Ntt
{

    public static final short[] nttZetas = new short[]{
        2285, 2571, 2970, 1812, 1493, 1422, 287, 202, 3158, 622, 1577, 182, 962,
        2127, 1855, 1468, 573, 2004, 264, 383, 2500, 1458, 1727, 3199, 2648, 1017,
        732, 608, 1787, 411, 3124, 1758, 1223, 652, 2777, 1015, 2036, 1491, 3047,
        1785, 516, 3321, 3009, 2663, 1711, 2167, 126, 1469, 2476, 3239, 3058, 830,
        107, 1908, 3082, 2378, 2931, 961, 1821, 2604, 448, 2264, 677, 2054, 2226,
        430, 555, 843, 2078, 871, 1550, 105, 422, 587, 177, 3094, 3038, 2869, 1574,
        1653, 3083, 778, 1159, 3182, 2552, 1483, 2727, 1119, 1739, 644, 2457, 349,
        418, 329, 3173, 3254, 817, 1097, 603, 610, 1322, 2044, 1864, 384, 2114, 3193,
        1218, 1994, 2455, 220, 2142, 1670, 2144, 1799, 2051, 794, 1819, 2475, 2459,
        478, 3221, 3021, 996, 991, 958, 1869, 1522, 1628};

    public static final short[] nttZetasInv = new short[]{
        1701, 1807, 1460, 2371, 2338, 2333, 308, 108, 2851, 870, 854, 1510, 2535,
        1278, 1530, 1185, 1659, 1187, 3109, 874, 1335, 2111, 136, 1215, 2945, 1465,
        1285, 2007, 2719, 2726, 2232, 2512, 75, 156, 3000, 2911, 2980, 872, 2685,
        1590, 2210, 602, 1846, 777, 147, 2170, 2551, 246, 1676, 1755, 460, 291, 235,
        3152, 2742, 2907, 3224, 1779, 2458, 1251, 2486, 2774, 2899, 1103, 1275, 2652,
        1065, 2881, 725, 1508, 2368, 398, 951, 247, 1421, 3222, 2499, 271, 90, 853,
        1860, 3203, 1162, 1618, 666, 320, 8, 2813, 1544, 282, 1838, 1293, 2314, 552,
        2677, 2106, 1571, 205, 2918, 1542, 2721, 2597, 2312, 681, 130, 1602, 1871,
        829, 2946, 3065, 1325, 2756, 1861, 1474, 1202, 2367, 3147, 1752, 2707, 171,
        3127, 3042, 1907, 1836, 1517, 359, 758, 1441};

    public static short[] ntt(short[] inp)
    {
        short[] r = new short[MLKEMEngine.KyberN];
        System.arraycopy(inp, 0, r, 0, r.length);
        int len, start, j, k;
        short t, zeta;

        k = 1;
        for (len = 128; len >= 2; len >>= 1)
        {
            for (start = 0; start < 256; start = j + len)
            {
                zeta = nttZetas[k++];
                for (j = start; j < start + len; ++j)
                {
                    t = factorQMulMont(zeta, r[j + len]);
                    r[j + len] = (short)(r[j] - t);
                    r[j] = (short)(r[j] + t);
                }
            }
        }
        return r;
    }

    public static short[] invNtt(short[] inp)
    {
        short[] r = new short[MLKEMEngine.KyberN];
        System.arraycopy(inp, 0, r, 0, MLKEMEngine.KyberN);
        int len, start, j, k;
        short t, zeta;
        k = 0;
        for (len = 2; len <= 128; len <<= 1)
        {
            for (start = 0; start < 256; start = j + len)
            {
                zeta = nttZetasInv[k++];
                for (j = start; j < start + len; ++j)
                {
                    t = r[j];
                    r[j] = Reduce.barretReduce((short)(t + r[j + len]));
                    r[j + len] = (short)(t - r[j + len]);
                    r[j + len] = factorQMulMont(zeta, r[j + len]);

                }
            }
        }

        for (j = 0; j < 256; ++j)
        {
            r[j] = factorQMulMont(r[j], Ntt.nttZetasInv[127]);
        }
        return r;
    }

    public static short factorQMulMont(short a, short b)
    {
        return Reduce.montgomeryReduce((int)(a * b));
    }

    public static void baseMult(Poly outPoly, int outIndex, short a0, short a1, short b0, short b1, short zeta)
    {
        short outVal0 = factorQMulMont(a1, b1);
        outVal0 = factorQMulMont(outVal0, zeta);
        outVal0 += factorQMulMont(a0, b0);
        outPoly.setCoeffIndex(outIndex, outVal0);

        short outVal1 = factorQMulMont(a0, b1);
        outVal1 += factorQMulMont(a1, b0);
        outPoly.setCoeffIndex(outIndex + 1, outVal1);
    }
}