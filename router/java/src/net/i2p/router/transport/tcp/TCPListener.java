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
    private TCPAddress _myAddress;
    private ServerSocket _socket;
    private ListenerRunner _listener;
    private RouterContext _context;
    private List _pendingSockets;
    private List _handlers;
    
    public TCPListener(RouterContext context, TCPTransport transport) {
        _context = context;
        _log = context.logManager().getLog(TCPListener.class);
        _myAddress = null;
        _transport = transport;
        _pendingSockets = new ArrayList(10);
        _handlers = new ArrayList(CONCURRENT_HANDLERS);
    }
    
    public void setAddress(TCPAddress address) { _myAddress = address; }
    public TCPAddress getAddress() { return _myAddress; }
    
    private static final int CONCURRENT_HANDLERS = 3;
    
    public void startListening() {
        for (int i = 0; i < CONCURRENT_HANDLERS; i++) {
            SocketHandler handler = new SocketHandler();
            _handlers.add(handler);
            Thread t = new I2PThread(handler);
            t.setName("Handler" + i+" [" + _myAddress.getPort()+"]");
            t.setDaemon(true);
            t.start();
        }
        _listener = new ListenerRunner();
        Thread t = new I2PThread(_listener);
        t.setName("Listener [" + _myAddress.getPort()+"]");
        t.setDaemon(true);
        t.start();
    }
    
    public void stopListening() {
        _listener.stopListening();
        for (int i = 0; i < _handlers.size(); i++) {
            SocketHandler h = (SocketHandler)_handlers.get(i);
            h.stopHandling();
        }
        _handlers.clear();
        if (_socket != null)
            try {
                _socket.close();
                _socket = null;
            } catch (IOException ioe) {}
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
    
    private final static int MAX_FAIL_DELAY = 5*60*1000;
    
    class ListenerRunner implements Runnable {
        private boolean _isRunning;
        private int _nextFailDelay = 1000;
        public ListenerRunner() {
            _isRunning = true;
        }
        public void stopListening() { _isRunning = false; }
        
        public void run() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Beginning TCP listener");
            
            int curDelay = 0;
            while ( (_isRunning) && (curDelay < MAX_FAIL_DELAY) ) {
                try {
                    if (_transport.getListenAddressIsValid()) {
                        _socket = new ServerSocket(_myAddress.getPort(), 5, getInetAddress(_myAddress.getHost()));
                    } else {
                        _socket = new ServerSocket(_myAddress.getPort());
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Begin looping for host " + _myAddress.getHost() + ":" + _myAddress.getPort());
                    curDelay = 0;
                    loop();
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error listening to tcp connection " + _myAddress.getHost() + ":" 
                                   + _myAddress.getPort(), ioe);
                }
                
                if (_socket != null) {
                    stopListening();
                    try { _socket.close(); } catch (IOException ioe) {}
                    _socket = null;
                }
                
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error listening, waiting " + _nextFailDelay + "ms before we try again");
                try { Thread.sleep(_nextFailDelay); } catch (InterruptedException ie) {}
                curDelay += _nextFailDelay;
                _nextFailDelay *= 5;
            }
            if (_log.shouldLog(Log.ERROR))
                _log.error("CANCELING TCP LISTEN.  delay = " + curDelay);
            _isRunning = false;
        }
        private void loop() {
            while (_isRunning) {
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

    /** if we're not making progress in 30s, drop 'em */
    private final static long HANDLE_TIMEOUT = 10*1000;
    private static volatile int __handlerId = 0;
    
    private class TimedHandler implements SimpleTimer.TimedEvent {
        private int _handlerId;
        private Socket _socket;
        private boolean _wasSuccessful;
        private boolean _receivedIdentByte;
        public TimedHandler(Socket socket) {
            _socket = socket;
            _wasSuccessful = false;
            _handlerId = ++__handlerId;
            _receivedIdentByte = false;
        }
        public int getHandlerId() { return _handlerId; }
        public void handle() {
            SimpleTimer.getInstance().addEvent(TimedHandler.this, HANDLE_TIMEOUT);
            try {
                OutputStream os = _socket.getOutputStream();
                os.write(SocketCreator.I2P_FLAG);
                os.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("listener: I2P flag sent");
                int val = _socket.getInputStream().read();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("listener: Value read: [" + val + "] == flag? [" + SocketCreator.I2P_FLAG + "]");
                if (val == -1)
                    throw new UnsupportedOperationException("Peer disconnected while we were looking for the I2P flag");
                if (val != SocketCreator.I2P_FLAG) {
                    throw new UnsupportedOperationException("Peer connecting to us didn't send the right I2P byte [" + val + "]");
                }
                
                _receivedIdentByte = true;
                
                TCPConnection c = new RestrictiveTCPConnection(_context, _socket, false);
                _transport.handleConnection(c, null);
                _wasSuccessful = true;
            } catch (UnsupportedOperationException uoe) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Failed to state they wanted to connect as I2P", uoe);
                _wasSuccessful = false;
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Error listening to the peer", ioe);
                _wasSuccessful = false;
            } catch (Throwable t) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error handling", t);
                _wasSuccessful = false;
            }
        }
        public boolean wasSuccessful() { return _wasSuccessful; }
        public boolean receivedIdentByte() { return _receivedIdentByte; }

        /**
         * Called after a timeout period - if we haven't already established the
         * connection, close the socket (interrupting any blocking ops)
         *
         */
        public void timeReached() {
            if (wasSuccessful()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Handle successful");
            } else {
                if (receivedIdentByte()) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unable to handle in the time allotted");
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Peer didn't send the ident byte, so either they were testing us, or portscanning");
                }
                try { _socket.close(); } catch (IOException ioe) {}
            }
        }
    }
}
