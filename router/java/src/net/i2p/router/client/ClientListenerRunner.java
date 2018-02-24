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
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import net.i2p.client.I2PClient;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

/**
 * Listen for connections on the specified port, and toss them onto the client manager's
 * set of connections once they are established.
 *
 * This is not used for internal (in-JVM) connections - see ClientManager and QueuedClientConnectionRunner.
 *
 * Note that this is extended by SSLClientListenerRunner for SSL,
 * and by DomainClientListenerRunner in Android for domain sockets.
 *
 * @author jrandom
 */
class ClientListenerRunner implements Runnable {
    protected final Log _log;
    protected final RouterContext _context;
    protected final ClientManager _manager;
    protected ServerSocket _socket;
    protected final int _port;
    protected final boolean _bindAllInterfaces;
    protected volatile boolean _running;
    protected volatile boolean _listening;
    
    public static final String BIND_ALL_INTERFACES = "i2cp.tcp.bindAllInterfaces";

    public ClientListenerRunner(RouterContext context, ClientManager manager, int port) {
        _context = context;
        _log = _context.logManager().getLog(getClass());
        _manager = manager;
        _port = port;
        _bindAllInterfaces = context.getBooleanProperty(BIND_ALL_INTERFACES);
    }
    
    public boolean isListening() { return _running && _listening; }
    
    /** 
     * Get a ServerSocket.
     * Split out so it can be overridden for SSL.
     * @since 0.8.3
     */
    protected ServerSocket getServerSocket() throws IOException {
        if (_bindAllInterfaces) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Listening on port " + _port + " on all interfaces");
            return new ServerSocket(_port);
        } else {
            String listenInterface = _context.getProperty(ClientManagerFacadeImpl.PROP_CLIENT_HOST, 
                                                          ClientManagerFacadeImpl.DEFAULT_HOST);
            if (_log.shouldLog(Log.INFO))
                _log.info("Listening on port " + _port + " of the specific interface: " + listenInterface);
            return new ServerSocket(_port, 0, InetAddress.getByName(listenInterface));
        }
    }

    public void run() { runServer(); }

    /** 
     * Start up the socket listener, listens for connections, and
     * fires those connections off via {@link #runConnection runConnection}.  
     * This only returns if the socket cannot be opened or there is a catastrophic
     * failure.
     *
     */
    protected void runServer() {
        _running = true;
        int curDelay = 1000;
        final String portMapperService = (this instanceof SSLClientListenerRunner) ? PortMapper.SVC_I2CP_SSL
                                                                                   : PortMapper.SVC_I2CP;
        while (_running) {
            try {
                _socket = getServerSocket();
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ServerSocket created, before accept: " + _socket);
                if (_port > 0) {
                    // not for DomainClientListenerRunner
                    _context.portMapper().register(portMapperService, _socket.getInetAddress().getHostAddress(), _port);
                }                
                curDelay = 1000;
                _listening = true;
                while (_running) {
                    try {
                        Socket socket = _socket.accept();
                        if (validate(socket)) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Connection received");
                            socket.setKeepAlive(true);
                            runConnection(socket);
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Refused connection from " + socket.getInetAddress());
                            try {
                                socket.close();
                            } catch (IOException ioe) {}
                        }
                    } catch (IOException ioe) {
                        if (isAlive()) 
                            _log.error("Server error accepting", ioe);
                    } catch (Throwable t) {
                        if (isAlive()) 
                            _log.error("Fatal error running client listener - killing the thread!", t);
                        _listening = false;
                        return;
                    }
                }
            } catch (IOException ioe) {
                if (isAlive()) 
                    _log.error("Error listening on port " + _port, ioe);
            } finally {
                if (_port > 0) {
                    // not for DomainClientListenerRunner
                    _context.portMapper().unregister(portMapperService);
                }                
            }
            
            _listening = false;
            if (_socket != null) {
                try { _socket.close(); } catch (IOException ioe) {}
                _socket = null; 
            }
            
            if (!isAlive()) break;
            
            if (curDelay < 60*1000)
                _log.error("Error listening, waiting " + (curDelay/1000) + "s before we try again");
            else
                _log.log(Log.CRIT, "I2CP error listening to port " + _port + " - is another I2P instance running? Resolve conflicts and restart");
            try { Thread.sleep(curDelay); } catch (InterruptedException ie) {}
            curDelay = Math.min(curDelay*3, 60*1000);
        }

        if (isAlive())
            _log.error("CANCELING I2CP LISTEN", new Exception("I2CP Listen cancelled!!!"));
        _running = false;
    }
    
    /** 
     *  Just so unit tests don't NPE, where router could be null.
     *  @since 0.9.20
     */
    private boolean isAlive() {
        Router r = _context.router();
        return r == null || r.isAlive();
    }	

    /** give the i2cp client 5 seconds to show that they're really i2cp clients */
    protected final static int CONNECT_TIMEOUT = 5*1000;

    /**
     *  Verify the first byte.
     */
    protected boolean validate(Socket socket) {
        try {
            InputStream is = socket.getInputStream();
            socket.setSoTimeout(CONNECT_TIMEOUT);
            boolean rv = is.read() == I2PClient.PROTOCOL_BYTE;
            socket.setSoTimeout(0);
            return rv;
        } catch (IOException ioe) {}
        if (_log.shouldLog(Log.WARN))
             _log.warn("Peer did not authenticate themselves as I2CP quickly enough, dropping");
        return false;
    }

    /**
     * Handle the connection by passing it off to a {@link ClientConnectionRunner ClientConnectionRunner}
     *
     */
    protected void runConnection(Socket socket) {
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
}
