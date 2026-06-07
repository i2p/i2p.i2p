package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.crypto.Xof;

class Poly
{
    private final short[] coeffs = new short[MLKEMEngine.N];

    short getCoeffIndex(int i)
    {
        return coeffs[i];
    }

    short[] getCoeffs()
    {
        return coeffs;
    }

    void setCoeffIndex(int i, short val)
    {
        coeffs[i] = val;
    }

    void polyNtt()
    {
        Ntt.ntt(coeffs);
        reduce();
    }

    void polyInverseNttToMont()
    {
        Ntt.invNtt(coeffs);
    }

    void reduce()
    {
        for (int i = 0; i < MLKEMEngine.N; i++)
        {
            coeffs[i] = Reduce.barrettReduce(coeffs[i]);
        }
    }

    static void baseMultMontgomery(Poly r, Poly a, Poly b)
    {
        for (int i = 0; i < MLKEMEngine.N / 4; i++)
        {
            Ntt.baseMult(r.coeffs, 4 * i,
                a.getCoeffIndex(4 * i), a.getCoeffIndex(4 * i + 1),
                b.getCoeffIndex(4 * i), b.getCoeffIndex(4 * i + 1),
                Ntt.ZETAS[64 + i]);
            Ntt.baseMult(r.coeffs, 4 * i + 2,
                a.getCoeffIndex(4 * i + 2), a.getCoeffIndex(4 * i + 3),
                b.getCoeffIndex(4 * i + 2), b.getCoeffIndex(4 * i + 3),
                (short)(-1 * Ntt.ZETAS[64 + i]));
        }
    }

    void add(Poly b)
    {
        for (int i = 0; i < MLKEMEngine.N; i++)
        {
            coeffs[i] = (short)(coeffs[i] + b.coeffs[i]);
        }
    }

    void convertToMont()
    {
        final short f = (short)((1L << 32) % MLKEMEngine.Q);
        for (int i = 0; i < MLKEMEngine.N; i++)
        {
            this.setCoeffIndex(i, Reduce.montgomeryReduce(this.getCoeffIndex(i) * f));
        }
    }

    byte[] compressPoly128()
    {
        byte[] t = new byte[8];
        byte[] r = new byte[128];
        int count = 0;

        condSubQ();

        for (int i = 0; i < MLKEMEngine.N / 8; i++)
        {
            for (int j = 0; j < 8; j++)
            {
                /*t[j] =
                    (byte)((((((short)this.getCoeffIndex(8 * i + j)) << 4)
                        +
                        (KyberEngine.KyberQ / 2)
                    ) / KyberEngine.KyberQ)
                        & 15);*/
                // Fix for KyberSlash2: division by KyberQ above is not
                // constant time.
                int t_j = this.getCoeffIndex(8 * i + j);
                t_j <<= 4;
                t_j += 1665;
                t_j *= 80635;
                t_j >>= 28;
                t_j &= 15;
                t[j] = (byte)t_j;
            }

            r[count + 0] = (byte)(t[0] | (t[1] << 4));
            r[count + 1] = (byte)(t[2] | (t[3] << 4));
            r[count + 2] = (byte)(t[4] | (t[5] << 4));
            r[count + 3] = (byte)(t[6] | (t[7] << 4));
            count += 4;
        }

        return r;
    }

    byte[] compressPoly160()
    {
        byte[] t = new byte[8];
        byte[] r = new byte[160];
        int count = 0;

        condSubQ();

        for (int i = 0; i < MLKEMEngine.N / 8; i++)
        {
            for (int j = 0; j < 8; j++)
            {
                /*t[j] =
                    (byte)(((((this.getCoeffIndex(8 * i + j) << 5))
                        +
                        (KyberEngine.KyberQ / 2)
                    ) / KyberEngine.KyberQ
                    ) & 31
                    );*/
                // Fix for KyberSlash2: division by KyberQ above is not
                // constant time.
                int t_j = this.getCoeffIndex(8 * i + j);
                t_j <<= 5;
                t_j += 1664;
                t_j *= 40318;
                t_j >>= 27;
                t_j &= 31;
                t[j] = (byte)t_j;
            }
            r[count + 0] = (byte)((t[0] >> 0) | (t[1] << 5));
            r[count + 1] = (byte)((t[1] >> 3) | (t[2] << 2) | (t[3] << 7));
            r[count + 2] = (byte)((t[3] >> 1) | (t[4] << 4));
            r[count + 3] = (byte)((t[4] >> 4) | (t[5] << 1) | (t[6] << 6));
            r[count + 4] = (byte)((t[6] >> 2) | (t[7] << 3));
            count += 5;
        }

        return r;
    }

    void decompressPoly128(byte[] cBuf, int cOff)
    {
        int pos = cOff;
        for (int i = 0; i < MLKEMEngine.N / 2; i++)
        {
            this.setCoeffIndex(2 * i + 0, (short)((((short)((cBuf[pos] & 0xFF) & 15) * MLKEMEngine.Q) + 8) >> 4));
            this.setCoeffIndex(2 * i + 1, (short)((((short)((cBuf[pos] & 0xFF) >> 4) * MLKEMEngine.Q) + 8) >> 4));
            pos += 1;
        }
    }

    void decompressPoly160(byte[] cBuf, int cOff)
    {
        int pos = cOff;

        byte[] t = new byte[8];
        for (int i = 0; i < MLKEMEngine.N / 8; i++)
        {
            t[0] = (byte)((cBuf[pos + 0] & 0xFF) >> 0);
            t[1] = (byte)(((cBuf[pos + 0] & 0xFF) >> 5) | ((cBuf[pos + 1] & 0xFF) << 3));
            t[2] = (byte)((cBuf[pos + 1] & 0xFF) >> 2);
            t[3] = (byte)(((cBuf[pos + 1] & 0xFF) >> 7) | ((cBuf[pos + 2] & 0xFF) << 1));
            t[4] = (byte)(((cBuf[pos + 2] & 0xFF) >> 4) | ((cBuf[pos + 3] & 0xFF) << 4));
            t[5] = (byte)((cBuf[pos + 3] & 0xFF) >> 1);
            t[6] = (byte)(((cBuf[pos + 3] & 0xFF) >> 6) | ((cBuf[pos + 4] & 0xFF) << 2));
            t[7] = (byte)((cBuf[pos + 4] & 0xFF) >> 3);
            pos += 5;
            for (int j = 0; j < 8; j++)
            {
                this.setCoeffIndex(8 * i + j, (short)(((t[j] & 31) * MLKEMEngine.Q + 16) >> 5));
            }
        }
    }

    void toBytes(byte[] r, int off)
    {
        condSubQ();

        for (int i = 0; i < MLKEMEngine.N / 2; i++)
        {
            short t0 = coeffs[2 * i + 0];
            short t1 = coeffs[2 * i + 1];
            r[off + 3 * i + 0] = (byte)(t0 >> 0);
            r[off + 3 * i + 1] = (byte)((t0 >> 8) | (t1 << 4));
            r[off + 3 * i + 2] = (byte)(t1 >> 4);
        }
    }

    void fromBytes(byte[] inpBytes, int inOff)
    {
        for (int i = 0; i < MLKEMEngine.N / 2; ++i)
        {
            int index = inOff + (3 * i);
            int a0 = inpBytes[index + 0] & 0xFF;
            int a1 = inpBytes[index + 1] & 0xFF;
            int a2 = inpBytes[index + 2] & 0xFF;
            coeffs[2 * i + 0] = (short)(((a0 >> 0) | (a1 << 8)) & 0xFFF);
            coeffs[2 * i + 1] = (short)(((a1 >> 4) | (a2 << 4)) & 0xFFF);
        }
    }

    void toMsg(byte[] msg)
    {
        int LOWER = MLKEMEngine.Q >>> 2;
        int UPPER = MLKEMEngine.Q - LOWER;

        condSubQ();

        for (int i = 0; i < MLKEMEngine.N / 8; i++)
        {
            msg[i] = 0;
            for (int j = 0; j < 8; j++)
            {
                int c_j = this.getCoeffIndex(8 * i + j);

                // KyberSlash: division by Q is not constant time.
//                int t = (((c_j << 1) + (KyberEngine.KyberQ / 2)) / KyberEngine.KyberQ) & 1;
                int t = ((LOWER - c_j) & (c_j - UPPER)) >>> 31;

                msg[i] |= (byte)(t << j);
            }
        }
    }

    void fromMsg(byte[] msg, int msgOff)
    {
        for (int i = 0; i < MLKEMEngine.N / 8; i++)
        {
            int msg_i = msg[msgOff + i] & 0xFF;
            for (int j = 0; j < 8; j++)
            {
                short mask = (short)-((msg_i >> j) & 1);
                this.setCoeffIndex(8 * i + j, (short)(mask & (short)((MLKEMEngine.Q + 1) / 2)));
            }
        }
    }

    void condSubQ()
    {
        for (int i = 0; i < MLKEMEngine.N; i++)
        {
            coeffs[i] = Reduce.condSubQ(coeffs[i]);
        }
    }

    void getNoiseEta2(Xof xof, byte[] seed, int seedOff, byte nonce)
    {
        byte[] buf = new byte[2 * MLKEMEngine.N / 4];
        prf(xof, seed, seedOff, nonce, buf);
        CBD.eta2(this, buf);
    }

    void getNoiseEta3(Xof xof, byte[] seed, int seedOff, byte nonce)
    {
        byte[] buf = new byte[3 * MLKEMEngine.N / 4];
        prf(xof, seed, seedOff, nonce, buf);
        CBD.eta3(this, buf);
    }

    private static void prf(Xof xof, byte[] seed, int seedOff, byte nonce, byte[] output)
    {
        xof.update(seed, seedOff, MLKEMEngine.SymBytes);
        xof.update(nonce);
        xof.doFinal(output, 0, output.length);
    }

    void subtract(Poly b)
    {
        for (int i = 0; i < MLKEMEngine.N; i++)
        {
            coeffs[i] = (short)(b.coeffs[i] - coeffs[i]);
        }
    }

    static int checkModulus(byte[] a, int off)
    {
        int result = -1;
        for (int i = 0; i < MLKEMEngine.N / 2; ++i)
        {
            int a0 = a[off + 3 * i + 0] & 0xFF;
            int a1 = a[off + 3 * i + 1] & 0xFF;
            int a2 = a[off + 3 * i + 2] & 0xFF;
            short c0 = (short)(((a0 >> 0) | (a1 << 8)) & 0xFFF);
            short c1 = (short)(((a1 >> 4) | (a2 << 4)) & 0xFFF);
            result &= Reduce.checkModulus(c0);
            result &= Reduce.checkModulus(c1);
        }
        return result;
    }
}
