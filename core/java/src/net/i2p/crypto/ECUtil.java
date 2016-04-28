package net.i2p.crypto;

import java.math.BigInteger;
import java.security.spec.ECField;
import java.security.spec.ECFieldFp;
import java.security.spec.ECPoint;
import java.security.spec.EllipticCurve;

import net.i2p.util.NativeBigInteger;

/**
 *  Used by KeyGenerator.getSigningPublicKey()
 *
 *  Modified from
 *  http://stackoverflow.com/questions/15727147/scalar-multiplication-of-point-over-elliptic-curve
 *  Apparently public domain.
 *  Supported P-192 only.
 *  Added curve parameters to support all curves.
 *
 *  @since 0.9.16
 */
final class ECUtil {

    private static final BigInteger TWO = new BigInteger("2");
    private static final BigInteger THREE = new BigInteger("3");

    public static ECPoint scalarMult(ECPoint p, BigInteger kin, EllipticCurve curve) {
        ECPoint r = ECPoint.POINT_INFINITY;
        BigInteger prime = ((ECFieldFp) curve.getField()).getP();
        BigInteger k = kin.mod(prime);
        int length = k.bitLength();
        byte[] binarray = new byte[length];
        for (int i = 0; i <= length-1; i++) {
            binarray[i] = k.mod(TWO).byteValue();
            k = k.divide(TWO);
        }

        for (int i = length-1; i >= 0; i--) {
            // i should start at length-1 not -2 because the MSB of binarry may not be 1
            r = doublePoint(r, curve);
            if (binarray[i] == 1) 
                r = addPoint(r, p, curve);
        }
        return r;
    }

    private static ECPoint addPoint(ECPoint r, ECPoint s, EllipticCurve curve) {
        if (r.equals(s))
            return doublePoint(r, curve);
        else if (r.equals(ECPoint.POINT_INFINITY))
            return s;
        else if (s.equals(ECPoint.POINT_INFINITY))
            return r;
        BigInteger prime = ((ECFieldFp) curve.getField()).getP();
        // use NBI modInverse();
        BigInteger tmp = r.getAffineX().subtract(s.getAffineX());
        tmp = new NativeBigInteger(tmp);
        BigInteger slope = (r.getAffineY().subtract(s.getAffineY())).multiply(tmp.modInverse(prime)).mod(prime);
        slope = new NativeBigInteger(slope);
        BigInteger xOut = (slope.modPow(TWO, prime).subtract(r.getAffineX())).subtract(s.getAffineX()).mod(prime);
        BigInteger yOut = s.getAffineY().negate().mod(prime);
        yOut = yOut.add(slope.multiply(s.getAffineX().subtract(xOut))).mod(prime);
        ECPoint out = new ECPoint(xOut, yOut);
        return out;
    }

    private static ECPoint doublePoint(ECPoint r, EllipticCurve curve) {
        if (r.equals(ECPoint.POINT_INFINITY)) 
            return r;
        BigInteger slope = (r.getAffineX().pow(2)).multiply(THREE);
        slope = slope.add(curve.getA());
        BigInteger prime = ((ECFieldFp) curve.getField()).getP();
        // use NBI modInverse();
        BigInteger tmp = r.getAffineY().multiply(TWO);
        tmp = new NativeBigInteger(tmp);
        slope = slope.multiply(tmp.modInverse(prime));
        BigInteger xOut = slope.pow(2).subtract(r.getAffineX().multiply(TWO)).mod(prime);
        BigInteger yOut = (r.getAffineY().negate()).add(slope.multiply(r.getAffineX().subtract(xOut))).mod(prime);
        ECPoint out = new ECPoint(xOut, yOut);
        return out;
    }

    /**
     *  P-192 test only.
     *  See KeyGenerator.main() for a test of all supported curves.
     */
/****
    public static void main(String[] args) {
        EllipticCurve P192 = ECConstants.P192_SPEC.getCurve();
        BigInteger xs = new BigInteger("d458e7d127ae671b0c330266d246769353a012073e97acf8", 16);
        BigInteger ys = new BigInteger("325930500d851f336bddc050cf7fb11b5673a1645086df3b", 16);
        BigInteger xt = new BigInteger("f22c4395213e9ebe67ddecdd87fdbd01be16fb059b9753a4", 16);
        BigInteger yt = new BigInteger("264424096af2b3597796db48f8dfb41fa9cecc97691a9c79", 16);
        ECPoint S = new ECPoint(xs,ys);
        ECPoint T = new ECPoint(xt,yt);

        // Verifying addition 
        ECPoint Rst = addPoint(S, T, P192);
        BigInteger xst = new BigInteger("48e1e4096b9b8e5ca9d0f1f077b8abf58e843894de4d0290", 16);   // Specified value of x of point R for addition  in NIST Routine example
        System.out.println("x-coordinate of point Rst is : " + Rst.getAffineX()); 
        System.out.println("y-coordinate of point Rst is : " + Rst.getAffineY());
        if (Rst.getAffineX().equals(xst))
            System.out.println("Adding is correct");
        else
            System.out.println("Adding FAIL");

        //Verifying Doubling
        BigInteger xr = new BigInteger("30c5bc6b8c7da25354b373dc14dd8a0eba42d25a3f6e6962", 16);  // Specified value of x of point R for doubling  in NIST Routine example
        BigInteger yr = new BigInteger("0dde14bc4249a721c407aedbf011e2ddbbcb2968c9d889cf", 16);
        ECPoint R2s = new ECPoint(xr, yr);  // Specified value of y of point R for doubling  in NIST Routine example
        System.out.println("x-coordinate of point R2s is : " + R2s.getAffineX()); 
        System.out.println("y-coordinate of point R2s is : " + R2s.getAffineY());
        System.out.println("x-coordinate of calculated point is : " + doublePoint(S, P192).getAffineX()); 
        System.out.println("y-coordinate of calculated point is : " +    doublePoint(S, P192).getAffineY());
        if (R2s.getAffineX().equals(doublePoint(S, P192).getAffineX()) &&
            R2s.getAffineY().equals(doublePoint(S, P192).getAffineY()))
            System.out.println("Doubling is correct");
        else
            System.out.println("Doubling FAIL");

        xr = new BigInteger("1faee4205a4f669d2d0a8f25e3bcec9a62a6952965bf6d31", 16);  // Specified value of x of point R for scalar Multiplication  in NIST Routine example
        yr = new BigInteger("5ff2cdfa508a2581892367087c696f179e7a4d7e8260fb06", 16);   // Specified value of y of point R for scalar Multiplication  in NIST Routine example
        ECPoint Rds = new ECPoint(xr, yr);
        BigInteger d = new BigInteger("a78a236d60baec0c5dd41b33a542463a8255391af64c74ee", 16);

        ECPoint Rs = scalarMult(S, d, P192);

        System.out.println("x-coordinate of point Rds is : " + Rds.getAffineX());
        System.out.println("y-coordinate of point Rds is : " + Rds.getAffineY());
        System.out.println("x-coordinate of calculated point is : " + Rs.getAffineX());
        System.out.println("y-coordinate of calculated point is : " + Rs.getAffineY()); 


        if (Rds.getAffineX().equals(Rs.getAffineX()) &&
            Rds.getAffineY().equals(Rs.getAffineY()))
            System.out.println("Scalar Multiplication is correct");
        else
            System.out.println("Scalar Multiplication FAIL");
    }
****/
}
