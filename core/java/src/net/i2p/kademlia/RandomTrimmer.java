package net.i2p.kademlia;

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.SimpleDataStructure;

/**
 *  Removes a random element. Not resistant to flooding.
 *  @since 0.9.2 in i2psnark, moved to core in 0.9.10
 */
public class RandomTrimmer<T extends SimpleDataStructure> implements KBucketTrimmer<T> {
    protected final I2PAppContext _ctx;
    private final int _max;

    public RandomTrimmer(I2PAppContext ctx, int max) {
        _ctx = ctx;
        _max = max;
    }

    public boolean trim(KBucket<T> kbucket, T toAdd) {
        List<T> e = new ArrayList<T>(kbucket.getEntries());
        int sz = e.size();
        // concurrency
        if (sz < _max)
            return true;
        T toRemove = e.get(_ctx.random().nextInt(sz));
        kbucket.remove(toRemove);
        return true;
    }
}
