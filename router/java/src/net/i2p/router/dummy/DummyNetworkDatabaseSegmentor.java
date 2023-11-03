package net.i2p.router.dummy;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.SegmentedNetworkDatabaseFacade;

/**
 *  @since 0.9.60
 */
public class DummyNetworkDatabaseSegmentor extends SegmentedNetworkDatabaseFacade {
    private final NetworkDatabaseFacade _fndb;
    
    public DummyNetworkDatabaseSegmentor(RouterContext ctx) {
        _fndb = new DummyNetworkDatabaseFacade(ctx);
    }

    public void shutdown() {
        _fndb.shutdown();
    }

    public void startup() {
        _fndb.startup();
    }

    public NetworkDatabaseFacade mainNetDB() {
        return _fndb;
    }

    public NetworkDatabaseFacade clientNetDB(Hash id) {
        return _fndb;
    }
}
