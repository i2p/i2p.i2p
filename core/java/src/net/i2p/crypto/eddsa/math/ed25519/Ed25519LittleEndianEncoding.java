package net.i2p.crypto.eddsa.math.ed25519;

import net.i2p.crypto.eddsa.math.Encoding;
import net.i2p.crypto.eddsa.math.FieldElement;

public class Ed25519LittleEndianEncoding extends Encoding {
    /**
     * Preconditions:<br>
     *   |h| bounded by 1.1*2^26,1.1*2^25,1.1*2^26,1.1*2^25,etc.<br><br>
     *
     * Write p=2^255-19; q=floor(h/p).<br>
     * Basic claim: q = floor(2^(-255)(h + 19 2^(-25)h9 + 2^(-1))).
     * <br><br>
     * Proof:<br>
     *   Have |h|<=p so |q|<=1 so |19^2 2^(-255) q|<1/4.<br>
     *   Also have |h-2^230 h9|<2^231 so |19 2^(-255)(h-2^230 h9)|<1/4.
     *   <br><br>
     *   Write y=2^(-1)-19^2 2^(-255)q-19 2^(-255)(h-2^230 h9).<br>
     *   Then 0 < y < 1.
     *   <br><br>
     *   Write r=h-pq.<br>
     *   Have 0 <= r <= p-1=2^255-20.<br>
     *   Thus 0 <= r+19(2^-255)r < r+19(2^-255)2^255 <= 2^255-1.
     *   <br><br>
     *   Write x=r+19(2^-255)r+y.<br>
     *   Then 0 < x < 2^255 so floor(2^(-255)x) = 0 so floor(q+2^(-255)x) = q.
     *   <br><br>
     *   Have q+2^(-255)x = 2^(-255)(h + 19 2^(-25) h9 + 2^(-1))<br>
     *   so floor(2^(-255)(h + 19 2^(-25) h9 + 2^(-1))) = q.
     */
    public byte[] encode(FieldElement x) {
        int[] h = ((Ed25519FieldElement)x).t;
        int h0 = h[0];
        int h1 = h[1];
        int h2 = h[2];
        int h3 = h[3];
        int h4 = h[4];
        int h5 = h[5];
        int h6 = h[6];
        int h7 = h[7];
        int h8 = h[8];
        int h9 = h[9];
        int q;
        int carry0;
        int carry1;
        int carry2;
        int carry3;
        int carry4;
        int carry5;
        int carry6;
        int carry7;
        int carry8;
        int carry9;

        q = (19 * h9 + (((int) 1) << 24)) >> 25;
        q = (h0 + q) >> 26;
        q = (h1 + q) >> 25;
        q = (h2 + q) >> 26;
        q = (h3 + q) >> 25;
        q = (h4 + q) >> 26;
        q = (h5 + q) >> 25;
        q = (h6 + q) >> 26;
        q = (h7 + q) >> 25;
        q = (h8 + q) >> 26;
        q = (h9 + q) >> 25;

        /* Goal: Output h-(2^255-19)q, which is between 0 and 2^255-20. */
        h0 += 19 * q;
        /* Goal: Output h-2^255 q, which is between 0 and 2^255-20. */

        carry0 = h0 >> 26; h1 += carry0; h0 -= carry0 << 26;
        carry1 = h1 >> 25; h2 += carry1; h1 -= carry1 << 25;
        carry2 = h2 >> 26; h3 += carry2; h2 -= carry2 << 26;
        carry3 = h3 >> 25; h4 += carry3; h3 -= carry3 << 25;
        carry4 = h4 >> 26; h5 += carry4; h4 -= carry4 << 26;
        carry5 = h5 >> 25; h6 += carry5; h5 -= carry5 << 25;
        carry6 = h6 >> 26; h7 += carry6; h6 -= carry6 << 26;
        carry7 = h7 >> 25; h8 += carry7; h7 -= carry7 << 25;
        carry8 = h8 >> 26; h9 += carry8; h8 -= carry8 << 26;
        carry9 = h9 >> 25;               h9 -= carry9 << 25;
                        /* h10 = carry9 */

        /*
        Goal: Output h0+...+2^255 h10-2^255 q, which is between 0 and 2^255-20.
        Have h0+...+2^230 h9 between 0 and 2^255-1;
        evidently 2^255 h10-2^255 q = 0.
        Goal: Output h0+...+2^230 h9.
        */

        byte[] s = new byte[32];
        s[0] = (byte) h0;
        s[1] = (byte) (h0 >> 8);
        s[2] = (byte) (h0 >> 16);
        s[3] = (byte) ((h0 >> 24) | (h1 << 2));
        s[4] = (byte) (h1 >> 6);
        s[5] = (byte) (h1 >> 14);
        s[6] = (byte) ((h1 >> 22) | (h2 << 3));
        s[7] = (byte) (h2 >> 5);
        s[8] = (byte) (h2 >> 13);
        s[9] = (byte) ((h2 >> 21) | (h3 << 5));
        s[10] = (byte) (h3 >> 3);
        s[11] = (byte) (h3 >> 11);
        s[12] = (byte) ((h3 >> 19) | (h4 << 6));
        s[13] = (byte) (h4 >> 2);
        s[14] = (byte) (h4 >> 10);
        s[15] = (byte) (h4 >> 18);
        s[16] = (byte) h5;
        s[17] = (byte) (h5 >> 8);
        s[18] = (byte) (h5 >> 16);
        s[19] = (byte) ((h5 >> 24) | (h6 << 1));
        s[20] = (byte) (h6 >> 7);
        s[21] = (byte) (h6 >> 15);
        s[22] = (byte) ((h6 >> 23) | (h7 << 3));
        s[23] = (byte) (h7 >> 5);
        s[24] = (byte) (h7 >> 13);
        s[25] = (byte) ((h7 >> 21) | (h8 << 4));
        s[26] = (byte) (h8 >> 4);
        s[27] = (byte) (h8 >> 12);
        s[28] = (byte) ((h8 >> 20) | (h9 << 6));
        s[29] = (byte) (h9 >> 2);
        s[30] = (byte) (h9 >> 10);
        s[31] = (byte) (h9 >> 18);
        return s;
    }

    static int load_3(byte[] in, int offset) {
        int result = in[offset++] & 0xff;
        result |= (in[offset++] & 0xff) << 8;
        result |= (in[offset] & 0xff) << 16;
        return result;
    }

    static long load_4(byte[] in, int offset) {
        int result = in[offset++] & 0xff;
        result |= (in[offset++] & 0xff) << 8;
        result |= (in[offset++] & 0xff) << 16;
        result |= in[offset] << 24;
        return ((long)result) & 0xffffffffL;
    }

    /**
     * Ignores top bit.
     */
    public FieldElement decode(byte[] in) {
        long h0 = load_4(in, 0);
        long h1 = load_3(in, 4) << 6;
        long h2 = load_3(in, 7) << 5;
        long h3 = load_3(in, 10) << 3;
        long h4 = load_3(in, 13) << 2;
        long h5 = load_4(in, 16);
        long h6 = load_3(in, 20) << 7;
        long h7 = load_3(in, 23) << 5;
        long h8 = load_3(in, 26) << 4;
        long h9 = (load_3(in, 29) & 8388607) << 2;
        long carry0;
        long carry1;
        long carry2;
        long carry3;
        long carry4;
        long carry5;
        long carry6;
        long carry7;
        long carry8;
        long carry9;

        carry9 = (h9 + (long) (1<<24)) >> 25; h0 += carry9 * 19; h9 -= carry9 << 25;
        carry1 = (h1 + (long) (1<<24)) >> 25; h2 += carry1; h1 -= carry1 << 25;
        carry3 = (h3 + (long) (1<<24)) >> 25; h4 += carry3; h3 -= carry3 << 25;
        carry5 = (h5 + (long) (1<<24)) >> 25; h6 += carry5; h5 -= carry5 << 25;
        carry7 = (h7 + (long) (1<<24)) >> 25; h8 += carry7; h7 -= carry7 << 25;

        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;
        carry2 = (h2 + (long) (1<<25)) >> 26; h3 += carry2; h2 -= carry2 << 26;
        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;
        carry6 = (h6 + (long) (1<<25)) >> 26; h7 += carry6; h6 -= carry6 << 26;
        carry8 = (h8 + (long) (1<<25)) >> 26; h9 += carry8; h8 -= carry8 << 26;

        int[] h = new int[10];
        h[0] = (int) h0;
        h[1] = (int) h1;
        h[2] = (int) h2;
        h[3] = (int) h3;
        h[4] = (int) h4;
        h[5] = (int) h5;
        h[6] = (int) h6;
        h[7] = (int) h7;
        h[8] = (int) h8;
        h[9] = (int) h9;
        return new Ed25519FieldElement(f, h);
    }

    /**
     * Return true if x is in {1,3,5,...,q-2}<br>
     * Return false if x is in {0,2,4,...,q-1}<br><br>
     *
     * Preconditions:<br>
     *    |x| bounded by 1.1*2^26,1.1*2^25,1.1*2^26,1.1*2^25,etc.
     * @return true if x is in {1,3,5,...,q-2}, false otherwise.
     */
    public boolean isNegative(FieldElement x) {
        byte[] s = encode(x);
        return (s[0] & 1) != 0;
    }

}
