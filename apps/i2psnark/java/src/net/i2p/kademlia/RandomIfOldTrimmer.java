package net.i2p.kademlia;

import net.i2p.I2PAppContext;
import net.i2p.data.SimpleDataStructure;

/**
 *  Removes a random element, but only if the bucket hasn't changed in 5 minutes.
 *  @since 0.9.2
 */
public class RandomIfOldTrimmer<T extends SimpleDataStructure> extends RandomTrimmer<T> {

    public RandomIfOldTrimmer(I2PAppContext ctx, int max) {
        super(ctx, max);
    }

    public boolean trim(KBucket<T> kbucket, T toAdd) {
        if (kbucket.getLastChanged() > _ctx.clock().now() - 5*60*1000)
            return false;
        return super.trim(kbucket, toAdd);
    }
}
