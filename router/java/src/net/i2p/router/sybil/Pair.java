package net.i2p.router.sybil;

import java.math.BigInteger;

import net.i2p.data.router.RouterInfo;

/**
 *  A pair of routers and the distance between them.
 *
 *  @since 0.9.38 moved from SybilRenderer
 */
public class Pair implements Comparable<Pair> {
    public final RouterInfo r1, r2;
    public final BigInteger dist;

    public Pair(RouterInfo ri1, RouterInfo ri2, BigInteger distance) {
        r1 = ri1; r2 = ri2; dist = distance;
    }

    public int compareTo(Pair p) {
        return this.dist.compareTo(p.dist);
    }
}

