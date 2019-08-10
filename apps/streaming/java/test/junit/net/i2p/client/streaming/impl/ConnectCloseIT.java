package net.i2p.client.streaming.impl;

import java.io.InputStream;
import java.util.Properties;

import org.junit.Test;


import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.IncomingConnectionFilter;
import net.i2p.util.Log;
/**
 * Have a client connect to a server, where the server waits 5
 * seconds and closes the socket and the client detect that 
 * EOF.
 *
 */
public class ConnectCloseIT extends StreamingITBase {
    private Log _log;
    private I2PSession _server;
    
    @Test
    public void test() throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        _log = context.logManager().getLog(ConnectCloseIT.class);
        _log.debug("creating server session");
        _server = createSession();
        _log.debug("running server");
        runServer(context, _server);
        _log.debug("running client");
        runClient(context, createSession());
    }
    
    
    
    @Override
    protected Properties getProperties() {
        return System.getProperties();
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
                I2PSocketManager mgr = new I2PSocketManagerFull(
                    _context, _session, opts, "client", IncomingConnectionFilter.ALLOW);
                _log.debug("* manager created");
                I2PServerSocket ssocket = mgr.getServerSocket();
                _log.debug("* server socket created");
                while (true) {
                    I2PSocket socket = ssocket.accept();
                    _log.debug("* socket accepted: " + socket);
                    try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
                    socket.close();
                    _log.debug("* socket closed: " + socket);
                }
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
                I2PSocketManager mgr = new I2PSocketManagerFull(
                    _context, _session, opts, "client", IncomingConnectionFilter.ALLOW);
                _log.debug("* manager created");
                I2PSocket socket = mgr.connect(_server.getMyDestination());
                _log.debug("* socket created");
                InputStream in = socket.getInputStream();
                int c = in.read();
                if (c != -1)
                    throw new RuntimeException("hrm, we got data?  [" + c + "]");
                socket.close();
                _log.debug("* socket closed");
                mgr.destroySocketManager();
                mgr = null;
                socket = null;
            } catch (Exception e) {
                _log.error("error running", e);
            }
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
            System.exit(0);
        }
        
    }
}
