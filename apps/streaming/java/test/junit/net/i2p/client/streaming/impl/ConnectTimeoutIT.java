package net.i2p.client.streaming.impl;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Try to connect to a new nonexistant peer and, of course,
 * timeout.
 */
public class ConnectTimeoutIT  extends StreamingITBase {
    private Log _log;
    private I2PSession _client;
    private Destination _serverDest;
    
    @Test
    public void testNonexistant() throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        _log = context.logManager().getLog(ConnectIT.class);
        _log.debug("creating server dest");
        _serverDest = I2PClientFactory.createClient().createDestination(new ByteArrayOutputStream());
        _log.debug("creating client session");
        _client = createSession();
        _log.debug("running client");
        runClient(context, _client).join();
    }
    
    protected Runnable getClient(I2PAppContext ctx, I2PSession session) {
        return new ClientRunner(ctx,session);
    }
    
    private class ClientRunner extends RunnerBase {
        public ClientRunner(I2PAppContext ctx, I2PSession session) {
            super(ctx,session);
        }
        
        public void run() {
            I2PSocketManager mgr = I2PSocketManagerFactory.createManager("localhost", 10001, getProperties());
            assertNull(mgr);
        }

    }
    
    @Override
    protected Runnable getServer(I2PAppContext ctx, I2PSession session) {
        return null;
    }
    
    @Override
    protected Properties getProperties() {
        Properties p = new Properties();
        p.setProperty(I2PSocketManagerFactory.PROP_MANAGER, I2PSocketManagerFull.class.getName());
        p.setProperty("tunnels.depthInbound", "0");
        p.setProperty(ConnectionOptions.PROP_CONNECT_TIMEOUT, "30000");
        //p.setProperty(ConnectionOptions.PROP_CONNECT_DELAY, "10000");
        p.setProperty(ConnectionOptions.PROP_CONNECT_DELAY, "0");
        return p;
    }
}
