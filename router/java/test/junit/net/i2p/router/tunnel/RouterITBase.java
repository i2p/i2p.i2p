package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

import org.junit.BeforeClass;

/**
 * Base class for tests that need a functioning router set up.
 * 
 * @author zab
 */
public abstract class RouterITBase {
    
    protected static RouterContext _context;
    protected static TunnelCreatorConfig _config;

    @BeforeClass
    public static void routerClassSetup() {
     // order of these matters
        Router r = new Router();
        _context = new RouterContext(r);
        _context.initAll();
        r.runRouter();
        RouterIdentity rIdentity = new TestRouterIdentity();
        RouterInfo rInfo = new RouterInfo();
        rInfo.setIdentity(rIdentity);
        r.setRouterInfo(rInfo);
        _config = prepareConfig(8);
    }
    
    private static TunnelCreatorConfig prepareConfig(int numHops) {
        Hash peers[] = new Hash[numHops];
        byte tunnelIds[][] = new byte[numHops][4];
        for (int i = 0; i < numHops; i++) {
            peers[i] = new Hash();
            peers[i].setData(new byte[Hash.HASH_LENGTH]);
            _context.random().nextBytes(peers[i].getData());
            _context.random().nextBytes(tunnelIds[i]);
        }
        
        TunnelCreatorConfig config = new TunnelCreatorConfig(_context, numHops, false);
        for (int i = 0; i < numHops; i++) {
            config.setPeer(i, peers[i]);
            HopConfig cfg = config.getConfig(i);
            cfg.setExpiration(_context.clock().now() + 60000);
            cfg.setIVKey(_context.keyGenerator().generateSessionKey());
            cfg.setLayerKey(_context.keyGenerator().generateSessionKey());
            if (i > 0)
                cfg.setReceiveFrom(peers[i-1]);
            else
                cfg.setReceiveFrom(null);
            cfg.setReceiveTunnelId(tunnelIds[i]);
            if (i < numHops - 1) {
                cfg.setSendTo(peers[i+1]);
                cfg.setSendTunnelId(tunnelIds[i+1]);
            } else {
                cfg.setSendTo(null);
                cfg.setSendTunnelId(null);
            }
        }
        return config;
    }
    
    private static class TestRouterIdentity extends RouterIdentity {
        @Override
        public Hash getHash() {
            return Hash.FAKE_HASH;
        }
    }
}
