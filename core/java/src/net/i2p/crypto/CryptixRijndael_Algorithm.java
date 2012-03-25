/*
 * Copyright (c) 1997, 1998 Systemics Ltd on behalf of
 * the Cryptix Development Team. All rights reserved.
 */
package net.i2p.crypto;

import java.io.PrintWriter;
import java.security.InvalidKeyException;

import net.i2p.util.Clock;

//...........................................................................
/**
 * Rijndael --pronounced Reindaal-- is a variable block-size (128-, 192- and
 * 256-bit), variable key-size (128-, 192- and 256-bit) symmetric cipher.<p>
 *
 * Rijndael was written by <a href="mailto:rijmen@esat.kuleuven.ac.be">Vincent
 * Rijmen</a> and <a href="mailto:Joan.Daemen@village.uunet.be">Joan Daemen</a>.<p>
 *
 * Portions of this code are <b>Copyright</b> &copy; 1997, 1998
 * <a href="http://www.systemics.com/">Systemics Ltd</a> on behalf of the
 * <a href="http://www.systemics.com/docs/cryptix/">Cryptix Development Team</a>.
 * <br>All rights reserved.<p>
 *
 * @author  Raif S. Naffah
 * @author  Paulo S. L. M. Barreto
 *
 * License is apparently available from http://www.cryptix.org/docs/license.html
 */
public final class CryptixRijndael_Algorithm // implicit no-argument constructor
{
    // Debugging methods and variables
    //...........................................................................

    static final String _NAME = "Rijndael_Algorithm";
    static final boolean _IN = true, _OUT = false;

    static final boolean _RDEBUG = false;
    static final int _debuglevel = 0; // RDEBUG ? Rijndael_Properties.getLevel(NAME): 0;
    //    static final PrintWriter err = RDEBUG ? Rijndael_Properties.getOutput() : null;
    static final PrintWriter _err = new PrintWriter(new java.io.OutputStreamWriter(System.err));

    static final boolean _TRACE = false; // Rijndael_Properties.isTraceable(NAME);

    static void debug(String s) {
        _err.println(">>> " + _NAME + ": " + s);
    }

    static void trace(boolean in, String s) {
        if (_TRACE) _err.println((in ? "==> " : "<== ") + _NAME + "." + s);
    }

    static void trace(String s) {
        if (_TRACE) _err.println("<=> " + _NAME + "." + s);
    }

    // Constants and variables
    //...........................................................................

    static final int _BLOCK_SIZE = 16; // default block size in bytes

    static final int[] _alog = new int[256];
    static final int[] _log = new int[256];

    static final byte[] _S = new byte[256];
    static final byte[] _Si = new byte[256];
    static final int[] _T1 = new int[256];
    static final int[] _T2 = new int[256];
    static final int[] _T3 = new int[256];
    static final int[] _T4 = new int[256];
    static final int[] _T5 = new int[256];
    static final int[] _T6 = new int[256];
    static final int[] _T7 = new int[256];
    static final int[] _T8 = new int[256];
    static final int[] _U1 = new int[256];
    static final int[] _U2 = new int[256];
    static final int[] _U3 = new int[256];
    static final int[] _U4 = new int[256];
    static final byte[] _rcon = new byte[30];

    static final int[][][] _shifts = new int[][][] { { { 0, 0}, { 1, 3}, { 2, 2}, { 3, 1}},
                                                    { { 0, 0}, { 1, 5}, { 2, 4}, { 3, 3}},
                                                    { { 0, 0}, { 1, 7}, { 3, 5}, { 4, 4}}};

    private static final char[] _HEX_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D',
                                               'E', 'F'};

    // Static code - to intialise S-boxes and T-boxes
    //...........................................................................

    static {
        if (_RDEBUG && _debuglevel > 6) {
            System.out.println("Algorithm Name: Rijndael ver 0.1");
            System.out.println("Electronic Codebook (ECB) Mode");
            System.out.println();
        }
        int ROOT = 0x11B;
        int i, j = 0;

        //
        // produce log and alog tables, needed for multiplying in the
        // field GF(2^m) (generator = 3)
        //
        _alog[0] = 1;
        for (i = 1; i < 256; i++) {
            j = (_alog[i - 1] << 1) ^ _alog[i - 1];
            if ((j & 0x100) != 0) j ^= ROOT;
            _alog[i] = j;
        }
        for (i = 1; i < 255; i++)
            _log[_alog[i]] = i;
        byte[][] A = new byte[][] { { 1, 1, 1, 1, 1, 0, 0, 0}, { 0, 1, 1, 1, 1, 1, 0, 0}, { 0, 0, 1, 1, 1, 1, 1, 0},
                                   { 0, 0, 0, 1, 1, 1, 1, 1}, { 1, 0, 0, 0, 1, 1, 1, 1}, { 1, 1, 0, 0, 0, 1, 1, 1},
                                   { 1, 1, 1, 0, 0, 0, 1, 1}, { 1, 1, 1, 1, 0, 0, 0, 1}};
        byte[] B = new byte[] { 0, 1, 1, 0, 0, 0, 1, 1};

        //
        // substitution box based on F^{-1}(x)
        //
        int t;
        byte[][] box = new byte[256][8];
        box[1][7] = 1;
        for (i = 2; i < 256; i++) {
            j = _alog[255 - _log[i]];
            for (t = 0; t < 8; t++)
                box[i][t] = (byte) ((j >>> (7 - t)) & 0x01);
        }
        //
        // affine transform:  box[i] <- B + A*box[i]
        //
        byte[][] cox = new byte[256][8];
        for (i = 0; i < 256; i++)
            for (t = 0; t < 8; t++) {
                cox[i][t] = B[t];
                for (j = 0; j < 8; j++)
                    cox[i][t] ^= A[t][j] * box[i][j];
            }
        //
        // S-boxes and inverse S-boxes
        //
        for (i = 0; i < 256; i++) {
            _S[i] = (byte) (cox[i][0] << 7);
            for (t = 1; t < 8; t++)
                _S[i] ^= cox[i][t] << (7 - t);
            _Si[_S[i] & 0xFF] = (byte) i;
        }
        //
        // T-boxes
        //
        byte[][] G = new byte[][] { { 2, 1, 1, 3}, { 3, 2, 1, 1}, { 1, 3, 2, 1}, { 1, 1, 3, 2}};
        byte[][] AA = new byte[4][8];
        for (i = 0; i < 4; i++) {
            for (j = 0; j < 4; j++)
                AA[i][j] = G[i][j];
            AA[i][i + 4] = 1;
        }
        byte pivot, tmp;
        byte[][] iG = new byte[4][4];
        for (i = 0; i < 4; i++) {
            pivot = AA[i][i];
            if (pivot == 0) {
                t = i + 1;
                while ((AA[t][i] == 0) && (t < 4))
                    t++;
                if (t == 4)
                    throw new RuntimeException("G matrix is not invertible");

                for (j = 0; j < 8; j++) {
                    tmp = AA[i][j];
                    AA[i][j] = AA[t][j];
                    AA[t][j] = tmp;
                }
                pivot = AA[i][i];
            }
            for (j = 0; j < 8; j++)
                if (AA[i][j] != 0) AA[i][j] = (byte) _alog[(255 + _log[AA[i][j] & 0xFF] - _log[pivot & 0xFF]) % 255];
            for (t = 0; t < 4; t++)
                if (i != t) {
                    for (j = i + 1; j < 8; j++)
                        AA[t][j] ^= mul(AA[i][j], AA[t][i]);
                    AA[t][i] = 0;
                }
        }
        for (i = 0; i < 4; i++)
            for (j = 0; j < 4; j++)
                iG[i][j] = AA[i][j + 4];

        int s;
        for (t = 0; t < 256; t++) {
            s = _S[t];
            _T1[t] = mul4(s, G[0]);
            _T2[t] = mul4(s, G[1]);
            _T3[t] = mul4(s, G[2]);
            _T4[t] = mul4(s, G[3]);

            s = _Si[t];
            _T5[t] = mul4(s, iG[0]);
            _T6[t] = mul4(s, iG[1]);
            _T7[t] = mul4(s, iG[2]);
            _T8[t] = mul4(s, iG[3]);

            _U1[t] = mul4(t, iG[0]);
            _U2[t] = mul4(t, iG[1]);
            _U3[t] = mul4(t, iG[2]);
            _U4[t] = mul4(t, iG[3]);
        }
        //
        // round constants
        //
        _rcon[0] = 1;
        int r = 1;
        for (t = 1; t < 30;)
            _rcon[t++] = (byte) (r = mul(2, r));

/****
        time = Clock.getInstance().now() - time;

        if (_RDEBUG && _debuglevel > 8) {
            System.out.println("==========");
            System.out.println();
            System.out.println("Static Data");
            System.out.println();
            System.out.println("S[]:");
            for (i = 0; i < 16; i++) {
                for (j = 0; j < 16; j++)
                    System.out.print("0x" + byteToString(_S[i * 16 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("Si[]:");
            for (i = 0; i < 16; i++) {
                for (j = 0; j < 16; j++)
                    System.out.print("0x" + byteToString(_Si[i * 16 + j]) + ", ");
                System.out.println();
            }

            System.out.println();
            System.out.println("iG[]:");
            for (i = 0; i < 4; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + byteToString(iG[i][j]) + ", ");
                System.out.println();
            }

            System.out.println();
            System.out.println("T1[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_T1[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("T2[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_T2[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("T3[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_T3[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("T4[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_T4[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("T5[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_T5[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("T6[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_T6[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("T7[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_T7[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("T8[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_T8[i * 4 + j]) + ", ");
                System.out.println();
            }

            System.out.println();
            System.out.println("U1[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_U1[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("U2[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_U2[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("U3[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_U3[i * 4 + j]) + ", ");
                System.out.println();
            }
            System.out.println();
            System.out.println("U4[]:");
            for (i = 0; i < 64; i++) {
                for (j = 0; j < 4; j++)
                    System.out.print("0x" + intToString(_U4[i * 4 + j]) + ", ");
                System.out.println();
            }

            System.out.println();
            System.out.println("rcon[]:");
            for (i = 0; i < 5; i++) {
                for (j = 0; j < 6; j++)
                    System.out.print("0x" + byteToString(_rcon[i * 6 + j]) + ", ");
                System.out.println();
            }

            System.out.println();
            System.out.println("Total initialization time: " + time + " ms.");
            System.out.println();
        }
****/
    }

    // multiply two elements of GF(2^m)
    static final int mul(int a, int b) {
        return (a != 0 && b != 0) ? _alog[(_log[a & 0xFF] + _log[b & 0xFF]) % 255] : 0;
    }

    // convenience method used in generating Transposition boxes
    static final int mul4(int a, byte[] b) {
        if (a == 0) return 0;
        a = _log[a & 0xFF];
        int a0 = (b[0] != 0) ? _alog[(a + _log[b[0] & 0xFF]) % 255] & 0xFF : 0;
        int a1 = (b[1] != 0) ? _alog[(a + _log[b[1] & 0xFF]) % 255] & 0xFF : 0;
        int a2 = (b[2] != 0) ? _alog[(a + _log[b[2] & 0xFF]) % 255] & 0xFF : 0;
        int a3 = (b[3] != 0) ? _alog[(a + _log[b[3] & 0xFF]) % 255] & 0xFF : 0;
        return a0 << 24 | a1 << 16 | a2 << 8 | a3;
    }

    // Basic API methods
    //...........................................................................

    /**
     * Convenience method to expand a user-supplied key material into a
     * session key, assuming Rijndael's default block size (128-bit).
     *
     * @param k The 128/192/256-bit user-key to use.
     * @exception  InvalidKeyException  If the key is invalid.
     */
    public static final Object makeKey(byte[] k) throws InvalidKeyException {
        return makeKey(k, _BLOCK_SIZE);
    }

    /**
     * Convenience method to encrypt exactly one block of plaintext, assuming
     * Rijndael's default block size (128-bit).
     *
     * @param  in         The plaintext.
     * @param  result     The resulting ciphertext.
     * @param  inOffset   Index of in from which to start considering data.
     * @param  sessionKey The session key to use for encryption.
     */
    public static final void blockEncrypt(byte[] in, byte[] result, int inOffset, int outOffset, Object sessionKey) {
        if (_RDEBUG) trace(_IN, "blockEncrypt(" + in + ", " + inOffset + ", " + sessionKey + ")");
        int[][] Ke = (int[][]) ((Object[]) sessionKey)[0]; // extract encryption round keys
        int ROUNDS = Ke.length - 1;
        int[] Ker = Ke[0];

        // plaintext to ints + key
        int t0 = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                 ^ Ker[0];
        int t1 = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                 ^ Ker[1];
        int t2 = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                 ^ Ker[2];
        int t3 = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                 ^ Ker[3];

        int a0, a1, a2, a3;
        for (int r = 1; r < ROUNDS; r++) { // apply round transforms
            Ker = Ke[r];
            a0 = (_T1[(t0 >>> 24) & 0xFF] ^ _T2[(t1 >>> 16) & 0xFF] ^ _T3[(t2 >>> 8) & 0xFF] ^ _T4[t3 & 0xFF]) ^ Ker[0];
            a1 = (_T1[(t1 >>> 24) & 0xFF] ^ _T2[(t2 >>> 16) & 0xFF] ^ _T3[(t3 >>> 8) & 0xFF] ^ _T4[t0 & 0xFF]) ^ Ker[1];
            a2 = (_T1[(t2 >>> 24) & 0xFF] ^ _T2[(t3 >>> 16) & 0xFF] ^ _T3[(t0 >>> 8) & 0xFF] ^ _T4[t1 & 0xFF]) ^ Ker[2];
            a3 = (_T1[(t3 >>> 24) & 0xFF] ^ _T2[(t0 >>> 16) & 0xFF] ^ _T3[(t1 >>> 8) & 0xFF] ^ _T4[t2 & 0xFF]) ^ Ker[3];
            t0 = a0;
            t1 = a1;
            t2 = a2;
            t3 = a3;
            if (_RDEBUG && _debuglevel > 6)
                System.out.println("CT" + r + "=" + intToString(t0) + intToString(t1) + intToString(t2)
                                   + intToString(t3));
        }

        // last round is special
        Ker = Ke[ROUNDS];
        int tt = Ker[0];
        result[outOffset++] = (byte) (_S[(t0 >>> 24) & 0xFF] ^ (tt >>> 24));
        result[outOffset++] = (byte) (_S[(t1 >>> 16) & 0xFF] ^ (tt >>> 16));
        result[outOffset++] = (byte) (_S[(t2 >>> 8) & 0xFF] ^ (tt >>> 8));
        result[outOffset++] = (byte) (_S[t3 & 0xFF] ^ tt);
        tt = Ker[1];
        result[outOffset++] = (byte) (_S[(t1 >>> 24) & 0xFF] ^ (tt >>> 24));
        result[outOffset++] = (byte) (_S[(t2 >>> 16) & 0xFF] ^ (tt >>> 16));
        result[outOffset++] = (byte) (_S[(t3 >>> 8) & 0xFF] ^ (tt >>> 8));
        result[outOffset++] = (byte) (_S[t0 & 0xFF] ^ tt);
        tt = Ker[2];
        result[outOffset++] = (byte) (_S[(t2 >>> 24) & 0xFF] ^ (tt >>> 24));
        result[outOffset++] = (byte) (_S[(t3 >>> 16) & 0xFF] ^ (tt >>> 16));
        result[outOffset++] = (byte) (_S[(t0 >>> 8) & 0xFF] ^ (tt >>> 8));
        result[outOffset++] = (byte) (_S[t1 & 0xFF] ^ tt);
        tt = Ker[3];
        result[outOffset++] = (byte) (_S[(t3 >>> 24) & 0xFF] ^ (tt >>> 24));
        result[outOffset++] = (byte) (_S[(t0 >>> 16) & 0xFF] ^ (tt >>> 16));
        result[outOffset++] = (byte) (_S[(t1 >>> 8) & 0xFF] ^ (tt >>> 8));
        result[outOffset++] = (byte) (_S[t2 & 0xFF] ^ tt);
        if (_RDEBUG && _debuglevel > 6) {
            System.out.println("CT=" + toString(result));
            System.out.println();
        }
        if (_RDEBUG) trace(_OUT, "blockEncrypt()");
    }

    /**
     * Convenience method to decrypt exactly one block of plaintext, assuming
     * Rijndael's default block size (128-bit).
     *
     * @param  in         The ciphertext.
     * @param  result     The resulting ciphertext
     * @param  inOffset   Index of in from which to start considering data.
     * @param  sessionKey The session key to use for decryption.
     */
    public static final void blockDecrypt(byte[] in, byte[] result, int inOffset, int outOffset, Object sessionKey) {
        if (result.length - outOffset <= 15)
            throw new IllegalArgumentException("result too small:"
                                               + " result.len=" + result.length + " result.offset=" + outOffset);
        if (in.length - inOffset <= 15)
            throw new IllegalArgumentException("data too small: " + in.length + " inOffset: " + inOffset);
        if (_RDEBUG) trace(_IN, "blockDecrypt(" + in + ", " + inOffset + ", " + sessionKey + ")");
        int[][] Kd = (int[][]) ((Object[]) sessionKey)[1]; // extract decryption round keys
        int ROUNDS = Kd.length - 1;
        int[] Kdr = Kd[0];

        // ciphertext to ints + key
        int t0 = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                 ^ Kdr[0];
        int t1 = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                 ^ Kdr[1];
        int t2 = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                 ^ Kdr[2];
        int t3 = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                 ^ Kdr[3];

        int a0, a1, a2, a3;
        for (int r = 1; r < ROUNDS; r++) { // apply round transforms
            Kdr = Kd[r];
            a0 = (_T5[(t0 >>> 24) & 0xFF] ^ _T6[(t3 >>> 16) & 0xFF] ^ _T7[(t2 >>> 8) & 0xFF] ^ _T8[t1 & 0xFF]) ^ Kdr[0];
            a1 = (_T5[(t1 >>> 24) & 0xFF] ^ _T6[(t0 >>> 16) & 0xFF] ^ _T7[(t3 >>> 8) & 0xFF] ^ _T8[t2 & 0xFF]) ^ Kdr[1];
            a2 = (_T5[(t2 >>> 24) & 0xFF] ^ _T6[(t1 >>> 16) & 0xFF] ^ _T7[(t0 >>> 8) & 0xFF] ^ _T8[t3 & 0xFF]) ^ Kdr[2];
            a3 = (_T5[(t3 >>> 24) & 0xFF] ^ _T6[(t2 >>> 16) & 0xFF] ^ _T7[(t1 >>> 8) & 0xFF] ^ _T8[t0 & 0xFF]) ^ Kdr[3];
            t0 = a0;
            t1 = a1;
            t2 = a2;
            t3 = a3;
            if (_RDEBUG && _debuglevel > 6)
                System.out.println("PT" + r + "=" + intToString(t0) + intToString(t1) + intToString(t2)
                                   + intToString(t3));
        }

        // last round is special
        Kdr = Kd[ROUNDS];
        int tt = Kdr[0];
        result[outOffset++] = (byte) (_Si[(t0 >>> 24) & 0xFF] ^ (tt >>> 24));
        result[outOffset++] = (byte) (_Si[(t3 >>> 16) & 0xFF] ^ (tt >>> 16));
        result[outOffset++] = (byte) (_Si[(t2 >>> 8) & 0xFF] ^ (tt >>> 8));
        result[outOffset++] = (byte) (_Si[t1 & 0xFF] ^ tt);
        tt = Kdr[1];
        result[outOffset++] = (byte) (_Si[(t1 >>> 24) & 0xFF] ^ (tt >>> 24));
        result[outOffset++] = (byte) (_Si[(t0 >>> 16) & 0xFF] ^ (tt >>> 16));
        result[outOffset++] = (byte) (_Si[(t3 >>> 8) & 0xFF] ^ (tt >>> 8));
        result[outOffset++] = (byte) (_Si[t2 & 0xFF] ^ tt);
        tt = Kdr[2];
        result[outOffset++] = (byte) (_Si[(t2 >>> 24) & 0xFF] ^ (tt >>> 24));
        result[outOffset++] = (byte) (_Si[(t1 >>> 16) & 0xFF] ^ (tt >>> 16));
        result[outOffset++] = (byte) (_Si[(t0 >>> 8) & 0xFF] ^ (tt >>> 8));
        result[outOffset++] = (byte) (_Si[t3 & 0xFF] ^ tt);
        tt = Kdr[3];
        result[outOffset++] = (byte) (_Si[(t3 >>> 24) & 0xFF] ^ (tt >>> 24));
        result[outOffset++] = (byte) (_Si[(t2 >>> 16) & 0xFF] ^ (tt >>> 16));
        result[outOffset++] = (byte) (_Si[(t1 >>> 8) & 0xFF] ^ (tt >>> 8));
        result[outOffset++] = (byte) (_Si[t0 & 0xFF] ^ tt);
        if (_RDEBUG && _debuglevel > 6) {
            System.out.println("PT=" + toString(result));
            System.out.println();
        }
        if (_RDEBUG) trace(_OUT, "blockDecrypt()");
    }

    /** A basic symmetric encryption/decryption test. */
/****
    public static boolean self_test() {
        return self_test(_BLOCK_SIZE);
    }
****/

    // Rijndael own methods
    //...........................................................................

    /** @return The default length in bytes of the Algorithm input block. */
    public static final int blockSize() {
        return _BLOCK_SIZE;
    }

    /**
     * Expand a user-supplied key material into a session key.
     *
     * @param k          The 128/192/256-bit user-key to use.
     * @param blockSize  The block size in bytes of this Rijndael.
     * @exception  InvalidKeyException  If the key is invalid.
     */
    public static final/* synchronized */Object makeKey(byte[] k, int blockSize) throws InvalidKeyException {
        return makeKey(k, blockSize, null);
    }
    public static final/* synchronized */Object makeKey(byte[] k, int blockSize, CryptixAESKeyCache.KeyCacheEntry keyData) throws InvalidKeyException {
        if (_RDEBUG) trace(_IN, "makeKey(" + k + ", " + blockSize + ")");
        if (k == null) throw new InvalidKeyException("Empty key");
        if (!(k.length == 16 || k.length == 24 || k.length == 32))
            throw new InvalidKeyException("Incorrect key length");
        int ROUNDS = getRounds(k.length, blockSize);
        int BC = blockSize / 4;
        int[][] Ke = null; // new int[ROUNDS + 1][BC]; // encryption round keys
        int[][] Kd = null; // new int[ROUNDS + 1][BC]; // decryption round keys
        int ROUND_KEY_COUNT = (ROUNDS + 1) * BC;
        int KC = k.length / 4;
        int[] tk = null; // new int[KC];
        int i, j;
        
        if (keyData == null) {
            Ke = new int[ROUNDS + 1][BC];
            Kd = new int[ROUNDS + 1][BC];
            tk = new int[KC];
        } else {
            Ke = keyData.Ke;
            Kd = keyData.Kd;
            tk = keyData.tk;
        }

        // copy user material bytes into temporary ints
        for (i = 0, j = 0; i < KC;)
            tk[i++] = (k[j++] & 0xFF) << 24 | (k[j++] & 0xFF) << 16 | (k[j++] & 0xFF) << 8 | (k[j++] & 0xFF);
        // copy values into round key arrays
        int t = 0;
        for (j = 0; (j < KC) && (t < ROUND_KEY_COUNT); j++, t++) {
            Ke[t / BC][t % BC] = tk[j];
            Kd[ROUNDS - (t / BC)][t % BC] = tk[j];
        }
        int tt, rconpointer = 0;
        while (t < ROUND_KEY_COUNT) {
            // extrapolate using phi (the round key evolution function)
            tt = tk[KC - 1];
            tk[0] ^= (_S[(tt >>> 16) & 0xFF] & 0xFF) << 24 ^ (_S[(tt >>> 8) & 0xFF] & 0xFF) << 16
                     ^ (_S[tt & 0xFF] & 0xFF) << 8 ^ (_S[(tt >>> 24) & 0xFF] & 0xFF)
                     ^ (_rcon[rconpointer++] & 0xFF) << 24;
            if (KC != 8)
                for (i = 1, j = 0; i < KC;) {
                    //tk[i++] ^= tk[j++];
                    // The above line replaced with the code below in order to work around
                    // a bug in the kjc-1.4F java compiler (which has been reported).
                    tk[i] ^= tk[j++];
                    i++;
                }
            else {
                for (i = 1, j = 0; i < KC / 2;) {
                    //tk[i++] ^= tk[j++];
                    // The above line replaced with the code below in order to work around
                    // a bug in the kjc-1.4F java compiler (which has been reported).
                    tk[i] ^= tk[j++];
                    i++;
                }
                tt = tk[KC / 2 - 1];
                tk[KC / 2] ^= (_S[tt & 0xFF] & 0xFF) ^ (_S[(tt >>> 8) & 0xFF] & 0xFF) << 8
                              ^ (_S[(tt >>> 16) & 0xFF] & 0xFF) << 16 ^ (_S[(tt >>> 24) & 0xFF] & 0xFF) << 24;
                for (j = KC / 2, i = j + 1; i < KC;) {
                    //tk[i++] ^= tk[j++];
                    // The above line replaced with the code below in order to work around
                    // a bug in the kjc-1.4F java compiler (which has been reported).
                    tk[i] ^= tk[j++];
                    i++;
                }
            }
            // copy values into round key arrays
            for (j = 0; (j < KC) && (t < ROUND_KEY_COUNT); j++, t++) {
                Ke[t / BC][t % BC] = tk[j];
                Kd[ROUNDS - (t / BC)][t % BC] = tk[j];
            }
        }
        for (int r = 1; r < ROUNDS; r++)
            // inverse MixColumn where needed
            for (j = 0; j < BC; j++) {
                tt = Kd[r][j];
                Kd[r][j] = _U1[(tt >>> 24) & 0xFF] ^ _U2[(tt >>> 16) & 0xFF] ^ _U3[(tt >>> 8) & 0xFF] ^ _U4[tt & 0xFF];
            }
        // assemble the encryption (Ke) and decryption (Kd) round keys into
        // one sessionKey object
        Object[] sessionKey = null;
        if (keyData == null)
            sessionKey = new Object[] { Ke, Kd};
        else
            sessionKey = keyData.key;
        if (_RDEBUG) trace(_OUT, "makeKey()");
        return sessionKey;
    }

    /**
     * Encrypt exactly one block of plaintext.
     *
     * @param  in         The plaintext.
     * @param  result     The resulting ciphertext.
     * @param  inOffset   Index of in from which to start considering data.
     * @param  sessionKey The session key to use for encryption.
     * @param  blockSize  The block size in bytes of this Rijndael.
     */
    public static final void blockEncrypt(byte[] in, byte[] result, int inOffset, int outOffset, Object sessionKey, int blockSize) {
        if (blockSize == _BLOCK_SIZE) {
            blockEncrypt(in, result, inOffset, outOffset, sessionKey);
            return;
        }
        if (_RDEBUG) trace(_IN, "blockEncrypt(" + in + ", " + inOffset + ", " + sessionKey + ", " + blockSize + ")");
        Object[] sKey = (Object[]) sessionKey; // extract encryption round keys
        int[][] Ke = (int[][]) sKey[0];

        int BC = blockSize / 4;
        int ROUNDS = Ke.length - 1;
        int SC = BC == 4 ? 0 : (BC == 6 ? 1 : 2);
        int s1 = _shifts[SC][1][0];
        int s2 = _shifts[SC][2][0];
        int s3 = _shifts[SC][3][0];
        int[] a = new int[BC];
        int[] t = new int[BC]; // temporary work array
        int i;
        int j = outOffset;
        int tt;

        for (i = 0; i < BC; i++)
            // plaintext to ints + key
            t[i] = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                   ^ Ke[0][i];
        for (int r = 1; r < ROUNDS; r++) { // apply round transforms
            for (i = 0; i < BC; i++)
                a[i] = (_T1[(t[i] >>> 24) & 0xFF] ^ _T2[(t[(i + s1) % BC] >>> 16) & 0xFF]
                        ^ _T3[(t[(i + s2) % BC] >>> 8) & 0xFF] ^ _T4[t[(i + s3) % BC] & 0xFF])
                       ^ Ke[r][i];
            System.arraycopy(a, 0, t, 0, BC);
            if (_RDEBUG && _debuglevel > 6) System.out.println("CT" + r + "=" + toString(t));
        }
        for (i = 0; i < BC; i++) { // last round is special
            tt = Ke[ROUNDS][i];
            result[j++] = (byte) (_S[(t[i] >>> 24) & 0xFF] ^ (tt >>> 24));
            result[j++] = (byte) (_S[(t[(i + s1) % BC] >>> 16) & 0xFF] ^ (tt >>> 16));
            result[j++] = (byte) (_S[(t[(i + s2) % BC] >>> 8) & 0xFF] ^ (tt >>> 8));
            result[j++] = (byte) (_S[t[(i + s3) % BC] & 0xFF] ^ tt);
        }
        if (_RDEBUG && _debuglevel > 6) {
            System.out.println("CT=" + toString(result));
            System.out.println();
        }
        if (_RDEBUG) trace(_OUT, "blockEncrypt()");
    }

    /**
     * Decrypt exactly one block of ciphertext.
     *
     * @param  in         The ciphertext.
     * @param  result     The resulting ciphertext.
     * @param  inOffset   Index of in from which to start considering data.
     * @param  sessionKey The session key to use for decryption.
     * @param  blockSize  The block size in bytes of this Rijndael.
     */
    public static final void blockDecrypt(byte[] in, byte[] result, int inOffset, int outOffset, Object sessionKey, int blockSize) {
        if (blockSize == _BLOCK_SIZE) {
            blockDecrypt(in, result, inOffset, outOffset, sessionKey);
            return;
        }

        if (_RDEBUG) trace(_IN, "blockDecrypt(" + in + ", " + inOffset + ", " + sessionKey + ", " + blockSize + ")");
        Object[] sKey = (Object[]) sessionKey; // extract decryption round keys
        int[][] Kd = (int[][]) sKey[1];

        int BC = blockSize / 4;
        int ROUNDS = Kd.length - 1;
        int SC = BC == 4 ? 0 : (BC == 6 ? 1 : 2);
        int s1 = _shifts[SC][1][1];
        int s2 = _shifts[SC][2][1];
        int s3 = _shifts[SC][3][1];
        int[] a = new int[BC];
        int[] t = new int[BC]; // temporary work array
        int i;
        int j = outOffset;
        int tt;

        for (i = 0; i < BC; i++)
            // ciphertext to ints + key
            t[i] = ((in[inOffset++] & 0xFF) << 24 | (in[inOffset++] & 0xFF) << 16 | (in[inOffset++] & 0xFF) << 8 | (in[inOffset++] & 0xFF))
                   ^ Kd[0][i];
        for (int r = 1; r < ROUNDS; r++) { // apply round transforms
            for (i = 0; i < BC; i++)
                a[i] = (_T5[(t[i] >>> 24) & 0xFF] ^ _T6[(t[(i + s1) % BC] >>> 16) & 0xFF]
                        ^ _T7[(t[(i + s2) % BC] >>> 8) & 0xFF] ^ _T8[t[(i + s3) % BC] & 0xFF])
                       ^ Kd[r][i];
            System.arraycopy(a, 0, t, 0, BC);
            if (_RDEBUG && _debuglevel > 6) System.out.println("PT" + r + "=" + toString(t));
        }
        for (i = 0; i < BC; i++) { // last round is special
            tt = Kd[ROUNDS][i];
            result[j++] = (byte) (_Si[(t[i] >>> 24) & 0xFF] ^ (tt >>> 24));
            result[j++] = (byte) (_Si[(t[(i + s1) % BC] >>> 16) & 0xFF] ^ (tt >>> 16));
            result[j++] = (byte) (_Si[(t[(i + s2) % BC] >>> 8) & 0xFF] ^ (tt >>> 8));
            result[j++] = (byte) (_Si[t[(i + s3) % BC] & 0xFF] ^ tt);
        }
        if (_RDEBUG && _debuglevel > 6) {
            System.out.println("PT=" + toString(result));
            System.out.println();
        }
        if (_RDEBUG) trace(_OUT, "blockDecrypt()");
    }

    /** A basic symmetric encryption/decryption test for a given key size. */
/****
    private static boolean self_test(int keysize) {
        if (_RDEBUG) trace(_IN, "self_test(" + keysize + ")");
        boolean ok = false;
        try {
            byte[] kb = new byte[keysize];
            byte[] pt = new byte[_BLOCK_SIZE];
            int i;

            for (i = 0; i < keysize; i++)
                kb[i] = (byte) i;
            for (i = 0; i < _BLOCK_SIZE; i++)
                pt[i] = (byte) i;

            if (_RDEBUG && _debuglevel > 6) {
                System.out.println("==========");
                System.out.println();
                System.out.println("KEYSIZE=" + (8 * keysize));
                System.out.println("KEY=" + toString(kb));
                System.out.println();
            }
            Object key = makeKey(kb, _BLOCK_SIZE);

            if (_RDEBUG && _debuglevel > 6) {
                System.out.println("Intermediate Ciphertext Values (Encryption)");
                System.out.println();
                System.out.println("PT=" + toString(pt));
            }
            byte[] ct = new byte[_BLOCK_SIZE];
            blockEncrypt(pt, ct, 0, 0, key, _BLOCK_SIZE);

            if (_RDEBUG && _debuglevel > 6) {
                System.out.println("Intermediate Plaintext Values (Decryption)");
                System.out.println();
                System.out.println("CT=" + toString(ct));
            }
            byte[] cpt = new byte[_BLOCK_SIZE];
            blockDecrypt(ct, cpt, 0, 0, key, _BLOCK_SIZE);

            ok = areEqual(pt, cpt);
            if (!ok) throw new RuntimeException("Symmetric operation failed");
        } catch (Exception x) {
            if (_RDEBUG && _debuglevel > 0) {
                debug("Exception encountered during self-test: " + x.getMessage());
                x.printStackTrace();
            }
        }
        if (_RDEBUG && _debuglevel > 0) debug("Self-test OK? " + ok);
        if (_RDEBUG) trace(_OUT, "self_test()");
        return ok;
    }
****/

    /**
     * Return The number of rounds for a given Rijndael's key and block sizes.
     *
     * @param keySize    The size of the user key material in bytes.
     * @param blockSize  The desired block size in bytes.
     * @return The number of rounds for a given Rijndael's key and
     *      block sizes.
     */
    public static final int getRounds(int keySize, int blockSize) {
        switch (keySize) {
        case 16:
            return blockSize == 16 ? 10 : (blockSize == 24 ? 12 : 14);
        case 24:
            return blockSize != 32 ? 12 : 14;
        default:
            // 32 bytes = 256 bits
            return 14;
        }
    }

    // utility static methods (from cryptix.util.core ArrayUtil and Hex classes)
    //...........................................................................

    /**
     * Compares two byte arrays for equality.
     *
     * @return true if the arrays have identical contents
     */
    private static final boolean areEqual(byte[] a, byte[] b) {
        int aLength = a.length;
        if (aLength != b.length) return false;
        for (int i = 0; i < aLength; i++)
            if (a[i] != b[i]) return false;
        return true;
    }

    /**
     * Returns a string of 2 hexadecimal digits (most significant
     * digit first) corresponding to the lowest 8 bits of <i>n</i>.
     */
    private static final String byteToString(int n) {
        char[] buf = { _HEX_DIGITS[(n >>> 4) & 0x0F], _HEX_DIGITS[n & 0x0F]};
        return new String(buf);
    }

    /**
     * Returns a string of 8 hexadecimal digits (most significant
     * digit first) corresponding to the integer <i>n</i>, which is
     * treated as unsigned.
     */
    private static final String intToString(int n) {
        char[] buf = new char[8];
        for (int i = 7; i >= 0; i--) {
            buf[i] = _HEX_DIGITS[n & 0x0F];
            n >>>= 4;
        }
        return new String(buf);
    }

    /**
     * Returns a string of hexadecimal digits from a byte array. Each
     * byte is converted to 2 hex symbols.
     */
    private static final String toString(byte[] ba) {
        int length = ba.length;
        char[] buf = new char[length * 2];
        for (int i = 0, j = 0, k; i < length;) {
            k = ba[i++];
            buf[j++] = _HEX_DIGITS[(k >>> 4) & 0x0F];
            buf[j++] = _HEX_DIGITS[k & 0x0F];
        }
        return new String(buf);
    }

    /**
     * Returns a string of hexadecimal digits from an integer array. Each
     * int is converted to 4 hex symbols.
     */
    private static final String toString(int[] ia) {
        int length = ia.length;
        char[] buf = new char[length * 8];
        for (int i = 0, j = 0, k; i < length; i++) {
            k = ia[i];
            buf[j++] = _HEX_DIGITS[(k >>> 28) & 0x0F];
            buf[j++] = _HEX_DIGITS[(k >>> 24) & 0x0F];
            buf[j++] = _HEX_DIGITS[(k >>> 20) & 0x0F];
            buf[j++] = _HEX_DIGITS[(k >>> 16) & 0x0F];
            buf[j++] = _HEX_DIGITS[(k >>> 12) & 0x0F];
            buf[j++] = _HEX_DIGITS[(k >>> 8) & 0x0F];
            buf[j++] = _HEX_DIGITS[(k >>> 4) & 0x0F];
            buf[j++] = _HEX_DIGITS[k & 0x0F];
        }
        return new String(buf);
    }

    // main(): use to generate the Intermediate Values KAT
    //...........................................................................

/****
    public static void main(String[] args) {
        self_test(16);
        self_test(24);
        self_test(32);
    }
****/
}
