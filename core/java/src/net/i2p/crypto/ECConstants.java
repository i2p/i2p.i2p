package net.i2p.crypto;

import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.Security;
import java.security.spec.ECField;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.EllipticCurve;

import net.i2p.util.NativeBigInteger;

/**
 * Constants for elliptic curves, from NIST FIPS 186-4 (2013) / ANSI X9.62
 *
 * @since 0.9.9
 */
final class ECConstants {

    private static final boolean DEBUG = false;

    private static void log(String s) {
        log(s, null);
    }

    private static void log(String s, Throwable t) {
        if (DEBUG) {
            System.out.println("ECConstants: " + s);
            if (t != null)
                t.printStackTrace();
        }
    }

    private static final boolean BC_AVAILABLE;

    static {
        boolean loaded;
        if (Security.getProvider("BC") == null) {
            try {
                Class<?> cls = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
                Constructor<?> con = cls.getConstructor();
                Provider bc = (Provider)con.newInstance();
                Security.addProvider(bc);
                log("Added BC provider");
                loaded = true;
            } catch (Exception e) {
                log("Unable to add BC provider", e);
                loaded = false;
            }
        } else {
            log("BC provider already loaded");
            loaded = true;
        }
        BC_AVAILABLE = loaded;
    }

    public static boolean isBCAvailable() { return BC_AVAILABLE; }

    private static class ECParms {
        public final String ps, ns, ss, bs, gxs, gys;
        private static final BigInteger A = new NativeBigInteger("-3");
        private static final int H = 1;

        /**
         *  P and N in decimal, no spaces;
         *  Seed, B, Gx, Gy in hex, spaces allowed
         */
        public ECParms(String pss, String nss, String sss, String bss, String gxss, String gyss) {
            ps = pss; ns = nss; ss = sss; bs = bss; gxs = gxss; gys = gyss;
        }

        public ECParameterSpec genSpec() {
            BigInteger pb = new NativeBigInteger(ps);
            BigInteger nb = new NativeBigInteger(ns);
            BigInteger sb = new NativeBigInteger(ss.replace(" ", ""), 16);
            BigInteger bb = new NativeBigInteger(bs.replace(" ", ""), 16);
            BigInteger gxb = new NativeBigInteger(gxs.replace(" ", ""), 16);
            BigInteger gyb = new NativeBigInteger(gys.replace(" ", ""), 16);
            BigInteger ab = new NativeBigInteger(A.mod(pb));
            ECField field = new ECFieldFp(pb);
            EllipticCurve curve = new EllipticCurve(field, ab, bb, sb.toByteArray());
            ECPoint g = new ECPoint(gxb, gyb);
            return new ECParameterSpec(curve, g, nb, H);
        }
    }

    /*

    D.1.2 Curves over Prime Fields

    For each prime p, a pseudo-random curve
    E : y**2 = x**3 -3x +b (mod p)
    of prime order n is listed 4. (Thus, for these curves, the cofactor is always h = 1.) The following
    parameters are given:

    The selection a a = -3 for the coefficient of x was made for reasons of efficiency; see IEEE Std 1363-2000.

    * The prime modulus p
    * The order n
    * The 160-bit input seed SEED to the SHA-1 based algorithm (i.e., the domain parameter
       seed)
    * The output c of the SHA-1 based algorithm
    * The coefficient b (satisfying b**2 c = -27 (mod p))
    * The base point x coordinate G x
    * The base point y coordinate G y
    The integers p and n are given in decimal form; bit strings and field elements are given in
    hexadecimal.
    */

    /*
    D.1.2.1 Curve P-192

    p= 6277101735386680763835789423207666416083908700390324961279
    n= 6277101735386680763835789423176059013767194773182842284081
    SEED = 3045ae6f c8422f64 ed579528 d38120ea e12196d5
    c= 3099d2bb bfcb2538 542dcd5f b078b6ef 5f3d6fe2 c745de65
    b= 64210519 e59c80e7 0fa7e9ab 72243049 feb8deec c146b9b1
    Gx= 188da80e b03090f6 7cbf20eb 43a18800 f4ff0afd 82ff1012
    Gy= 07192b95 ffc8da78 631011ed 6b24cdd5 73f977a1 1e794811
    */

    /*
    private static final ECParms PARM_P192 = new ECParms(
        // P N Seed B Gx Gy
            "6277101735386680763835789423207666416083908700390324961279",
            "6277101735386680763835789423176059013767194773182842284081",
            "3045ae6f c8422f64 ed579528 d38120ea e12196d5",
            "64210519 e59c80e7 0fa7e9ab 72243049 feb8deec c146b9b1",
            "188da80e b03090f6 7cbf20eb 43a18800 f4ff0afd 82ff1012",
            "07192b95 ffc8da78 631011ed 6b24cdd5 73f977a1 1e794811"
    );
    */


    /*
    D.1.2.3 Curve P-256

    p=
    1157920892103562487626974469494075735300861434152903141955
    33631308867097853951
    n=
    115792089210356248762697446949407573529996955224135760342
    422259061068512044369
    SEED = c49d3608 86e70493 6a6678e1 139d26b7 819f7e90
    c=
    7efba166 2985be94 03cb055c 75d4f7e0 ce8d84a9 c5114abc
    af317768 0104fa0d
    b=
    5ac635d8 aa3a93e7 b3ebbd55 769886bc 651d06b0 cc53b0f6
    3bce3c3e 27d2604b
    Gx=
    6b17d1f2 e12c4247 f8bce6e5 63a440f2 77037d81 2deb33a0
    f4a13945 d898c296
    Gy=
    4fe342e2 fe1a7f9b 8ee7eb4a 7c0f9e16 2bce3357 6b315ece
    cbb64068 37bf51f5
    */

    private static final ECParms PARM_P256 = new ECParms(
        // P N Seed B Gx Gy
            "1157920892103562487626974469494075735300861434152903141955" +
            "33631308867097853951",
            "115792089210356248762697446949407573529996955224135760342" +
            "422259061068512044369",
            "c49d3608 86e70493 6a6678e1 139d26b7 819f7e90",
            "5ac635d8 aa3a93e7 b3ebbd55 769886bc 651d06b0 cc53b0f6" +
            "3bce3c3e 27d2604b",
            "6b17d1f2 e12c4247 f8bce6e5 63a440f2 77037d81 2deb33a0" +
            "f4a13945 d898c296",
            "4fe342e2 fe1a7f9b 8ee7eb4a 7c0f9e16 2bce3357 6b315ece" +
            "cbb64068 37bf51f5"
    );

    /*
    D.1.2.4 Curve P-384

    p=
    3940200619639447921227904010014361380507973927046544666794
    8293404245721771496870329047266088258938001861606973112319
    n=
    3940200619639447921227904010014361380507973927046544666794
    6905279627659399113263569398956308152294913554433653942643
    SEED = a335926a a319a27a 1d00896a 6773a482 7acdac73
    c=
    79d1e655 f868f02f ff48dcde e14151dd b80643c1 406d0ca1
    0dfe6fc5 2009540a 495e8042 ea5f744f 6e184667 cc722483
    b=
    b3312fa7 e23ee7e4 988e056b e3f82d19 181d9c6e fe814112
    0314088f 5013875a c656398d 8a2ed19d 2a85c8ed d3ec2aef
    Gx=
    aa87ca22 be8b0537 8eb1c71e f320ad74 6e1d3b62 8ba79b98
    59f741e0 82542a38 5502f25d bf55296c 3a545e38 72760ab7
    G y=
    3617de4a 96262c6f 5d9e98bf 9292dc29 f8f41dbd 289a147c
    e9da3113 b5f0b8c0 0a60b1ce 1d7e819d 7a431d7c 90ea0e5f
    */

    private static final ECParms PARM_P384 = new ECParms(
        // P N Seed B Gx Gy
            "3940200619639447921227904010014361380507973927046544666794" +
            "8293404245721771496870329047266088258938001861606973112319",
            "3940200619639447921227904010014361380507973927046544666794" +
            "6905279627659399113263569398956308152294913554433653942643",
            "a335926a a319a27a 1d00896a 6773a482 7acdac73",
            "b3312fa7 e23ee7e4 988e056b e3f82d19 181d9c6e fe814112" +
            "0314088f 5013875a c656398d 8a2ed19d 2a85c8ed d3ec2aef",
            "aa87ca22 be8b0537 8eb1c71e f320ad74 6e1d3b62 8ba79b98" +
            "59f741e0 82542a38 5502f25d bf55296c 3a545e38 72760ab7",
            "3617de4a 96262c6f 5d9e98bf 9292dc29 f8f41dbd 289a147c" +
            "e9da3113 b5f0b8c0 0a60b1ce 1d7e819d 7a431d7c 90ea0e5f"
    );

    /*
    D.1.2.5 Curve P-521

    p=
    686479766013060971498190079908139321726943530014330540939
    446345918554318339765605212255964066145455497729631139148
    0858037121987999716643812574028291115057151
    n=
    686479766013060971498190079908139321726943530014330540939
    446345918554318339765539424505774633321719753296399637136
    3321113864768612440380340372808892707005449
    SEED = d09e8800 291cb853 96cc6717 393284aa a0da64ba
    c=
    0b4 8bfa5f42 0a349495 39d2bdfc 264eeeeb 077688e4
    4fbf0ad8 f6d0edb3 7bd6b533 28100051 8e19f1b9 ffbe0fe9
    ed8a3c22 00b8f875 e523868c 70c1e5bf 55bad637
    b=
    051 953eb961 8e1c9a1f 929a21a0 b68540ee a2da725b
    99b315f3 b8b48991 8ef109e1 56193951 ec7e937b 1652c0bd
    3bb1bf07 3573df88 3d2c34f1 ef451fd4 6b503f00
    Gx=
    c6 858e06b7 0404e9cd 9e3ecb66 2395b442 9c648139
    053fb521 f828af60 6b4d3dba a14b5e77 efe75928 fe1dc127
    a2ffa8de 3348b3c1 856a429b f97e7e31 c2e5bd66
    Gy=
    118 39296a78 9a3bc004 5c8a5fb4 2c7d1bd9 98f54449
    579b4468 17afbd17 273e662c 97ee7299 5ef42640 c550b901
    3fad0761 353c7086 a272c240 88be9476 9fd16650
    */

    private static final ECParms PARM_P521 = new ECParms(
            "686479766013060971498190079908139321726943530014330540939" +
            "446345918554318339765605212255964066145455497729631139148" +
            "0858037121987999716643812574028291115057151",
            "686479766013060971498190079908139321726943530014330540939" +
            "446345918554318339765539424505774633321719753296399637136" +
            "3321113864768612440380340372808892707005449",
            "d09e8800 291cb853 96cc6717 393284aa a0da64ba",
            "051 953eb961 8e1c9a1f 929a21a0 b68540ee a2da725b" +
            "99b315f3 b8b48991 8ef109e1 56193951 ec7e937b 1652c0bd" +
            "3bb1bf07 3573df88 3d2c34f1 ef451fd4 6b503f00",
            "c6 858e06b7 0404e9cd 9e3ecb66 2395b442 9c648139" +
            "053fb521 f828af60 6b4d3dba a14b5e77 efe75928 fe1dc127" +
            "a2ffa8de 3348b3c1 856a429b f97e7e31 c2e5bd66",
            "118 39296a78 9a3bc004 5c8a5fb4 2c7d1bd9 98f54449" +
            "579b4468 17afbd17 273e662c 97ee7299 5ef42640 c550b901" +
            "3fad0761 353c7086 a272c240 88be9476 9fd16650"
    );

    /**
     *  Generate a spec from a curve name
     *  @return null if fail
     */
    private static ECParameterSpec genSpec(String name) {
        // convert the ECGenParameterSpecs to ECParameterSpecs for several reasons:
        // 1) to check availability
        // 2) efficiency
        // 3) SigUtil must cast the AlgorithmParameterSpec to a ECParameterSpec
        //    to convert a I2P key to a Java key. Sadly, a ECGenParameterSpec
        //    is not a ECParameterSpec.
        try {
            AlgorithmParameters ap;
            try {
                ap = AlgorithmParameters.getInstance("EC");
            } catch (GeneralSecurityException e) {
                if (BC_AVAILABLE) {
                    log("Named curve " + name + " is not available, trying BC", e);
                    ap = AlgorithmParameters.getInstance("EC", "BC");
                    log("Fallback to BC worked for named curve " + name);
                } else {
                    throw e;
                }
            }
            ECGenParameterSpec ecgps = new ECGenParameterSpec(name);
            ap.init(ecgps);
            ECParameterSpec rv = ap.getParameterSpec(ECParameterSpec.class);
            log("Named curve " + name + " loaded");
            return rv;
        } catch (GeneralSecurityException e) {
            log("Named curve " + name + " is not available", e);
            return null;
        }
    }

    /**
     *  Tries curve name1, then name2, then creates new from parms.
     *  @param name2 null to skip
     *  @param parms null to skip
     *  @return null if all fail
     */
    private static ECParameterSpec genSpec(String name1, String name2, ECParms parms) {
        ECParameterSpec rv = genSpec(name1);
        if (rv == null && name2 != null) {
            rv = genSpec(name2);
            if (rv == null && parms != null) {
                rv = parms.genSpec();
                if (rv != null)
                    log("Curve " + name2 + " created");
            }
        }
        return rv;
    }

    // standard curve names
    // first is OpenJDK 6/7
    // second is BC
    //public static final ECParameterSpec P192_SPEC = genSpec("secp192r1", "P-192", PARM_P192);
    public static final ECParameterSpec P256_SPEC = genSpec("secp256r1", "P-256", PARM_P256);
    public static final ECParameterSpec P384_SPEC = genSpec("secp384r1", "P-384", PARM_P384);
    public static final ECParameterSpec P521_SPEC = genSpec("secp521r1", "P-521", PARM_P521);

    // Koblitz
    //public static final ECParameterSpec K163_SPEC = genSpec("sect163k1", "K-163", null);
    //public static final ECParameterSpec K233_SPEC = genSpec("sect233k1", "K-233", null);
    //public static final ECParameterSpec K283_SPEC = genSpec("sect283k1", "K-283", null);
    //public static final ECParameterSpec K409_SPEC = genSpec("sect409k1", "K-409", null);
    //public static final ECParameterSpec K571_SPEC = genSpec("sect571k1", "K-571", null);


}
