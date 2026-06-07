package org.bouncycastle.pqc.crypto.mlkem;

class PolyVec
{
    final Poly[] vec;

    PolyVec(int K)
    {
        this.vec = new Poly[K];
        for (int i = 0; i < K; i++)
        {
            vec[i] = new Poly();
        }
    }

    Poly getVectorIndex(int i)
    {
        return vec[i];
    }

    void polyVecNtt()
    {
        for (int i = 0; i < vec.length; i++)
        {
            vec[i].polyNtt();
        }
    }

    void polyVecInverseNttToMont()
    {
        for (int i = 0; i < vec.length; i++)
        {
            vec[i].polyInverseNttToMont();
        }
    }

    void compressPolyVec(byte[] rBuf, int rOff)
    {
        int pos = rOff;

        condSubQ();

        if (vec.length == 4)
        {
            // PolyVecCompressedBytes == K * 352

            short[] t = new short[8];
            for (int i = 0; i < vec.length; i++)
            {
                for (int j = 0; j < MLKEMEngine.N / 8; j++)
                {
                    for (int k = 0; k < 8; k++)
                    {
                        /*t[k] = (short)
                            (
                                (
                                    ((this.getVectorIndex(i).getCoeffIndex(8 * j + k) << 11)
                                        + (KyberEngine.KyberQ / 2))
                                        / KyberEngine.KyberQ)
                                    & 0x7ff);*/
                        // Fix for KyberSlash2: division by KyberQ above is not
                        // constant time.
                        long t_k = vec[i].getCoeffIndex(8 * j + k);
                        t_k <<= 11;
                        t_k += 1664;
                        t_k *= 645084;
                        t_k >>= 31;
                        t_k &= 0x7ff;
                        t[k] = (short)t_k;
                    }
                    rBuf[pos + 0] = (byte)((t[0] >> 0));
                    rBuf[pos + 1] = (byte)((t[0] >> 8) | (t[1] << 3));
                    rBuf[pos + 2] = (byte)((t[1] >> 5) | (t[2] << 6));
                    rBuf[pos + 3] = (byte)((t[2] >> 2));
                    rBuf[pos + 4] = (byte)((t[2] >> 10) | (t[3] << 1));
                    rBuf[pos + 5] = (byte)((t[3] >> 7) | (t[4] << 4));
                    rBuf[pos + 6] = (byte)((t[4] >> 4) | (t[5] << 7));
                    rBuf[pos + 7] = (byte)((t[5] >> 1));
                    rBuf[pos + 8] = (byte)((t[5] >> 9) | (t[6] << 2));
                    rBuf[pos + 9] = (byte)((t[6] >> 6) | (t[7] << 5));
                    rBuf[pos + 10] = (byte)((t[7] >> 3));
                    pos += 11;
                }
            }
        }
        else
        {
            // PolyVecCompressedBytes == K * 320

            short[] t = new short[4];
            for (int i = 0; i < vec.length; i++)
            {
                for (int j = 0; j < MLKEMEngine.N / 4; j++)
                {
                    for (int k = 0; k < 4; k++)
                    {
                        /*t[k] = (short)
                            (
                                (
                                    ((this.getVectorIndex(i).getCoeffIndex(4 * j + k) << 10)
                                        + (KyberEngine.KyberQ / 2))
                                        / KyberEngine.KyberQ)
                                    & 0x3ff);*/
                        // Fix for KyberSlash2: division by KyberQ above is not
                        // constant time.
                        long t_k = vec[i].getCoeffIndex(4 * j + k);
                        t_k <<= 10;
                        t_k += 1665;
                        t_k *= 1290167;
                        t_k >>= 32;
                        t_k &= 0x3ff;
                        t[k] = (short)t_k;
                    }
                    rBuf[pos + 0] = (byte)(t[0] >> 0);
                    rBuf[pos + 1] = (byte)((t[0] >> 8) | (t[1] << 2));
                    rBuf[pos + 2] = (byte)((t[1] >> 6) | (t[2] << 4));
                    rBuf[pos + 3] = (byte)((t[2] >> 4) | (t[3] << 6));
                    rBuf[pos + 4] = (byte)((t[3] >> 2));
                    pos += 5;
                }
            }
        }
    }

    void decompressPolyVec(byte[] cBuf, int cOff)
    {
        int pos = cOff;

        if (vec.length == 4)
        {
            // PolyVecCompressedBytes == K * 352
            
            short[] t = new short[8];
            for (int i = 0; i < vec.length; i++)
            {
                for (int j = 0; j < MLKEMEngine.N / 8; j++)
                {
                    t[0] = (short)(((cBuf[pos] & 0xFF) >> 0) | ((short)(cBuf[pos + 1] & 0xFF) << 8));
                    t[1] = (short)(((cBuf[pos + 1] & 0xFF) >> 3) | ((short)(cBuf[pos + 2] & 0xFF) << 5));
                    t[2] = (short)(((cBuf[pos + 2] & 0xFF) >> 6) | ((short)(cBuf[pos + 3] & 0xFF) << 2) | ((short)((cBuf[pos + 4] & 0xFF) << 10)));
                    t[3] = (short)(((cBuf[pos + 4] & 0xFF) >> 1) | ((short)(cBuf[pos + 5] & 0xFF) << 7));
                    t[4] = (short)(((cBuf[pos + 5] & 0xFF) >> 4) | ((short)(cBuf[pos + 6] & 0xFF) << 4));
                    t[5] = (short)(((cBuf[pos + 6] & 0xFF) >> 7) | ((short)(cBuf[pos + 7] & 0xFF) << 1) | ((short)((cBuf[pos + 8] & 0xFF) << 9)));
                    t[6] = (short)(((cBuf[pos + 8] & 0xFF) >> 2) | ((short)(cBuf[pos + 9] & 0xFF) << 6));
                    t[7] = (short)(((cBuf[pos + 9] & 0xFF) >> 5) | ((short)(cBuf[pos + 10] & 0xFF) << 3));
                    pos += 11;
                    for (int k = 0; k < 8; k++)
                    {
                        this.vec[i].setCoeffIndex(8 * j + k, (short)(((t[k] & 0x7FF) * MLKEMEngine.Q + 1024) >> 11));
                    }
                }
            }
        }
        else
        {
            // PolyVecCompressedBytes == K * 320

            short[] t = new short[4];
            for (int i = 0; i < vec.length; i++)
            {
                for (int j = 0; j < MLKEMEngine.N / 4; j++)
                {
                    t[0] = (short)(((cBuf[pos] & 0xFF) >> 0) | (short)((cBuf[pos + 1] & 0xFF) << 8));
                    t[1] = (short)(((cBuf[pos + 1] & 0xFF) >> 2) | (short)((cBuf[pos + 2] & 0xFF) << 6));
                    t[2] = (short)(((cBuf[pos + 2] & 0xFF) >> 4) | (short)((cBuf[pos + 3] & 0xFF) << 4));
                    t[3] = (short)(((cBuf[pos + 3] & 0xFF) >> 6) | (short)((cBuf[pos + 4] & 0xFF) << 2));
                    pos += 5;
                    for (int k = 0; k < 4; k++)
                    {
                        this.vec[i].setCoeffIndex(4 * j + k, (short)(((t[k] & 0x3FF) * MLKEMEngine.Q + 512) >> 10));
                    }
                }
            }
        }
    }

    static void pointwiseAccountMontgomery(Poly out, PolyVec inp1, PolyVec inp2, MLKEMEngine engine)
    {
        Poly t = new Poly();

        Poly.baseMultMontgomery(out, inp1.vec[0], inp2.vec[0]);
        for (int i = 1; i < engine.getK(); i++)
        {
            Poly.baseMultMontgomery(t, inp1.vec[i], inp2.vec[i]);
            out.add(t);
        }
        out.reduce();
    }

    void reducePoly()
    {
        for (int i = 0; i < vec.length; i++)
        {
            vec[i].reduce();
        }
    }

    void addPoly(PolyVec b)
    {
        for (int i = 0; i < vec.length; i++)
        {
            vec[i].add(b.vec[i]);
        }
    }

    void toBytes(byte[] buf, int off)
    {
        for (int i = 0; i < vec.length; i++)
        {
            vec[i].toBytes(buf, off + i * MLKEMEngine.PolyBytes);
        }
    }

    void fromBytes(byte[] buf, int off)
    {
        for (int i = 0; i < vec.length; i++)
        {
            vec[i].fromBytes(buf, off + i * MLKEMEngine.PolyBytes);
        }
    }

    private void condSubQ()
    {
        for (int i = 0; i < vec.length; i++)
        {
            vec[i].condSubQ();
        }
    }

    static int checkModulus(MLKEMEngine engine, byte[] inputBytes)
    {
        int result = -1;
        for (int i = 0, k = engine.getK(); i < k; i++)
        {
            result &= Poly.checkModulus(inputBytes, i * MLKEMEngine.PolyBytes);
        }
        return result;
    }
}
