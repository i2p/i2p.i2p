package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import net.i2p.client.I2PClient;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Listen for connections on the specified port, and toss them onto the client manager's
 * set of connections once they are established.
 *
 * @author jrandom
 */
public class ClientListenerRunner implements Runnable {
    private Log _log;
    private RouterContext _context;
    private ClientManager _manager;
    private ServerSocket _socket;
    private int _port;
    private boolean _running;
    private long _nextFailDelay = 1000;
    
    public ClientListenerRunner(RouterContext context, ClientManager manager, int port) {
        _context = context;
        _log = _context.logManager().getLog(ClientListenerRunner.class);
        _manager = manager;
        _port = port;
        _running = false;
    }
    
    public void setPort(int port) { _port = port; }
    public int getPort() { return _port; }
    
    /** max time to bind */
    private final static int MAX_FAIL_DELAY = 5*60*1000;
    
    /** 
     * Start up the socket listener, listens for connections, and
     * fires those connections off via {@link #runConnection runConnection}.  
     * This only returns if the socket cannot be opened or there is a catastrophic
     * failure.
     *
     */
    public void runServer() {
        _running = true;
        int curDelay = 0;
        while ( (_running) && (curDelay < MAX_FAIL_DELAY) ) {
            try {
                _log.info("Starting up listening for connections on port " + _port);
                _socket = new ServerSocket(_port);
                curDelay = 0;
                while (_running) {
                    try {
                        Socket socket = _socket.accept();
                        if (validate(socket)) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Connection received");
                            runConnection(socket);
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Refused connection from " + socket.getInetAddress());
                            socket.close();
                        }
                    } catch (IOException ioe) {
                        if (_context.router().isAlive()) 
                            _log.error("Server error accepting", ioe);
                    } catch (Throwable t) {
                        if (_context.router().isAlive()) 
                            _log.error("Fatal error running client listener - killing the thread!", t);
                        return;
                    }
                }
            } catch (IOException ioe) {
                if (_context.router().isAlive()) 
                    _log.error("Error listening on port " + _port, ioe);
            }

            if (_socket != null) {
                try { _socket.close(); } catch (IOException ioe) {}
                _socket = null; 
            }
            
            if (!_context.router().isAlive()) break;
            
            _log.error("Error listening, waiting " + _nextFailDelay + "ms before we try again");
            try { Thread.sleep(_nextFailDelay); } catch (InterruptedException ie) {}
            curDelay += _nextFailDelay;
            _nextFailDelay *= 5;
        }

        if (_context.router().isAlive())
            _log.error("CANCELING I2CP LISTEN.  delay = " + curDelay, new Exception("I2CP Listen cancelled!!!"));
        _running = false;
    }
    
    /** give the i2cp client 5 seconds to show that they're really i2cp clients */
    private final static int CONNECT_TIMEOUT = 5*1000;
    
    private boolean validate(Socket socket) {
        try {
            socket.setSoTimeout(CONNECT_TIMEOUT);
            int read = socket.getInputStream().read();
            if (read != I2PClient.PROTOCOL_BYTE)
                return false;
            socket.setSoTimeout(0);
            return true;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Peer did not authenticate themselves as I2CP quickly enough, dropping");
            return false;
        }
    }
    /**
     * Handle the connection by passing it off to a {@link ClientConnectionRunner ClientConnectionRunner}
     *
     */
    protected void runConnection(Socket socket) throws IOException {
        ClientConnectionRunner runner = new ClientConnectionRunner(_context, _manager, socket);
        _manager.registerConnection(runner);
    }
    
    public void stopListening() { 
        _running = false; 
        if (_socket != null) try { 
            _socket.close(); 
            _socket = null;
        } catch (IOException ioe) {}
    }
    public void run() { runServer(); }
}
