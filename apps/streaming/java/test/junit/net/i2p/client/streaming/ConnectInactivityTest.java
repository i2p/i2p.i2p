package net.i2p.client.streaming;

import java.util.Properties;

import org.junit.Test;


import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.util.Log;

/**
 *
 */
public class ConnectInactivityTest extends StreamingTestBase {
    private Log _log;
    private I2PSession _client;
    private I2PSession _server;
    
    @Test
    public void test() throws Exception {
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
    }
    
    @Override
    protected Runnable getClient(I2PAppContext ctx, I2PSession session) {
        return new ClientRunner(ctx,session);
    }
    
    @Override
    protected Runnable getServer(I2PAppContext ctx, I2PSession session) {
        return new ServerRunner(ctx,session);
    }
    
    private class ServerRunner extends RunnerBase {
        public ServerRunner(I2PAppContext ctx, I2PSession session) {
            super(ctx,session);
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
    
    private class ClientRunner extends RunnerBase {
        public ClientRunner(I2PAppContext ctx, I2PSession session) {
            super(ctx,session);
        }
        
        public void run() {
            try {
                Properties opts = new Properties();
                I2PSocketManager mgr = new I2PSocketManagerFull(_context, _session, opts, "client");
                _log.debug("manager created");
                I2PSocket socket = mgr.connect(_server.getMyDestination());
                _log.debug("socket created");
                Thread.sleep(10*60*1000); 
                socket.close();
                _log.debug("socket closed");
                //_session.destroySession();
            } catch (Exception e) {
                _log.error("error running", e);
            }
        }
        
    }
    
    @Override
    protected Properties getProperties() {
        Properties p = new Properties();
        p.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
        p.setProperty(I2PClient.PROP_TCP_PORT, "10001");
        return p;
    }
}
