package net.i2p.router.tunnel;

import static junit.framework.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

import org.junit.Before;
import org.junit.BeforeClass;

public abstract class GatewayTestBase {

    protected static RouterContext _context;
    protected static TunnelGatewayPumper _pumper;
    protected static TunnelCreatorConfig _config;
    
    protected TunnelGateway.QueuePreprocessor _preprocessor;
    protected TunnelGateway.Sender _sender;
    protected TestReceiver _receiver;
    protected TunnelGateway _gw;
    
    @BeforeClass
    public static void globalSetUp() {
        // order of these matters
        Router r = new Router();
        _context = new RouterContext(r);
        _context.initAll();
        r.runRouter();
        RouterIdentity rIdentity = new TestRouterIdentity();
        RouterInfo rInfo = new RouterInfo();
        rInfo.setIdentity(rIdentity);
        r.setRouterInfo(rInfo);
        _pumper = new TunnelGatewayPumper(_context);
        _config = prepareConfig(8);
    }
    
    @Before
    public void baseSetUp() {
        _preprocessor = new BatchedPreprocessor(_context,"test pre-processor");
        setupSenderAndReceiver();
        _gw = new PumpedTunnelGateway(_context, _preprocessor, _sender, _receiver, _pumper);
    }
    
    protected abstract void setupSenderAndReceiver();
    
    private static class TestRouterIdentity extends RouterIdentity {
        @Override
        public Hash getHash() {
            return Hash.FAKE_HASH;
        }
    }
    
    protected static DataMessage getTestMessage(int size) {
        DataMessage m = new DataMessage(_context);
        m.setData(new byte[size]);
        java.util.Arrays.fill(m.getData(), (byte)0xFF);
        m.setMessageExpiration(_context.clock().now() + 60*1000);
        m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        
        byte [] data = m.toByteArray(); // not sure why, maybe side-effect? --zab
        return m;
    }
    
    protected class TestReceiver implements TunnelGateway.Receiver, FragmentHandler.DefragmentedReceiver {
        private TunnelCreatorConfig _config;
        private FragmentHandler _handler;
        private volatile List _received;
        public TestReceiver(TunnelCreatorConfig config) {
            _config = config;
            _handler = new FragmentHandler(_context, TestReceiver.this);
            _received = new ArrayList(1000);
        }
        public long receiveEncrypted(byte[] encrypted) {
            // fake all the hops...
            
            for (int i = 1; i <= _config.getLength() - 2; i++) {
                HopProcessor hop = new HopProcessor(_context, _config.getConfig(i));
                assertTrue(hop.process(encrypted, 0, encrypted.length, _config.getConfig(i).getReceiveFrom()));
            }
            
            handleAtEndpoint(encrypted);
            
            _handler.receiveTunnelMessage(encrypted, 0, encrypted.length);
            return -1; // or do we need to return the real message ID?
        }        
        
        protected void handleAtEndpoint(byte [] encrypted) {
        }
        
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            _received.add(msg);
        }
        public List clearReceived() { 
            List rv = _received; 
            _received = new ArrayList();
            return rv;
        }
        @Override
        public Hash getSendTo() {
            // TODO Auto-generated method stub
            return null;
        }
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
}
