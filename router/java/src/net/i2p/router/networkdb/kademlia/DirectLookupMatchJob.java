package net.i2p.router.networkdb.kademlia;

import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterContext;

/**
 * Override to not call failed() in setMessage(),
 * as it will be called from runJob()
 *
 * @since 0.9.56
 */
class DirectLookupMatchJob extends FloodOnlyLookupMatchJob {

    public DirectLookupMatchJob(RouterContext ctx, FloodSearchJob job) {
        super(ctx, job);
    }

    @Override
    public String getName() { return "Direct lookup match"; }

    /**
     * Override to not call failed() in setMessage(),
     * as it will be called from runJob()
     */
    @Override
    public void setMessage(I2NPMessage message) {
        if (message.getType() != DatabaseStoreMessage.MESSAGE_TYPE)
            return;
        DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
        if (dsm.getKey().equals(_search.getKey()))
            _success = true;
    }
}
