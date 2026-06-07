package org.bouncycastle.pqc.crypto.mlkem;

class Reduce
{
    static short montgomeryReduce(int a)
    {
        short u = (short)(a * MLKEMEngine.Qinv);
        int t = (int)(u * MLKEMEngine.Q);
        t = a - t;
        t >>= 16;
        return (short)t;
    }

    static short barrettReduce(short a)
    {
        short v = (short)(((1L << 26) + (MLKEMEngine.Q / 2)) / MLKEMEngine.Q);
        short t = (short)((v * a) >> 26);
        t = (short)(t * MLKEMEngine.Q);
        return (short)(a - t);
    }

    static short condSubQ(short a)
    {
        a -= MLKEMEngine.Q;
        a += (a >> 15) & MLKEMEngine.Q;
        return a;
    }

    // NB: We only care about the sign bit of the result: it will be 1 iff the argument was in range
    static int checkModulus(short a)
    {
        return a - MLKEMEngine.Q;
    }
}
