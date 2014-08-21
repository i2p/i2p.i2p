package net.i2p.crypto.eddsa.math;

import java.io.Serializable;

import net.i2p.crypto.eddsa.Utils;

/**
 * A point (x,y) on an EdDSA curve.
 * @author str4d
 *
 */
public class GroupElement implements Serializable {
    private static final long serialVersionUID = 2395879087349587L;

    public enum Representation {
        /** Projective: (X:Y:Z) satisfying x=X/Z, y=Y/Z */
        P2,
        /** Extended: (X:Y:Z:T) satisfying x=X/Z, y=Y/Z, XY=ZT */
        P3,
        /** Completed: ((X:Z),(Y:T)) satisfying x=X/Z, y=Y/T */
        P1P1,
        /** Precomputed (Duif): (y+x,y-x,2dxy) */
        PRECOMP,
        /** Cached: (Y+X,Y-X,Z,2dT) */
        CACHED
    }

    public static GroupElement p2(Curve curve, FieldElement X,
            FieldElement Y, FieldElement Z) {
        return new GroupElement(curve, Representation.P2, X, Y, Z, null);
    }

    public static GroupElement p3(Curve curve, FieldElement X,
            FieldElement Y, FieldElement Z, FieldElement T) {
        return new GroupElement(curve, Representation.P3, X, Y, Z, T);
    }

    public static GroupElement p1p1(Curve curve, FieldElement X,
            FieldElement Y, FieldElement Z, FieldElement T) {
        return new GroupElement(curve, Representation.P1P1, X, Y, Z, T);
    }

    public static GroupElement precomp(Curve curve, FieldElement ypx,
            FieldElement ymx, FieldElement xy2d) {
        return new GroupElement(curve, Representation.PRECOMP, ypx, ymx, xy2d, null);
    }

    public static GroupElement cached(Curve curve, FieldElement YpX,
            FieldElement YmX, FieldElement Z, FieldElement T2d) {
        return new GroupElement(curve, Representation.CACHED, YpX, YmX, Z, T2d);
    }

    /**
     * Variable is package private only so that tests run.
     */
    final Curve curve;
    /**
     * Variable is package private only so that tests run.
     */
    final Representation repr;
    /**
     * Variable is package private only so that tests run.
     */
    final FieldElement X;
    /**
     * Variable is package private only so that tests run.
     */
    final FieldElement Y;
    /**
     * Variable is package private only so that tests run.
     */
    final FieldElement Z;
    /**
     * Variable is package private only so that tests run.
     */
    final FieldElement T;
    /**
     * Precomputed table for {@link GroupElement#scalarMultiply(byte[])},
     * filled if necessary.
     *
     * Variable is package private only so that tests run.
     */
    GroupElement[][] precmp;
    /**
     * Precomputed table for {@link GroupElement#doubleScalarMultiplyVariableTime(GroupElement, byte[], byte[])},
     * filled if necessary.
     *
     * Variable is package private only so that tests run.
     */
    GroupElement[] dblPrecmp;

    public GroupElement(Curve curve, Representation repr, FieldElement X, FieldElement Y,
            FieldElement Z, FieldElement T) {
        this.curve = curve;
        this.repr = repr;
        this.X = X;
        this.Y = Y;
        this.Z = Z;
        this.T = T;
    }

    public GroupElement(Curve curve, byte[] s) {
        FieldElement x, y, yy, u, v, v3, vxx, check;
        y = curve.getField().fromByteArray(s);
        yy = y.square();

        // u = y^2-1	
        u = yy.subtractOne();

        // v = dy^2+1
        v = yy.multiply(curve.getD()).addOne();

        // v3 = v^3
        v3 = v.square().multiply(v);

        // x = (v3^2)vu, aka x = uv^7
        x = v3.square().multiply(v).multiply(u);	

        //  x = (uv^7)^((q-5)/8)
        x = x.pow22523();

        // x = uv^3(uv^7)^((q-5)/8)
        x = v3.multiply(u).multiply(x);

        vxx = x.square().multiply(v);
        check = vxx.subtract(u);			// vx^2-u
        if (check.isNonZero()) {
            check = vxx.add(u);				// vx^2+u

            if (check.isNonZero())
                throw new IllegalArgumentException("not a valid GroupElement");
            x = x.multiply(curve.getI());
        }

        if ((x.isNegative() ? 1 : 0) != Utils.bit(s, curve.getField().getb()-1)) {
            x = x.negate();
        }

        this.curve = curve;
        repr = Representation.P3;
        X = x;
        Y = y;
        Z = curve.getField().one;
        T = X.multiply(Y);
    }

    public byte[] toByteArray() {
        switch (repr) {
        case P2:
        case P3:
            FieldElement recip = Z.invert();
            FieldElement x = X.multiply(recip);
            FieldElement y = Y.multiply(recip);
            byte[] s = y.toByteArray();
            s[s.length-1] |= (x.isNegative() ? (byte) 0x80 : 0);
            return s;
        default:
            return toP2().toByteArray();
        }
    }

    public GroupElement toP2() {
        return toRep(Representation.P2);
    }
    public GroupElement toP3() {
        return toRep(Representation.P3);
    }
    public GroupElement toCached() {
        return toRep(Representation.CACHED);
    }

    /**
     * Convert a GroupElement from one Representation to another.<br>
     * r = p<br>
     * <br>
     * Supported conversions:<br>
     * - P3 -> P2<br>
     * - P3 -> CACHED (1 multiply, 1 add, 1 subtract)<br>
     * - P1P1 -> P2 (3 multiply)<br>
     * - P1P1 -> P3 (4 multiply)
     * @param rep The Representation to convert to.
     * @return A new GroupElement in the given Representation.
     */
    private GroupElement toRep(Representation repr) {
        switch (this.repr) {
        case P3:
            switch (repr) {
            case P2:
                return p2(curve, X, Y, Z);
            case CACHED:
                return cached(curve, Y.add(X), Y.subtract(X), Z, T.multiply(curve.get2D()));
            default:
                throw new IllegalArgumentException();
            }
        case P1P1:
            switch (repr) {
            case P2:
                return p2(curve, X.multiply(T), Y.multiply(Z), Z.multiply(T));
            case P3:
                return p3(curve, X.multiply(T), Y.multiply(Z), Z.multiply(T), X.multiply(Y));
            default:
                throw new IllegalArgumentException();
            }
        default:
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Precompute the tables for {@link GroupElement#scalarMultiply(byte[])}
     * and {@link GroupElement#doubleScalarMultiplyVariableTime(GroupElement, byte[], byte[])}.
     *
     * @param precomputeSingle should the matrix for scalarMultiply() be precomputed?
     */
    public synchronized void precompute(boolean precomputeSingle) {
        GroupElement Bi;

        if (precomputeSingle && precmp == null) {
            // Precomputation for single scalar multiplication.
            precmp = new GroupElement[32][8];
            Bi = this;
            for (int i = 0; i < 32; i++) {
                GroupElement Bij = Bi;
                for (int j = 0; j < 8; j++) {
                    FieldElement recip = Bij.Z.invert();
                    FieldElement x = Bij.X.multiply(recip);
                    FieldElement y = Bij.Y.multiply(recip);
                    precmp[i][j] = precomp(curve, y.add(x), y.subtract(x), x.multiply(y).multiply(curve.get2D()));
                    Bij = Bij.add(Bi.toCached()).toP3();
                }
                for (int k = 0; k < 8; k++) {
                    Bi = Bi.add(Bi.toCached()).toP3();
                }
            }
        }

        // Precomputation for double scalar multiplication.
        // P,3P,5P,7P,9P,11P,13P,15P
        if (dblPrecmp != null)
            return;
        dblPrecmp = new GroupElement[8];
        Bi = this;
        for (int i = 0; i < 8; i++) {
            FieldElement recip = Bi.Z.invert();
            FieldElement x = Bi.X.multiply(recip);
            FieldElement y = Bi.Y.multiply(recip);
            dblPrecmp[i] = precomp(curve, y.add(x), y.subtract(x), x.multiply(y).multiply(curve.get2D()));
            // Bi = edwards(B,edwards(B,Bi))
            Bi = add(add(Bi.toCached()).toP3().toCached()).toP3();
        }
    }

    /**
     * r = 2 * p
     * @return The P1P1 representation
     */
    public GroupElement dbl() {
        switch (repr) {
        case P2:
        case P3: // Ignore T for P3 representation
            FieldElement XX, YY, B, A, AA, Yn, Zn;
            XX = X.square();
            YY = Y.square();
            B = Z.squareAndDouble();
            A = X.add(Y);
            AA = A.square();
            Yn = YY.add(XX);
            Zn = YY.subtract(XX);
            return p1p1(curve, AA.subtract(Yn), Yn, Zn, B.subtract(Zn));
        default:
            throw new UnsupportedOperationException();
        }
    }

    /**
     * GroupElement addition using the twisted Edwards addition law with
     * extended coordinates (Hisil2008).<br>
     * r = p + q
     * @param q the PRECOMP representation of the GroupElement to add.
     * @return the P1P1 representation of the result.
     */
    private GroupElement madd(GroupElement q) {
        if (this.repr != Representation.P3)
            throw new UnsupportedOperationException();
        if (q.repr != Representation.PRECOMP)
            throw new IllegalArgumentException();

        FieldElement YpX, YmX, A, B, C, D;
        YpX = Y.add(X);
        YmX = Y.subtract(X);
        A = YpX.multiply(q.X); // q->y+x
        B = YmX.multiply(q.Y); // q->y-x
        C = q.Z.multiply(T); // q->2dxy
        D = Z.add(Z);
        return p1p1(curve, A.subtract(B), A.add(B), D.add(C), D.subtract(C));
    }

    /**
     * GroupElement subtraction using the twisted Edwards addition law with
     * extended coordinates (Hisil2008).<br>
     * r = p - q
     * @param q the PRECOMP representation of the GroupElement to subtract.
     * @return the P1P1 representation of the result.
     */
    private GroupElement msub(GroupElement q) {
        if (this.repr != Representation.P3)
            throw new UnsupportedOperationException();
        if (q.repr != Representation.PRECOMP)
            throw new IllegalArgumentException();

        FieldElement YpX, YmX, A, B, C, D;
        YpX = Y.add(X);
        YmX = Y.subtract(X);
        A = YpX.multiply(q.Y); // q->y-x
        B = YmX.multiply(q.X); // q->y+x
        C = q.Z.multiply(T); // q->2dxy
        D = Z.add(Z);
        return p1p1(curve, A.subtract(B), A.add(B), D.subtract(C), D.add(C));
    }

    /**
     * GroupElement addition using the twisted Edwards addition law with
     * extended coordinates (Hisil2008).<br>
     * r = p + q
     * @param q the CACHED representation of the GroupElement to add.
     * @return the P1P1 representation of the result.
     */
    public GroupElement add(GroupElement q) {
        if (this.repr != Representation.P3)
            throw new UnsupportedOperationException();
        if (q.repr != Representation.CACHED)
            throw new IllegalArgumentException();

        FieldElement YpX, YmX, A, B, C, ZZ, D;
        YpX = Y.add(X);
        YmX = Y.subtract(X);
        A = YpX.multiply(q.X); // q->Y+X
        B = YmX.multiply(q.Y); // q->Y-X
        C = q.T.multiply(T); // q->2dT
        ZZ = Z.multiply(q.Z);
        D = ZZ.add(ZZ);
        return p1p1(curve, A.subtract(B), A.add(B), D.add(C), D.subtract(C));
    }

    /**
     * GroupElement subtraction using the twisted Edwards addition law with
     * extended coordinates (Hisil2008).<br>
     * r = p - q
     * @param q the PRECOMP representation of the GroupElement to subtract.
     * @return the P1P1 representation of the result.
     */
    public GroupElement sub(GroupElement q) {
        if (this.repr != Representation.P3)
            throw new UnsupportedOperationException();
        if (q.repr != Representation.CACHED)
            throw new IllegalArgumentException();

        FieldElement YpX, YmX, A, B, C, ZZ, D;
        YpX = Y.add(X);
        YmX = Y.subtract(X);
        A = YpX.multiply(q.Y); // q->Y-X
        B = YmX.multiply(q.X); // q->Y+X
        C = q.T.multiply(T); // q->2dT
        ZZ = Z.multiply(q.Z);
        D = ZZ.add(ZZ);
        return p1p1(curve, A.subtract(B), A.add(B), D.subtract(C), D.add(C));
    }

    public GroupElement negate() {
        if (this.repr != Representation.P3)
            throw new UnsupportedOperationException();
        return curve.getZero(Representation.P3).sub(toCached()).toP3();
    }

    @Override
    public int hashCode() {
        // TODO
        return 42;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof GroupElement))
            return false;
        GroupElement ge = (GroupElement) obj;
        if (!this.repr.equals(ge.repr)) {
            try {
                ge = ge.toRep(this.repr);
            } catch (Exception e) {
                return false;
            }
        }
        switch (this.repr) {
        case P2:
        case P3:
            // Try easy way first
            if (Z.equals(ge.Z))
                return X.equals(ge.X) && Y.equals(ge.Y);
            // X1/Z1 = X2/Z2 --> X1*Z2 = X2*Z1
            FieldElement x1 = X.multiply(ge.Z);
            FieldElement y1 = Y.multiply(ge.Z);
            FieldElement x2 = ge.X.multiply(Z);
            FieldElement y2 = ge.Y.multiply(Z);
            return x1.equals(x2) && y1.equals(y2);
        case P1P1:
            return toP2().equals(ge);
        case PRECOMP:
            // Compare directly, PRECOMP is derived directly from x and y
            return X.equals(ge.X) && Y.equals(ge.Y) && Z.equals(ge.Z);
        case CACHED:
            // Try easy way first
            if (Z.equals(ge.Z))
                return X.equals(ge.X) && Y.equals(ge.Y) && T.equals(ge.T);
            // (Y+X)/Z = y+x etc.
            FieldElement x3 = X.multiply(ge.Z);
            FieldElement y3 = Y.multiply(ge.Z);
            FieldElement t3 = T.multiply(ge.Z);
            FieldElement x4 = ge.X.multiply(Z);
            FieldElement y4 = ge.Y.multiply(Z);
            FieldElement t4 = ge.T.multiply(Z);
            return x3.equals(x4) && y3.equals(y4) && t3.equals(t4);
        default:
            return false;
        }
    }

    /**
     * Convert a to radix 16.
     *
     * Method is package private only so that tests run.
     *
     * @param a = a[0]+256*a[1]+...+256^31 a[31]
     * @return 64 bytes, each between -8 and 7
     */
    static byte[] toRadix16(byte[] a) {
        byte[] e = new byte[64];
        int i;
        // Radix 16 notation
        for (i = 0; i < 32; i++) {
            e[2*i+0] = (byte) (a[i] & 15);
            e[2*i+1] = (byte) ((a[i] >> 4) & 15);
        }
        /* each e[i] is between 0 and 15 */
        /* e[63] is between 0 and 7 */
        int carry = 0;
        for (i = 0; i < 63; i++) {
            e[i] += carry;
            carry = e[i] + 8;
            carry >>= 4;
            e[i] -= carry << 4;
        }
        e[63] += carry;
        /* each e[i] is between -8 and 7 */
        return e;
    }

    /**
     * Constant-time conditional move.
     * Replaces this with u if b == 1.
     * Replaces this with this if b == 0.
     *
     * Method is package private only so that tests run.
     *
     * @param u
     * @param b in {0, 1}
     * @return u if b == 1; this if b == 0; null otherwise.
     */
    GroupElement cmov(GroupElement u, int b) {
        GroupElement ret = null;
        for (int i = 0; i < b; i++) {
            // Only for b == 1
            ret = u;
        }
        for (int i = 0; i < 1-b; i++) {
            // Only for b == 0
            ret = this;
        }
        return ret;
    }

    /**
     * Look up 16^i r_i B in the precomputed table.
     * No secret array indices, no secret branching.
     * Constant time.
     *
     * Must have previously precomputed.
     *
     * Method is package private only so that tests run.
     *
     * @param pos = i/2 for i in {0, 2, 4,..., 62}
     * @param b = r_i
     * @return
     */
    GroupElement select(int pos, int b) {
        // Is r_i negative?
        int bnegative = Utils.negative(b);
        // |r_i|
        int babs = b - (((-bnegative) & b) << 1);

        // 16^i |r_i| B
        GroupElement t = curve.getZero(Representation.PRECOMP)
                .cmov(precmp[pos][0], Utils.equal(babs, 1))
                .cmov(precmp[pos][1], Utils.equal(babs, 2))
                .cmov(precmp[pos][2], Utils.equal(babs, 3))
                .cmov(precmp[pos][3], Utils.equal(babs, 4))
                .cmov(precmp[pos][4], Utils.equal(babs, 5))
                .cmov(precmp[pos][5], Utils.equal(babs, 6))
                .cmov(precmp[pos][6], Utils.equal(babs, 7))
                .cmov(precmp[pos][7], Utils.equal(babs, 8));
        // -16^i |r_i| B
        GroupElement tminus = precomp(curve, t.Y, t.X, t.Z.negate());
        // 16^i r_i B
        return t.cmov(tminus, bnegative);
    }

    /**
     * h = a * B where a = a[0]+256*a[1]+...+256^31 a[31] and
     * B is this point. If its lookup table has not been precomputed, it
     * will be at the start of the method (and cached for later calls). 
     * Constant time.
     *
     * Preconditions: (TODO: Check this applies here)
     *   a[31] <= 127
     * @param a = a[0]+256*a[1]+...+256^31 a[31]
     * @return
     */
    public GroupElement scalarMultiply(byte[] a) {
        GroupElement t;
        int i;

        byte[] e = toRadix16(a);

        GroupElement h = curve.getZero(Representation.P3);
        synchronized(this) {
            // TODO: Get opinion from a crypto professional.
            // This should in practice never be necessary, the only point that
            // this should get called on is EdDSA's B.
            //precompute();
            for (i = 1; i < 64; i += 2) {
                t = select(i/2, e[i]);
                h = h.madd(t).toP3();
            }

            h = h.dbl().toP2().dbl().toP2().dbl().toP2().dbl().toP3();

            for (i = 0; i < 64; i += 2) {
                t = select(i/2, e[i]);
                h = h.madd(t).toP3();
            }
        }

        return h;
    }

    /**
     * I don't really know what this method does.
     *
     * Method is package private only so that tests run.
     *
     * @param a 32 bytes
     * @return 256 bytes
     */
    static byte[] slide(byte[] a) {
        byte[] r = new byte[256];

        // put each bit of 'a' into a separate byte, 0 or 1
        for (int i = 0; i < 256; ++i) {
            r[i] = (byte) (1 & (a[i >> 3] >> (i & 7)));
        }

        for (int i = 0; i < 256; ++i) {
            if (r[i] != 0) {
                for (int b = 1; b <= 6 && i + b < 256; ++b) {
                    if (r[i + b] != 0) {
                        if (r[i] + (r[i + b] << b) <= 15) {
                            r[i] += r[i + b] << b;
                            r[i + b] = 0;
                        } else if (r[i] - (r[i + b] << b) >= -15) {
                            r[i] -= r[i + b] << b;
                            for (int k = i + b; k < 256; ++k) {
                                if (r[k] == 0) {
                                    r[k] = 1;
                                    break;
                                }
                                r[k] = 0;
                            }
                        } else
                            break;
                    }
                }
            }
        }

        return r;
    }

    /**
     * r = a * A + b * B where a = a[0]+256*a[1]+...+256^31 a[31],
     * b = b[0]+256*b[1]+...+256^31 b[31] and B is this point.
     *
     * A must have been previously precomputed.
     *
     * @param A in P3 representation.
     * @param a = a[0]+256*a[1]+...+256^31 a[31]
     * @param b = b[0]+256*b[1]+...+256^31 b[31]
     * @return
     */
    public GroupElement doubleScalarMultiplyVariableTime(GroupElement A, byte[] a, byte[] b) {
        byte[] aslide = slide(a);
        byte[] bslide = slide(b);

        GroupElement r = curve.getZero(Representation.P2);

        int i;
        for (i = 255; i >= 0; --i) {
            if (aslide[i] != 0 || bslide[i] != 0) break;
        }

        synchronized(this) {
            // TODO: Get opinion from a crypto professional.
            // This should in practice never be necessary, the only point that
            // this should get called on is EdDSA's B.
            //precompute();
            for (; i >= 0; --i) {
                GroupElement t = r.dbl();

                if (aslide[i] > 0) {
                    t = t.toP3().madd(A.dblPrecmp[aslide[i]/2]);
                } else if(aslide[i] < 0) {
                    t = t.toP3().msub(A.dblPrecmp[(-aslide[i])/2]);
                }

                if (bslide[i] > 0) {
                    t = t.toP3().madd(dblPrecmp[bslide[i]/2]);
                } else if(bslide[i] < 0) {
                    t = t.toP3().msub(dblPrecmp[(-bslide[i])/2]);
                }

                r = t.toP2();
            }
        }

        return r;
    }

    /**
     * Verify that a point is on its curve.
     * @param P The point to check.
     * @return true if the point lies on its curve.
     */
    public boolean isOnCurve() {
        return isOnCurve(curve);
    }

    /**
     * Verify that a point is on the curve.
     * @param curve The curve to check.
     * @return true if the point lies on the curve.
     */
    public boolean isOnCurve(Curve curve) {
        switch (repr) {
        case P2:
        case P3:
            FieldElement recip = Z.invert();
            FieldElement x = X.multiply(recip);
            FieldElement y = Y.multiply(recip);
            FieldElement xx = x.square();
            FieldElement yy = y.square();
            FieldElement dxxyy = curve.getD().multiply(xx).multiply(yy);
            return curve.getField().one.add(dxxyy).add(xx).equals(yy);

        default:
            return toP2().isOnCurve(curve);
        }
    }

    @Override
    public String toString() {
        return "[GroupElement\nX="+X+"\nY="+Y+"\nZ="+Z+"\nT="+T+"\n]";
    }
}
