package net.i2p.router.admin;
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

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Listen for connections on the specified port, and toss them onto the client manager's
 * set of connections once they are established.
 *
 * @author jrandom
 */
public class AdminListener implements Runnable {
    private Log _log;
    private RouterContext _context;
    private ServerSocket _socket;
    private int _port;
    private boolean _running;
    private long _nextFailDelay = 1000;
    
    public AdminListener(RouterContext context, int port) {
        _context = context;
        _log = context.logManager().getLog(AdminListener.class);
        _port = port;
        _running = false;
    }
    
    public void restart() {
        // this works by taking advantage of the auto-retry mechanism in the 
        // startup() loop (which we reset to wait 1s).  by failing the socket
        // (through close()) and nulling it, we will have to try to build a new
        // serverSocket (using the *new* _port)
        _nextFailDelay = 1000;
        ServerSocket s = _socket;
        try {
            _socket = null;
            s.close();
        } catch (IOException ioe) {}
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
    public void startup() {
        _running = true;
        int curDelay = 0;
        while ( (_running) && (curDelay < MAX_FAIL_DELAY) ) {
            try {
                _log.info("Starting up listening for connections on port " + _port);
                _socket = new ServerSocket(_port);
                curDelay = 0;
                while (_running && (_socket != null) )  {
                    try {
                        Socket socket = _socket.accept();
                        _log.debug("Connection received");
                        runConnection(socket);
                    } catch (IOException ioe) {
                        _log.error("Server error accepting", ioe);
                    } catch (Throwable t) {
                        _log.error("Fatal error running client listener - killing the thread!", t);
                        return;
                    }
                }
            } catch (IOException ioe) {
                _log.error("Error listening on port " + _port, ioe);
            }
            
            if (_socket != null) {
                try { _socket.close(); } catch (IOException ioe) {}
                _socket = null;
            }
            
            _log.error("Error listening, waiting " + _nextFailDelay + "ms before we try again");
            try { Thread.sleep(_nextFailDelay); } catch (InterruptedException ie) {}
            curDelay += _nextFailDelay;
            _nextFailDelay *= 5;
        }
        
        _log.error("CANCELING ADMIN LISTENER.  delay = " + curDelay, new Exception("ADMIN LISTENER cancelled!!!"));
        _running = false;
    }
    
    /**
     * Handle the connection by passing it off to an AdminRunner
     *
     */
    protected void runConnection(Socket socket) throws IOException {
        AdminRunner runner = new AdminRunner(_context, socket);
        I2PThread t = new I2PThread(runner);
        t.setName("Admin Runner");
        //t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }
    
    public void shutdown() {
        _running = false;
        if (_socket != null) try {
            _socket.close();
            _socket = null;
        } catch (IOException ioe) {}
    }
    public void run() { startup(); }
}
