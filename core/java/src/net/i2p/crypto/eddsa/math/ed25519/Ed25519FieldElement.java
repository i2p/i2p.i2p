package net.i2p.crypto.eddsa.math.ed25519;

import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.FieldElement;

/**
 * An element t, entries t[0]...t[9], represents the integer
 * t[0]+2^26 t[1]+2^51 t[2]+2^77 t[3]+2^102 t[4]+...+2^230 t[9].
 * Bounds on each t[i] vary depending on context.
 */
public class Ed25519FieldElement extends FieldElement {
    /**
     * Variable is package private for encoding.
     */
    final int[] t;

    public Ed25519FieldElement(Field f, int[] t) {
        super(f);
        if (t.length != 10)
            throw new IllegalArgumentException("Invalid radix-2^51 representation");
        this.t = t;
    }

    private static final byte[] ZERO = new byte[32];

    public boolean isNonZero() {
        byte[] s = toByteArray();
        return Utils.equal(s, ZERO) == 1;
    }

    /**
     * h = f + g
     * Can overlap h with f or g.
     *
     * Preconditions:
     *    |f| bounded by 1.1*2^25,1.1*2^24,1.1*2^25,1.1*2^24,etc.
     *    |g| bounded by 1.1*2^25,1.1*2^24,1.1*2^25,1.1*2^24,etc.
     *
     * Postconditions:
     *    |h| bounded by 1.1*2^26,1.1*2^25,1.1*2^26,1.1*2^25,etc.
     */
    public FieldElement add(FieldElement val) {
        int[] g = ((Ed25519FieldElement)val).t;
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = t[i] + g[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * h = f - g
     * Can overlap h with f or g.
     *
     * Preconditions:
     *    |f| bounded by 1.1*2^25,1.1*2^24,1.1*2^25,1.1*2^24,etc.
     *    |g| bounded by 1.1*2^25,1.1*2^24,1.1*2^25,1.1*2^24,etc.
     *
     * Postconditions:
     *    |h| bounded by 1.1*2^26,1.1*2^25,1.1*2^26,1.1*2^25,etc.
     **/
    public FieldElement subtract(FieldElement val) {
        int[] g = ((Ed25519FieldElement)val).t;
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = t[i] - g[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * h = -f
     *
     * Preconditions:
     *    |f| bounded by 1.1*2^25,1.1*2^24,1.1*2^25,1.1*2^24,etc.
     *
     * Postconditions:
     *    |h| bounded by 1.1*2^25,1.1*2^24,1.1*2^25,1.1*2^24,etc.
     */
    public FieldElement negate() {
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = - t[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * h = f * g Can overlap h with f or g.
     * 
     * Preconditions: |f| bounded by
     * 1.65*2^26,1.65*2^25,1.65*2^26,1.65*2^25,etc. |g| bounded by
     * 1.65*2^26,1.65*2^25,1.65*2^26,1.65*2^25,etc.
     * 
     * Postconditions: |h| bounded by
     * 1.01*2^25,1.01*2^24,1.01*2^25,1.01*2^24,etc.
     *
     * Notes on implementation strategy:
     *
     * Using schoolbook multiplication. Karatsuba would save a little in some
     * cost models.
     *
     * Most multiplications by 2 and 19 are 32-bit precomputations; cheaper than
     * 64-bit postcomputations.
     *
     * There is one remaining multiplication by 19 in the carry chain; one *19
     * precomputation can be merged into this, but the resulting data flow is
     * considerably less clean.
     *
     * There are 12 carries below. 10 of them are 2-way parallelizable and
     * vectorizable. Can get away with 11 carries, but then data flow is much
     * deeper.
     *
     * With tighter constraints on inputs can squeeze carries into int32.
     */
    public FieldElement multiply(FieldElement val) {
        int[] g = ((Ed25519FieldElement)val).t;
        int f0 = t[0];
        int f1 = t[1];
        int f2 = t[2];
        int f3 = t[3];
        int f4 = t[4];
        int f5 = t[5];
        int f6 = t[6];
        int f7 = t[7];
        int f8 = t[8];
        int f9 = t[9];
        int g0 = g[0];
        int g1 = g[1];
        int g2 = g[2];
        int g3 = g[3];
        int g4 = g[4];
        int g5 = g[5];
        int g6 = g[6];
        int g7 = g[7];
        int g8 = g[8];
        int g9 = g[9];
        int g1_19 = 19 * g1; /* 1.959375*2^29 */
        int g2_19 = 19 * g2; /* 1.959375*2^30; still ok */
        int g3_19 = 19 * g3;
        int g4_19 = 19 * g4;
        int g5_19 = 19 * g5;
        int g6_19 = 19 * g6;
        int g7_19 = 19 * g7;
        int g8_19 = 19 * g8;
        int g9_19 = 19 * g9;
        int f1_2 = 2 * f1;
        int f3_2 = 2 * f3;
        int f5_2 = 2 * f5;
        int f7_2 = 2 * f7;
        int f9_2 = 2 * f9;
        long f0g0    = f0   * (long) g0;
        long f0g1    = f0   * (long) g1;
        long f0g2    = f0   * (long) g2;
        long f0g3    = f0   * (long) g3;
        long f0g4    = f0   * (long) g4;
        long f0g5    = f0   * (long) g5;
        long f0g6    = f0   * (long) g6;
        long f0g7    = f0   * (long) g7;
        long f0g8    = f0   * (long) g8;
        long f0g9    = f0   * (long) g9;
        long f1g0    = f1   * (long) g0;
        long f1g1_2  = f1_2 * (long) g1;
        long f1g2    = f1   * (long) g2;
        long f1g3_2  = f1_2 * (long) g3;
        long f1g4    = f1   * (long) g4;
        long f1g5_2  = f1_2 * (long) g5;
        long f1g6    = f1   * (long) g6;
        long f1g7_2  = f1_2 * (long) g7;
        long f1g8    = f1   * (long) g8;
        long f1g9_38 = f1_2 * (long) g9_19;
        long f2g0    = f2   * (long) g0;
        long f2g1    = f2   * (long) g1;
        long f2g2    = f2   * (long) g2;
        long f2g3    = f2   * (long) g3;
        long f2g4    = f2   * (long) g4;
        long f2g5    = f2   * (long) g5;
        long f2g6    = f2   * (long) g6;
        long f2g7    = f2   * (long) g7;
        long f2g8_19 = f2   * (long) g8_19;
        long f2g9_19 = f2   * (long) g9_19;
        long f3g0    = f3   * (long) g0;
        long f3g1_2  = f3_2 * (long) g1;
        long f3g2    = f3   * (long) g2;
        long f3g3_2  = f3_2 * (long) g3;
        long f3g4    = f3   * (long) g4;
        long f3g5_2  = f3_2 * (long) g5;
        long f3g6    = f3   * (long) g6;
        long f3g7_38 = f3_2 * (long) g7_19;
        long f3g8_19 = f3   * (long) g8_19;
        long f3g9_38 = f3_2 * (long) g9_19;
        long f4g0    = f4   * (long) g0;
        long f4g1    = f4   * (long) g1;
        long f4g2    = f4   * (long) g2;
        long f4g3    = f4   * (long) g3;
        long f4g4    = f4   * (long) g4;
        long f4g5    = f4   * (long) g5;
        long f4g6_19 = f4   * (long) g6_19;
        long f4g7_19 = f4   * (long) g7_19;
        long f4g8_19 = f4   * (long) g8_19;
        long f4g9_19 = f4   * (long) g9_19;
        long f5g0    = f5   * (long) g0;
        long f5g1_2  = f5_2 * (long) g1;
        long f5g2    = f5   * (long) g2;
        long f5g3_2  = f5_2 * (long) g3;
        long f5g4    = f5   * (long) g4;
        long f5g5_38 = f5_2 * (long) g5_19;
        long f5g6_19 = f5   * (long) g6_19;
        long f5g7_38 = f5_2 * (long) g7_19;
        long f5g8_19 = f5   * (long) g8_19;
        long f5g9_38 = f5_2 * (long) g9_19;
        long f6g0    = f6   * (long) g0;
        long f6g1    = f6   * (long) g1;
        long f6g2    = f6   * (long) g2;
        long f6g3    = f6   * (long) g3;
        long f6g4_19 = f6   * (long) g4_19;
        long f6g5_19 = f6   * (long) g5_19;
        long f6g6_19 = f6   * (long) g6_19;
        long f6g7_19 = f6   * (long) g7_19;
        long f6g8_19 = f6   * (long) g8_19;
        long f6g9_19 = f6   * (long) g9_19;
        long f7g0    = f7   * (long) g0;
        long f7g1_2  = f7_2 * (long) g1;
        long f7g2    = f7   * (long) g2;
        long f7g3_38 = f7_2 * (long) g3_19;
        long f7g4_19 = f7   * (long) g4_19;
        long f7g5_38 = f7_2 * (long) g5_19;
        long f7g6_19 = f7   * (long) g6_19;
        long f7g7_38 = f7_2 * (long) g7_19;
        long f7g8_19 = f7   * (long) g8_19;
        long f7g9_38 = f7_2 * (long) g9_19;
        long f8g0    = f8   * (long) g0;
        long f8g1    = f8   * (long) g1;
        long f8g2_19 = f8   * (long) g2_19;
        long f8g3_19 = f8   * (long) g3_19;
        long f8g4_19 = f8   * (long) g4_19;
        long f8g5_19 = f8   * (long) g5_19;
        long f8g6_19 = f8   * (long) g6_19;
        long f8g7_19 = f8   * (long) g7_19;
        long f8g8_19 = f8   * (long) g8_19;
        long f8g9_19 = f8   * (long) g9_19;
        long f9g0    = f9   * (long) g0;
        long f9g1_38 = f9_2 * (long) g1_19;
        long f9g2_19 = f9   * (long) g2_19;
        long f9g3_38 = f9_2 * (long) g3_19;
        long f9g4_19 = f9   * (long) g4_19;
        long f9g5_38 = f9_2 * (long) g5_19;
        long f9g6_19 = f9   * (long) g6_19;
        long f9g7_38 = f9_2 * (long) g7_19;
        long f9g8_19 = f9   * (long) g8_19;
        long f9g9_38 = f9_2 * (long) g9_19;
        long h0 = f0g0+f1g9_38+f2g8_19+f3g7_38+f4g6_19+f5g5_38+f6g4_19+f7g3_38+f8g2_19+f9g1_38;
        long h1 = f0g1+f1g0   +f2g9_19+f3g8_19+f4g7_19+f5g6_19+f6g5_19+f7g4_19+f8g3_19+f9g2_19;
        long h2 = f0g2+f1g1_2 +f2g0   +f3g9_38+f4g8_19+f5g7_38+f6g6_19+f7g5_38+f8g4_19+f9g3_38;
        long h3 = f0g3+f1g2   +f2g1   +f3g0   +f4g9_19+f5g8_19+f6g7_19+f7g6_19+f8g5_19+f9g4_19;
        long h4 = f0g4+f1g3_2 +f2g2   +f3g1_2 +f4g0   +f5g9_38+f6g8_19+f7g7_38+f8g6_19+f9g5_38;
        long h5 = f0g5+f1g4   +f2g3   +f3g2   +f4g1   +f5g0   +f6g9_19+f7g8_19+f8g7_19+f9g6_19;
        long h6 = f0g6+f1g5_2 +f2g4   +f3g3_2 +f4g2   +f5g1_2 +f6g0   +f7g9_38+f8g8_19+f9g7_38;
        long h7 = f0g7+f1g6   +f2g5   +f3g4   +f4g3   +f5g2   +f6g1   +f7g0   +f8g9_19+f9g8_19;
        long h8 = f0g8+f1g7_2 +f2g6   +f3g5_2 +f4g4   +f5g3_2 +f6g2   +f7g1_2 +f8g0   +f9g9_38;
        long h9 = f0g9+f1g8   +f2g7   +f3g6   +f4g5   +f5g4   +f6g3   +f7g2   +f8g1   +f9g0   ;
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

        /*
        |h0| <= (1.65*1.65*2^52*(1+19+19+19+19)+1.65*1.65*2^50*(38+38+38+38+38))
          i.e. |h0| <= 1.4*2^60; narrower ranges for h2, h4, h6, h8
        |h1| <= (1.65*1.65*2^51*(1+1+19+19+19+19+19+19+19+19))
          i.e. |h1| <= 1.7*2^59; narrower ranges for h3, h5, h7, h9
        */

        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;
        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;
        /* |h0| <= 2^25 */
        /* |h4| <= 2^25 */
        /* |h1| <= 1.71*2^59 */
        /* |h5| <= 1.71*2^59 */

        carry1 = (h1 + (long) (1<<24)) >> 25; h2 += carry1; h1 -= carry1 << 25;
        carry5 = (h5 + (long) (1<<24)) >> 25; h6 += carry5; h5 -= carry5 << 25;
        /* |h1| <= 2^24; from now on fits into int32 */
        /* |h5| <= 2^24; from now on fits into int32 */
        /* |h2| <= 1.41*2^60 */
        /* |h6| <= 1.41*2^60 */

        carry2 = (h2 + (long) (1<<25)) >> 26; h3 += carry2; h2 -= carry2 << 26;
        carry6 = (h6 + (long) (1<<25)) >> 26; h7 += carry6; h6 -= carry6 << 26;
        /* |h2| <= 2^25; from now on fits into int32 unchanged */
        /* |h6| <= 2^25; from now on fits into int32 unchanged */
        /* |h3| <= 1.71*2^59 */
        /* |h7| <= 1.71*2^59 */

        carry3 = (h3 + (long) (1<<24)) >> 25; h4 += carry3; h3 -= carry3 << 25;
        carry7 = (h7 + (long) (1<<24)) >> 25; h8 += carry7; h7 -= carry7 << 25;
        /* |h3| <= 2^24; from now on fits into int32 unchanged */
        /* |h7| <= 2^24; from now on fits into int32 unchanged */
        /* |h4| <= 1.72*2^34 */
        /* |h8| <= 1.41*2^60 */

        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;
        carry8 = (h8 + (long) (1<<25)) >> 26; h9 += carry8; h8 -= carry8 << 26;
        /* |h4| <= 2^25; from now on fits into int32 unchanged */
        /* |h8| <= 2^25; from now on fits into int32 unchanged */
        /* |h5| <= 1.01*2^24 */
        /* |h9| <= 1.71*2^59 */

        carry9 = (h9 + (long) (1<<24)) >> 25; h0 += carry9 * 19; h9 -= carry9 << 25;
        /* |h9| <= 2^24; from now on fits into int32 unchanged */
        /* |h0| <= 1.1*2^39 */

        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;
        /* |h0| <= 2^25; from now on fits into int32 unchanged */
        /* |h1| <= 1.01*2^24 */

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
     * h = f * f
     * Can overlap h with f.
     *
     * Preconditions:
     *    |f| bounded by 1.65*2^26,1.65*2^25,1.65*2^26,1.65*2^25,etc.
     *
     * Postconditions:
     *    |h| bounded by 1.01*2^25,1.01*2^24,1.01*2^25,1.01*2^24,etc.
     *
     * See {@link Ed25519FieldElement#multiply(FieldElement)} for discussion
     * of implementation strategy.
     */
    public FieldElement square() {
        int f0 = t[0];
        int f1 = t[1];
        int f2 = t[2];
        int f3 = t[3];
        int f4 = t[4];
        int f5 = t[5];
        int f6 = t[6];
        int f7 = t[7];
        int f8 = t[8];
        int f9 = t[9];
        int f0_2 = 2 * f0;
        int f1_2 = 2 * f1;
        int f2_2 = 2 * f2;
        int f3_2 = 2 * f3;
        int f4_2 = 2 * f4;
        int f5_2 = 2 * f5;
        int f6_2 = 2 * f6;
        int f7_2 = 2 * f7;
        int f5_38 = 38 * f5; /* 1.959375*2^30 */
        int f6_19 = 19 * f6; /* 1.959375*2^30 */
        int f7_38 = 38 * f7; /* 1.959375*2^30 */
        int f8_19 = 19 * f8; /* 1.959375*2^30 */
        int f9_38 = 38 * f9; /* 1.959375*2^30 */
        long f0f0    = f0   * (long) f0;
        long f0f1_2  = f0_2 * (long) f1;
        long f0f2_2  = f0_2 * (long) f2;
        long f0f3_2  = f0_2 * (long) f3;
        long f0f4_2  = f0_2 * (long) f4;
        long f0f5_2  = f0_2 * (long) f5;
        long f0f6_2  = f0_2 * (long) f6;
        long f0f7_2  = f0_2 * (long) f7;
        long f0f8_2  = f0_2 * (long) f8;
        long f0f9_2  = f0_2 * (long) f9;
        long f1f1_2  = f1_2 * (long) f1;
        long f1f2_2  = f1_2 * (long) f2;
        long f1f3_4  = f1_2 * (long) f3_2;
        long f1f4_2  = f1_2 * (long) f4;
        long f1f5_4  = f1_2 * (long) f5_2;
        long f1f6_2  = f1_2 * (long) f6;
        long f1f7_4  = f1_2 * (long) f7_2;
        long f1f8_2  = f1_2 * (long) f8;
        long f1f9_76 = f1_2 * (long) f9_38;
        long f2f2    = f2   * (long) f2;
        long f2f3_2  = f2_2 * (long) f3;
        long f2f4_2  = f2_2 * (long) f4;
        long f2f5_2  = f2_2 * (long) f5;
        long f2f6_2  = f2_2 * (long) f6;
        long f2f7_2  = f2_2 * (long) f7;
        long f2f8_38 = f2_2 * (long) f8_19;
        long f2f9_38 = f2   * (long) f9_38;
        long f3f3_2  = f3_2 * (long) f3;
        long f3f4_2  = f3_2 * (long) f4;
        long f3f5_4  = f3_2 * (long) f5_2;
        long f3f6_2  = f3_2 * (long) f6;
        long f3f7_76 = f3_2 * (long) f7_38;
        long f3f8_38 = f3_2 * (long) f8_19;
        long f3f9_76 = f3_2 * (long) f9_38;
        long f4f4    = f4   * (long) f4;
        long f4f5_2  = f4_2 * (long) f5;
        long f4f6_38 = f4_2 * (long) f6_19;
        long f4f7_38 = f4   * (long) f7_38;
        long f4f8_38 = f4_2 * (long) f8_19;
        long f4f9_38 = f4   * (long) f9_38;
        long f5f5_38 = f5   * (long) f5_38;
        long f5f6_38 = f5_2 * (long) f6_19;
        long f5f7_76 = f5_2 * (long) f7_38;
        long f5f8_38 = f5_2 * (long) f8_19;
        long f5f9_76 = f5_2 * (long) f9_38;
        long f6f6_19 = f6   * (long) f6_19;
        long f6f7_38 = f6   * (long) f7_38;
        long f6f8_38 = f6_2 * (long) f8_19;
        long f6f9_38 = f6   * (long) f9_38;
        long f7f7_38 = f7   * (long) f7_38;
        long f7f8_38 = f7_2 * (long) f8_19;
        long f7f9_76 = f7_2 * (long) f9_38;
        long f8f8_19 = f8   * (long) f8_19;
        long f8f9_38 = f8   * (long) f9_38;
        long f9f9_38 = f9   * (long) f9_38;
        long h0 = f0f0  +f1f9_76+f2f8_38+f3f7_76+f4f6_38+f5f5_38;
        long h1 = f0f1_2+f2f9_38+f3f8_38+f4f7_38+f5f6_38;
        long h2 = f0f2_2+f1f1_2 +f3f9_76+f4f8_38+f5f7_76+f6f6_19;
        long h3 = f0f3_2+f1f2_2 +f4f9_38+f5f8_38+f6f7_38;
        long h4 = f0f4_2+f1f3_4 +f2f2   +f5f9_76+f6f8_38+f7f7_38;
        long h5 = f0f5_2+f1f4_2 +f2f3_2 +f6f9_38+f7f8_38;
        long h6 = f0f6_2+f1f5_4 +f2f4_2 +f3f3_2 +f7f9_76+f8f8_19;
        long h7 = f0f7_2+f1f6_2 +f2f5_2 +f3f4_2 +f8f9_38;
        long h8 = f0f8_2+f1f7_4 +f2f6_2 +f3f5_4 +f4f4   +f9f9_38;
        long h9 = f0f9_2+f1f8_2 +f2f7_2 +f3f6_2 +f4f5_2;
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

        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;
        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;

        carry1 = (h1 + (long) (1<<24)) >> 25; h2 += carry1; h1 -= carry1 << 25;
        carry5 = (h5 + (long) (1<<24)) >> 25; h6 += carry5; h5 -= carry5 << 25;

        carry2 = (h2 + (long) (1<<25)) >> 26; h3 += carry2; h2 -= carry2 << 26;
        carry6 = (h6 + (long) (1<<25)) >> 26; h7 += carry6; h6 -= carry6 << 26;

        carry3 = (h3 + (long) (1<<24)) >> 25; h4 += carry3; h3 -= carry3 << 25;
        carry7 = (h7 + (long) (1<<24)) >> 25; h8 += carry7; h7 -= carry7 << 25;

        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;
        carry8 = (h8 + (long) (1<<25)) >> 26; h9 += carry8; h8 -= carry8 << 26;

        carry9 = (h9 + (long) (1<<24)) >> 25; h0 += carry9 * 19; h9 -= carry9 << 25;

        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;

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
     * h = 2 * f * f
     * Can overlap h with f.
     *
     * Preconditions:
     *    |f| bounded by 1.65*2^26,1.65*2^25,1.65*2^26,1.65*2^25,etc.
     *
     * Postconditions:
     *    |h| bounded by 1.01*2^25,1.01*2^24,1.01*2^25,1.01*2^24,etc.
     *
     * See {@link Ed25519FieldElement#multiply(FieldElement)} for discussion
     * of implementation strategy.
     */
    public FieldElement squareAndDouble() {
        int f0 = t[0];
        int f1 = t[1];
        int f2 = t[2];
        int f3 = t[3];
        int f4 = t[4];
        int f5 = t[5];
        int f6 = t[6];
        int f7 = t[7];
        int f8 = t[8];
        int f9 = t[9];
        int f0_2 = 2 * f0;
        int f1_2 = 2 * f1;
        int f2_2 = 2 * f2;
        int f3_2 = 2 * f3;
        int f4_2 = 2 * f4;
        int f5_2 = 2 * f5;
        int f6_2 = 2 * f6;
        int f7_2 = 2 * f7;
        int f5_38 = 38 * f5; /* 1.959375*2^30 */
        int f6_19 = 19 * f6; /* 1.959375*2^30 */
        int f7_38 = 38 * f7; /* 1.959375*2^30 */
        int f8_19 = 19 * f8; /* 1.959375*2^30 */
        int f9_38 = 38 * f9; /* 1.959375*2^30 */
        long f0f0    = f0   * (long) f0;
        long f0f1_2  = f0_2 * (long) f1;
        long f0f2_2  = f0_2 * (long) f2;
        long f0f3_2  = f0_2 * (long) f3;
        long f0f4_2  = f0_2 * (long) f4;
        long f0f5_2  = f0_2 * (long) f5;
        long f0f6_2  = f0_2 * (long) f6;
        long f0f7_2  = f0_2 * (long) f7;
        long f0f8_2  = f0_2 * (long) f8;
        long f0f9_2  = f0_2 * (long) f9;
        long f1f1_2  = f1_2 * (long) f1;
        long f1f2_2  = f1_2 * (long) f2;
        long f1f3_4  = f1_2 * (long) f3_2;
        long f1f4_2  = f1_2 * (long) f4;
        long f1f5_4  = f1_2 * (long) f5_2;
        long f1f6_2  = f1_2 * (long) f6;
        long f1f7_4  = f1_2 * (long) f7_2;
        long f1f8_2  = f1_2 * (long) f8;
        long f1f9_76 = f1_2 * (long) f9_38;
        long f2f2    = f2   * (long) f2;
        long f2f3_2  = f2_2 * (long) f3;
        long f2f4_2  = f2_2 * (long) f4;
        long f2f5_2  = f2_2 * (long) f5;
        long f2f6_2  = f2_2 * (long) f6;
        long f2f7_2  = f2_2 * (long) f7;
        long f2f8_38 = f2_2 * (long) f8_19;
        long f2f9_38 = f2   * (long) f9_38;
        long f3f3_2  = f3_2 * (long) f3;
        long f3f4_2  = f3_2 * (long) f4;
        long f3f5_4  = f3_2 * (long) f5_2;
        long f3f6_2  = f3_2 * (long) f6;
        long f3f7_76 = f3_2 * (long) f7_38;
        long f3f8_38 = f3_2 * (long) f8_19;
        long f3f9_76 = f3_2 * (long) f9_38;
        long f4f4    = f4   * (long) f4;
        long f4f5_2  = f4_2 * (long) f5;
        long f4f6_38 = f4_2 * (long) f6_19;
        long f4f7_38 = f4   * (long) f7_38;
        long f4f8_38 = f4_2 * (long) f8_19;
        long f4f9_38 = f4   * (long) f9_38;
        long f5f5_38 = f5   * (long) f5_38;
        long f5f6_38 = f5_2 * (long) f6_19;
        long f5f7_76 = f5_2 * (long) f7_38;
        long f5f8_38 = f5_2 * (long) f8_19;
        long f5f9_76 = f5_2 * (long) f9_38;
        long f6f6_19 = f6   * (long) f6_19;
        long f6f7_38 = f6   * (long) f7_38;
        long f6f8_38 = f6_2 * (long) f8_19;
        long f6f9_38 = f6   * (long) f9_38;
        long f7f7_38 = f7   * (long) f7_38;
        long f7f8_38 = f7_2 * (long) f8_19;
        long f7f9_76 = f7_2 * (long) f9_38;
        long f8f8_19 = f8   * (long) f8_19;
        long f8f9_38 = f8   * (long) f9_38;
        long f9f9_38 = f9   * (long) f9_38;
        long h0 = f0f0  +f1f9_76+f2f8_38+f3f7_76+f4f6_38+f5f5_38;
        long h1 = f0f1_2+f2f9_38+f3f8_38+f4f7_38+f5f6_38;
        long h2 = f0f2_2+f1f1_2 +f3f9_76+f4f8_38+f5f7_76+f6f6_19;
        long h3 = f0f3_2+f1f2_2 +f4f9_38+f5f8_38+f6f7_38;
        long h4 = f0f4_2+f1f3_4 +f2f2   +f5f9_76+f6f8_38+f7f7_38;
        long h5 = f0f5_2+f1f4_2 +f2f3_2 +f6f9_38+f7f8_38;
        long h6 = f0f6_2+f1f5_4 +f2f4_2 +f3f3_2 +f7f9_76+f8f8_19;
        long h7 = f0f7_2+f1f6_2 +f2f5_2 +f3f4_2 +f8f9_38;
        long h8 = f0f8_2+f1f7_4 +f2f6_2 +f3f5_4 +f4f4   +f9f9_38;
        long h9 = f0f9_2+f1f8_2 +f2f7_2 +f3f6_2 +f4f5_2;
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

        h0 += h0;
        h1 += h1;
        h2 += h2;
        h3 += h3;
        h4 += h4;
        h5 += h5;
        h6 += h6;
        h7 += h7;
        h8 += h8;
        h9 += h9;

        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;
        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;

        carry1 = (h1 + (long) (1<<24)) >> 25; h2 += carry1; h1 -= carry1 << 25;
        carry5 = (h5 + (long) (1<<24)) >> 25; h6 += carry5; h5 -= carry5 << 25;

        carry2 = (h2 + (long) (1<<25)) >> 26; h3 += carry2; h2 -= carry2 << 26;
        carry6 = (h6 + (long) (1<<25)) >> 26; h7 += carry6; h6 -= carry6 << 26;

        carry3 = (h3 + (long) (1<<24)) >> 25; h4 += carry3; h3 -= carry3 << 25;
        carry7 = (h7 + (long) (1<<24)) >> 25; h8 += carry7; h7 -= carry7 << 25;

        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;
        carry8 = (h8 + (long) (1<<25)) >> 26; h9 += carry8; h8 -= carry8 << 26;

        carry9 = (h9 + (long) (1<<24)) >> 25; h0 += carry9 * 19; h9 -= carry9 << 25;

        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;

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

    public FieldElement invert() {
        FieldElement t0, t1, t2, t3;

        // z2 = z1^2^1
        t0 = square();
        for (int i = 1; i < 1; ++i) { // Don't remove this
            t0 = t0.square();
        }

        // z8 = z2^2^2;
        t1 = t0.square();
        for (int i = 1; i < 2; ++i) {
            t1 = t1.square();
        }

        // z9 = z1*z8
        t1 = multiply(t1);

        // z11 = z2*z9
        t0 = t0.multiply(t1);

        // z22 = z11^2^1
        t2 = t0.square();
        for (int i = 1; i < 1; ++i) { // Don't remove this
            t2 = t2.square();
        }

        // z_5_0 = z9*z22
        t1 = t1.multiply(t2);

        // z_10_5 = z_5_0^2^5
        t2 = t1.square();
        for (int i = 1; i < 5; ++i) {
            t2 = t2.square();
        }

        // z_10_0 = z_10_5*z_5_0
        t1 = t2.multiply(t1);

        // z_20_10 = z_10_0^2^10
        t2 = t1.square();
        for (int i = 1; i < 10; ++i) {
            t2 = t2.square();
        }

        // z_20_0 = z_20_10*z_10_0
        t2 = t2.multiply(t1);

        // z_40_20 = z_20_0^2^20
        t3 = t2.square();
        for (int i = 1; i < 20; ++i) {
            t3 = t3.square();
        }

        // z_40_0 = z_40_20*z_20_0
        t2 = t3.multiply(t2);

        // z_50_10 = z_40_0^2^10
        t2 = t2.square();
        for (int i = 1; i < 10; ++i) {
            t2 = t2.square();
        }

        // z_50_0 = z_50_10*z_10_0
        t1 = t2.multiply(t1);

        // z_100_50 = z_50_0^2^50
        t2 = t1.square();
        for (int i = 1; i < 50; ++i) {
            t2 = t2.square();
        }

        // z_100_0 = z_100_50*z_50_0
        t2 = t2.multiply(t1);

        // z_200_100 = z_100_0^2^100
        t3 = t2.square();
        for (int i = 1; i < 100; ++i) {
            t3 = t3.square();
        }

        // z_200_0 = z_200_100*z_100_0
        t2 = t3.multiply(t2);

        // z_250_50 = z_200_0^2^50
        t2 = t2.square();
        for (int i = 1; i < 50; ++i) {
            t2 = t2.square();
        }

        // z_250_0 = z_250_50*z_50_0
        t1 = t2.multiply(t1);

        // z_255_5 = z_250_0^2^5
        t1 = t1.square();
        for (int i = 1; i < 5; ++i) {
            t1 = t1.square();
        }

        // z_255_21 = z_255_5*z11
        return t1.multiply(t0);
    }

    public FieldElement pow22523() {
        FieldElement t0, t1, t2;

        // z2 = z1^2^1
        t0 = square();
        for (int i = 1; i < 1; ++i) { // Don't remove this
            t0 = t0.square();
        }

        // z8 = z2^2^2;
        t1 = t0.square();
        for (int i = 1; i < 2; ++i) {
            t1 = t1.square();
        }

        // z9 = z1*z8
        t1 = multiply(t1);

        // z11 = z2*z9
        t0 = t0.multiply(t1);

        // z22 = z11^2^1
        t0 = t0.square();
        for (int i = 1; i < 1; ++i) { // Don't remove this
            t0 = t0.square();
        }

        // z_5_0 = z9*z22
        t0 = t1.multiply(t0);

        // z_10_5 = z_5_0^2^5
        t1 = t0.square();
        for (int i = 1; i < 5; ++i) {
            t1 = t1.square();
        }

        // z_10_0 = z_10_5*z_5_0
        t0 = t1.multiply(t0);

        // z_20_10 = z_10_0^2^10
        t1 = t0.square();
        for (int i = 1; i < 10; ++i) {
            t1 = t1.square();
        }

        // z_20_0 = z_20_10*z_10_0
        t1 = t1.multiply(t0);

        // z_40_20 = z_20_0^2^20
        t2 = t1.square();
        for (int i = 1; i < 20; ++i) {
            t2 = t2.square();
        }

        // z_40_0 = z_40_20*z_20_0
        t1 = t2.multiply(t1);

        // z_50_10 = z_40_0^2^10
        t1 = t1.square();
        for (int i = 1; i < 10; ++i) {
            t1 = t1.square();
        }

        // z_50_0 = z_50_10*z_10_0
        t0 = t1.multiply(t0);

        // z_100_50 = z_50_0^2^50
        t1 = t0.square();
        for (int i = 1; i < 50; ++i) {
            t1 = t1.square();
        }

        // z_100_0 = z_100_50*z_50_0
        t1 = t1.multiply(t0);

        // z_200_100 = z_100_0^2^100
        t2 = t1.square();
        for (int i = 1; i < 100; ++i) {
            t2 = t2.square();
        }

        // z_200_0 = z_200_100*z_100_0
        t1 = t2.multiply(t1);

        // z_250_50 = z_200_0^2^50
        t1 = t1.square();
        for (int i = 1; i < 50; ++i) {
            t1 = t1.square();
        }

        // z_250_0 = z_250_50*z_50_0
        t0 = t1.multiply(t0);

        // z_252_2 = z_250_0^2^2
        t0 = t0.square();
        for (int i = 1; i < 2; ++i) {
            t0 = t0.square();
        }

        // z_252_3 = z_252_2*z1
        return multiply(t0);
    }

    @Override
    public int hashCode() {
        return t.hashCode(); // TODO should this be something else?
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Ed25519FieldElement))
            return false;
        Ed25519FieldElement fe = (Ed25519FieldElement) obj;
        return 1==Utils.equal(toByteArray(), fe.toByteArray());
    }

    @Override
    public String toString() {
        return "[Ed25519FieldElement val="+Utils.bytesToHex(toByteArray())+"]";
    }
}
