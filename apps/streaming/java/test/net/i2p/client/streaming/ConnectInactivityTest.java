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
 *
 */
public class ConnectInactivityTest {
    private Log _log;
    private I2PSession _client;
    private I2PSession _server;
    public void test() {
        try {
            I2PAppContext context = I2PAppContext.getGlobalContext();
            _log = context.logManager().getLog(ConnectTest.class);
            _log.debug("creating server session");
            _server = createSession();
            _log.debug("running server");
            runServer(context, _server);
            _log.debug("creating client session");
            _client = createSession();
            _log.debug("running client");
            runClient(context, _client);
        } catch (Exception e) {
            _log.error("error running", e);
        }
    }
    
    private void runClient(I2PAppContext ctx, I2PSession session) {
        Thread t = new Thread(new ClientRunner(ctx, session));
        t.setName("client");
        t.setDaemon(false);
        t.start();
    }
    
    private void runServer(I2PAppContext ctx, I2PSession session) {
        Thread t = new Thread(new ServerRunner(ctx, session));
        t.setName("server");
        t.setDaemon(false);
        t.start();
    }
    
    private class ServerRunner implements Runnable {
        private I2PAppContext _context;
        private I2PSession _session;
        private Log _log;
        public ServerRunner(I2PAppContext ctx, I2PSession session) {
            _context = ctx;
            _session = session;
            _log = ctx.logManager().getLog(ServerRunner.class);
        }
        
        public void run() {
            try {
                Properties opts = new Properties();
                I2PSocketManager mgr = new I2PSocketManagerFull(_context, _session, opts, "client");
                _log.debug("manager created");
                I2PServerSocket ssocket = mgr.getServerSocket();
                _log.debug("server socket created");
                I2PSocket socket = ssocket.accept();
                _log.debug("socket accepted: " + socket);
                try { Thread.sleep(10*60*1000); } catch (InterruptedException ie) {}
                socket.close();
                ssocket.close();
                _session.destroySession();
            } catch (Exception e) {
                _log.error("error running", e);
            }
        }
        
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
                Properties opts = new Properties();
                I2PSocketManager mgr = new I2PSocketManagerFull(_context, _session, opts, "client");
                _log.debug("manager created");
                I2PSocket socket = mgr.connect(_server.getMyDestination());
                _log.debug("socket created");
                try { Thread.sleep(10*60*1000); } catch (InterruptedException ie) {}
                socket.close();
                _log.debug("socket closed");
                //_session.destroySession();
            } catch (Exception e) {
                _log.error("error running", e);
            }
        }
        
    }
    
    private I2PSession createSession() {
        try {
            I2PClient client = I2PClientFactory.createClient();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            Destination dest = client.createDestination(baos);
            Properties p = new Properties();
            p.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
            p.setProperty(I2PClient.PROP_TCP_PORT, "10001");
            I2PSession sess = client.createSession(new ByteArrayInputStream(baos.toByteArray()), p);
            sess.connect();
            return sess;
        } catch (Exception e) {
            _log.error("error running", e);
            throw new RuntimeException("b0rk b0rk b0rk");
        }
    }
    
    public static void main(String args[]) {
        ConnectInactivityTest ct = new ConnectInactivityTest();
        ct.test();
    }
}
