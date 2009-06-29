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
import java.net.InetAddress;
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
    private boolean _bindAllInterfaces;
    private boolean _running;
    private boolean _listening;
    
    public static final String BIND_ALL_INTERFACES = "i2cp.tcp.bindAllInterfaces";

    public ClientListenerRunner(RouterContext context, ClientManager manager, int port) {
        _context = context;
        _log = _context.logManager().getLog(ClientListenerRunner.class);
        _manager = manager;
        _port = port;
        _running = false;
        _listening = false;
        
        String val = context.getProperty(BIND_ALL_INTERFACES);
        _bindAllInterfaces = Boolean.valueOf(val).booleanValue();
    }
    
    public void setPort(int port) { _port = port; }
    public int getPort() { return _port; }
    public boolean isListening() { return _running && _listening; }
    
    /** 
     * Start up the socket listener, listens for connections, and
     * fires those connections off via {@link #runConnection runConnection}.  
     * This only returns if the socket cannot be opened or there is a catastrophic
     * failure.
     *
     */
    public void runServer() {
        _running = true;
        int curDelay = 1000;
        while (_running) {
            try {
                if (_bindAllInterfaces) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Listening on port " + _port + " on all interfaces");
                    _socket = new ServerSocket(_port);
                } else {
                    String listenInterface = _context.getProperty(ClientManagerFacadeImpl.PROP_CLIENT_HOST, 
                                                                  ClientManagerFacadeImpl.DEFAULT_HOST);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Listening on port " + _port + " of the specific interface: " + listenInterface);
                    _socket = new ServerSocket(_port, 0, InetAddress.getByName(listenInterface));
                }
                
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ServerSocket created, before accept: " + _socket);
                
                curDelay = 1000;
                _listening = true;
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
                        _listening = false;
                        return;
                    }
                }
            } catch (IOException ioe) {
                if (_context.router().isAlive()) 
                    _log.error("Error listening on port " + _port, ioe);
            }
            
            _listening = false;
            if (_socket != null) {
                try { _socket.close(); } catch (IOException ioe) {}
                _socket = null; 
            }
            
            if (!_context.router().isAlive()) break;
            
            if (curDelay < 60*1000)
                _log.error("Error listening, waiting " + (curDelay/1000) + "s before we try again");
            else
                _log.log(Log.CRIT, "I2CP error listening to port " + _port + " - is another I2P instance running? Resolve conflicts and restart");
            try { Thread.sleep(curDelay); } catch (InterruptedException ie) {}
            curDelay = Math.min(curDelay*3, 60*1000);
        }

        if (_context.router().isAlive())
            _log.error("CANCELING I2CP LISTEN", new Exception("I2CP Listen cancelled!!!"));
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
