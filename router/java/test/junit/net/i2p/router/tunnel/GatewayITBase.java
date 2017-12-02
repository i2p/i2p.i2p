package net.i2p.router.tunnel;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public abstract class GatewayITBase extends RouterITBase {

    
    private static TunnelGatewayPumper _pumper;
    
    private TunnelGateway.QueuePreprocessor _preprocessor;
    protected TunnelGateway.Sender _sender;
    protected TestReceiver _receiver;
    private TunnelGateway _gw;
    
    @BeforeClass
    public static void gatewayClassSetup() {
        _pumper = new TunnelGatewayPumper(_context);
    }
    
    @Before
    public void baseSetUp() {
        _preprocessor = new BatchedPreprocessor(_context,"test pre-processor");
        setupSenderAndReceiver();
        _gw = new PumpedTunnelGateway(_context, _preprocessor, _sender, _receiver, _pumper);
    }
    
    /** sets up the sender and receiver.  Subclasses must override */
    protected abstract void setupSenderAndReceiver();
    
    /**
     * @return at which hop to start the decryption process
     */
    protected abstract int getLastHop();
    
    @Test
    public void testSmall() throws Exception {
        int runCount = 1;
        
        List<DataMessage> messages = new ArrayList<DataMessage>(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(64);
            messages.add(m);
            _gw.add(m, null, null);
        }
        
        Thread.sleep(1000);
        
        List<I2NPMessage> received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testRouter() throws Exception {
        int runCount = 1;
        
        List<DataMessage> messages = new ArrayList<DataMessage>(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(64);
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            messages.add(m);
            _gw.add(m, to, null);
        }
        
        Thread.sleep(1000);
        
        List<I2NPMessage> received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testTunnel() throws Exception {
        int runCount = 1;
        
        List<DataMessage> messages = new ArrayList<DataMessage>(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(64);
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            TunnelId tunnel = new TunnelId(42);
            byte data[] = m.toByteArray();
            messages.add(m);
            _gw.add(m, to, tunnel);
        }
        
        Thread.sleep(1000);
        
        List<I2NPMessage> received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testLarge() throws Exception {
        int runCount = 1;
        
        List<DataMessage> messages = new ArrayList<DataMessage>(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(1024);
            messages.add(m);
            _gw.add(m, null, null);
        }
        
        Thread.sleep(1000);
        
        List<I2NPMessage> received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    private static DataMessage getTestMessage(int size) {
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
        private volatile List<I2NPMessage> _received;
        public TestReceiver(TunnelCreatorConfig config) {
            _config = config;
            _handler = new FragmentHandler(_context, TestReceiver.this);
            _received = new ArrayList<I2NPMessage>(1000);
        }

        @SuppressWarnings("deprecation")
        public long receiveEncrypted(byte[] encrypted) {
            // fake all the hops...
            
            for (int i = 1; i <= _config.getLength() - getLastHop(); i++) {
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
        public List<I2NPMessage> clearReceived() { 
            List<I2NPMessage> rv = _received; 
            _received = new ArrayList<I2NPMessage>();
            return rv;
        }
        @Override
        public Hash getSendTo() {
            // TODO Auto-generated method stub
            return null;
        }
    }
}
