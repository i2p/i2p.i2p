package net.i2p.client.streaming;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Try to connect to a new nonexistant peer and, of course,
 * timeout.
 */
public class ConnectTimeoutTest {
    private Log _log;
    private I2PSession _client;
    private I2PSession _server;
    private Destination _serverDest;
    
    public void testNonexistant() {
        try {
            I2PAppContext context = I2PAppContext.getGlobalContext();
            _log = context.logManager().getLog(ConnectTest.class);
            _log.debug("creating server dest");
            try {
                _serverDest = I2PClientFactory.createClient().createDestination(new ByteArrayOutputStream());
            } catch (Exception e) {}
            _log.debug("creating client session");
            _client = createSession();
            _log.debug("running client");
            runClient(context, _client);
        } catch (Exception e) {
            _log.error("error running", e);
        }
        while (true) { synchronized (this) { try { wait(); } catch (Exception e) {} } }
    }
    
    private void runClient(I2PAppContext ctx, I2PSession session) {
        Thread t = new Thread(new ClientRunner(ctx, session));
        t.setName("client");
        t.setDaemon(true);
        t.start();
    }
    
    private class ClientRunner implements Runnable {
        private I2PAppContext _context;
        private I2PSession _session;
        private Log _log;
        public ClientRunner(I2PAppContext ctx, I2PSession session) {
            _context = ctx;
            _session = session;
            _log = ctx.logManager().getLog(ClientRunner.class);
        }
        
        public void run() {
            try {
                I2PSocketManager mgr = I2PSocketManagerFactory.createManager("localhost", 10001, getProps());
                _log.debug("manager created");
                _log.debug("options: " + mgr.getDefaultOptions());
                I2PSocket socket = mgr.connect(_serverDest);
                _log.debug("socket created");
                socket.getOutputStream().write("you smell".getBytes());
                socket.getOutputStream().flush();
                _log.error("wtf, shouldn't have flushed");
                socket.close();
                _log.debug("socket closed");
            } catch (Exception e) {
                _log.error("error running (yay!)", e);
            }
        }
        
    }
    
    private I2PSession createSession() {
        try {
            I2PClient client = I2PClientFactory.createClient();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            Destination dest = client.createDestination(baos);
            Properties p = getProps();

            I2PSession sess = client.createSession(new ByteArrayInputStream(baos.toByteArray()), p);
            sess.connect();
            return sess;
        } catch (Exception e) {
            _log.error("error running", e);
            throw new RuntimeException("b0rk b0rk b0rk");
        }
    }
    
    public static void main(String args[]) {
        ConnectTimeoutTest ct = new ConnectTimeoutTest();
        ct.testNonexistant();
    }
    
    private static Properties getProps() {
        Properties p = new Properties();
        p.setProperty(I2PSocketManagerFactory.PROP_MANAGER, I2PSocketManagerFull.class.getName());
        p.setProperty("tunnels.depthInbound", "0");
        p.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
        p.setProperty(I2PClient.PROP_TCP_PORT, "10001");
        p.setProperty(ConnectionOptions.PROP_CONNECT_TIMEOUT, "30000");
        //p.setProperty(ConnectionOptions.PROP_CONNECT_DELAY, "10000");
        p.setProperty(ConnectionOptions.PROP_CONNECT_DELAY, "0");
        return p;
    }
}
