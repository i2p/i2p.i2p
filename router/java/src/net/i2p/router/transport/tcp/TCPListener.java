package net.i2p.router.transport.tcp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;

import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Listen for TCP connections with a listener thread
 *
 */
class TCPListener {
    private Log _log;
    private TCPTransport _transport;
    private ServerSocket _socket;
    private ListenerRunner _listener;
    private RouterContext _context;
    /** Client Sockets that have been received but not yet handled (oldest first) */
    private List _pendingSockets;
    /** List of SocketHandler runners if we're listening (else an empty list) */
    private List _handlers;
    
    /**
     * How many concurrent connection attempts from peers we will try to
     * deal with at once.
     */
    private static final int CONCURRENT_HANDLERS = 3;
    /** 
     * When things really suck, how long should we wait between attempts to
     * listen to the socket?
     */
    private final static int MAX_FAIL_DELAY = 5*60*1000;
    /** if we're not making progress in 10s, drop 'em */
    final static int HANDLE_TIMEOUT = 30*1000;
    /** id generator for the connections */
    private static volatile int __handlerId = 0;
    
    
    public TCPListener(RouterContext context, TCPTransport transport) {
        _context = context;
        _log = context.logManager().getLog(TCPListener.class);
        _transport = transport;
        _pendingSockets = new ArrayList(10);
        _handlers = new ArrayList(CONCURRENT_HANDLERS);
    }
        
    /** Make sure we are listening per the transport's config */
    public void startListening() {
        TCPAddress addr = new TCPAddress(_transport.getMyHost(), _transport.getPort());
            
        if (addr.getPort() > 0) {
            if (_listener != null) {
                if ( (_listener.getMyAddress().getPort() == addr.getPort()) &&
                     (_listener.getMyAddress().getHost() == null) ) {
                    _listener.getMyAddress().setHost(addr.getHost());
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not starting another listener on " + addr 
                              + " while already listening on " + _listener.getMyAddress());
                return;
            }
            
            _listener = new ListenerRunner(addr);
            Thread t = new I2PThread(_listener, "Listener [" + addr.getPort()+"]");
            t.setDaemon(true);
            t.start();
            
            for (int i = 0; i < CONCURRENT_HANDLERS; i++) {
                SocketHandler handler = new SocketHandler();
                _handlers.add(handler);            
                Thread th = new I2PThread(handler, "Handler " + addr.getPort() + ": " + i);
                th.setDaemon(true);
                th.start();
            }
        }
    }
    
    public void stopListening() {
        if (_listener != null)
            _listener.stopListening();
        
        for (int i = 0; i < _handlers.size(); i++) {
            SocketHandler h = (SocketHandler)_handlers.get(i);
            h.stopHandling();
        }
        _handlers.clear();
        
        if (_socket != null) {
            try {
                _socket.close();
                _socket = null;
            } catch (IOException ioe) {}
        }
        _listener = null;
    }
    
    private InetAddress getInetAddress(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException uhe) {
            _log.warn("Listen host " + host + " unknown", uhe);
            try {
                return InetAddress.getLocalHost();
            } catch (UnknownHostException uhe2) {
                _log.error("Local host is not reachable", uhe2);
                return null;
            }
        }
    }
    
    class ListenerRunner implements Runnable {
        private boolean _isRunning;
        private int _nextFailDelay = 1000;
        private TCPAddress _myAddress;
        public ListenerRunner(TCPAddress address) {
            _isRunning = true;
            _myAddress = address;
        }
        public void stopListening() { _isRunning = false; }
        
        public TCPAddress getMyAddress() { return _myAddress; }
        
        public void run() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Beginning TCP listener on " + _myAddress);
            
            int curDelay = 0;
            while (_isRunning) {
                try {
                    if ( (_transport.shouldListenToAllInterfaces()) || (_myAddress.getHost() == null) ) {
                        _socket = new ServerSocket(_myAddress.getPort());
                    } else {
                        InetAddress listenAddr = getInetAddress(_myAddress.getHost());
                        _socket = new ServerSocket(_myAddress.getPort(), 5, listenAddr);
                    }
                    String host = (null == _myAddress.getHost() ? "0.0.0.0" : _myAddress.getHost());
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Begin looping for host " + host + ":" + _myAddress.getPort());
                    curDelay = 0;
                    loop();
                } catch (IOException ioe) {
                    if (_isRunning && _context.router().isAlive())
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Error listening to tcp connection " + _myAddress.getHost() + ":" 
                                       + _myAddress.getPort(), ioe);
                }
                
                if (_socket != null) {
                    try { _socket.close(); } catch (IOException ioe) {}
                    _socket = null;
                }
                
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error listening, waiting " + _nextFailDelay + "ms before we try again");
                try { Thread.sleep(_nextFailDelay); } catch (InterruptedException ie) {}
                curDelay += _nextFailDelay;
                _nextFailDelay *= 5;
                if (_nextFailDelay > MAX_FAIL_DELAY)
                    _nextFailDelay = MAX_FAIL_DELAY;
            }
            if (_isRunning && _context.router().isAlive())
                if (_log.shouldLog(Log.ERROR))
                    _log.error("CANCELING TCP LISTEN.  delay = " + curDelay);
            _isRunning = false;
        }
        private void loop() {
            while (_isRunning && _context.router().isAlive()) {
                try {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Waiting for a connection on " + _myAddress.getHost() + ":" + _myAddress.getPort());
                    
                    Socket s = _socket.accept();
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Connection handled on " + _myAddress.getHost() + ":" + _myAddress.getPort() + " with " + s.getInetAddress().toString() + ":" + s.getPort());
                    
                    handle(s);
                    
                } catch (SocketException se) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error handling a connection - closed?", se);
                    return;
                } catch (Throwable t) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error handling a connection", t);
                }
            }
        }
    }
    
    /** 
     * Just toss it on a queue for our pool of handlers to deal with (but also
     * queue up a timeout event in case they're swamped)
     *
     */
    private void handle(Socket s) {
        SimpleTimer.getInstance().addEvent(new CloseUnhandled(s), HANDLE_TIMEOUT);
        synchronized (_pendingSockets) {
            _pendingSockets.add(s);
            _pendingSockets.notifyAll();
        }
    }
    
    /** callback to close an unhandled socket (if the handlers are overwhelmed) */
    private class CloseUnhandled implements SimpleTimer.TimedEvent {
        private Socket _cur;
        public CloseUnhandled(Socket socket) {
            _cur = socket;
        }
        public void timeReached() {
            boolean removed;
            synchronized (_pendingSockets) {
                removed = _pendingSockets.remove(_cur);
            }
            if (removed) {
                // handlers hadn't taken it yet, so close it
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Closing unhandled socket " + _cur);
                try { _cur.close(); } catch (IOException ioe) {}
            }
        }
        
    }
    
    /**
     * Implement a runner for the pool of handlers, pulling sockets out of the
     * _pendingSockets queue and synchronously pumping them through a 
     * TimedHandler. 
     *
     */
    private class SocketHandler implements Runnable {
        private boolean _handle;
        public SocketHandler() {
            _handle = true;
        }
        public void run () {
            while (_handle) {
                Socket cur = null;
                try {
                    synchronized (_pendingSockets) {
                        if (_pendingSockets.size() <= 0)
                            _pendingSockets.wait();
                        else
                            cur = (Socket)_pendingSockets.remove(0);
                    }
                } catch (InterruptedException ie) {}
                
                if (cur != null) 
                    handleSocket(cur);
                cur = null;
            }
        }
        public void stopHandling() { _handle = false; }
        
        /** 
         * blocking call to establish the basic connection, but with a timeout
         * in the TimedHandler 
         */
        private void handleSocket(Socket s) {
            TimedHandler h = new TimedHandler(s);
            h.handle();
        }
    }

    private class TimedHandler implements SimpleTimer.TimedEvent {
        private int _handlerId;
        private Socket _socket;
        private boolean _wasSuccessful;
        public TimedHandler(Socket socket) {
            _socket = socket;
            _wasSuccessful = false;
            _handlerId = ++__handlerId;
        }
        public int getHandlerId() { return _handlerId; }
        public void handle() {
            SimpleTimer.getInstance().addEvent(TimedHandler.this, HANDLE_TIMEOUT);
            ConnectionHandler ch = new ConnectionHandler(_context, _transport, _socket);
            TCPConnection con = null;
            try {
                con = ch.receiveConnection();
            } catch (Exception e) {
                _log.log(Log.CRIT, "Unhandled exception receiving a connection on " + _socket, e);
            }
            if (con != null) {
                _wasSuccessful = true;
                _transport.connectionEstablished(con);
            } else if (ch.getTestComplete()) {
                // not a connection, but we verified the test
                _wasSuccessful = true;
            }
            if (!_wasSuccessful)
                _transport.addConnectionErrorMessage(ch.getError());
        }
        public boolean wasSuccessful() { return _wasSuccessful; }

        /**
         * Called after a timeout period - if we haven't already established the
         * connection, close the socket (interrupting any blocking ops)
         *
         */
        public void timeReached() {
            if (wasSuccessful()) {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Handle successful");
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to handle in the time allotted");
                try { _socket.close(); } catch (IOException ioe) {}
            }
        }
    }
}
