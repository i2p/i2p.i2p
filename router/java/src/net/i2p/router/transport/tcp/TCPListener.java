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

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

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
    
    public TCPListener(RouterContext context, TCPTransport transport) {
        _context = context;
        _log = context.logManager().getLog(TCPListener.class);
        _myAddress = null;
        _transport = transport;
    }
    
    public void setAddress(TCPAddress address) { _myAddress = address; }
    public TCPAddress getAddress() { return _myAddress; }
    
    public void startListening() {
        _listener = new ListenerRunner();
        Thread t = new I2PThread(_listener);
        t.setName("Listener [" + _myAddress.getPort()+"]");
        t.setDaemon(true);
        t.start();
    }
    
    public void stopListening() {
        _listener.stopListening();
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
            _log.info("Beginning TCP listener");
            
            int curDelay = 0;
            while ( (_isRunning) && (curDelay < MAX_FAIL_DELAY) ) {
                try {
                    if (_transport.getListenAddressIsValid()) {
                        _socket = new ServerSocket(_myAddress.getPort(), 5, getInetAddress(_myAddress.getHost()));
                    } else {
                        _socket = new ServerSocket(_myAddress.getPort());
                    }
                    _log.info("Begin looping for host " + _myAddress.getHost() + ":" + _myAddress.getPort());
                    curDelay = 0;
                    loop();
                } catch (IOException ioe) {
                    _log.error("Error listening to tcp connection " + _myAddress.getHost() + ":" + _myAddress.getPort(), ioe);
                }
                
                if (_socket != null) {
                    stopListening();
                    try { _socket.close(); } catch (IOException ioe) {}
                    _socket = null;
                }
                
                _log.error("Error listening, waiting " + _nextFailDelay + "ms before we try again");
                try { Thread.sleep(_nextFailDelay); } catch (InterruptedException ie) {}
                curDelay += _nextFailDelay;
                _nextFailDelay *= 5;
            }
            _log.error("CANCELING TCP LISTEN.  delay = " + curDelay, new Exception("TCP Listen cancelled!!!"));
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
                    _log.error("Error handling a connection - closed?", se);
                    return;
                } catch (Throwable t) {
                    _log.error("Error handling a connection", t);
                }
            }
        }
    }
    
    private void handle(Socket s) {
        I2PThread t = new I2PThread(new BlockingHandler(s));
        t.setDaemon(true);
        t.setName("BlockingHandler:"+_transport.getListenPort());
        t.start();
    }
    
    private class BlockingHandler implements Runnable {
        private Socket _handledSocket;
        public BlockingHandler(Socket socket) {
            _handledSocket = socket;
        }
        public void run() {
            TimedHandler h = new TimedHandler(_handledSocket);
            I2PThread t = new I2PThread(h);
            t.setDaemon(true);
            t.start();
            try {
                synchronized (h) {
                    h.wait(HANDLE_TIMEOUT);
                }
            } catch (InterruptedException ie) {
                // got through early...
            }
            if (h.wasSuccessful()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Handle successful");
            } else {
                if (h.receivedIdentByte()) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unable to handle in the time allotted");
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Peer didn't send the ident byte, so either they were testing us, or portscanning");
                }
                try { _handledSocket.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /** if we're not making progress in 30s, drop 'em */
    private final static long HANDLE_TIMEOUT = 10*1000;
    private static volatile int __handlerId = 0;
    
    private class TimedHandler implements Runnable {
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
        public void run() {
            Thread.currentThread().setName("TimedHandler"+_handlerId + ':' + _transport.getListenPort());
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
            synchronized (TimedHandler.this) {
                TimedHandler.this.notifyAll();
            }
        }
        public boolean wasSuccessful() { return _wasSuccessful; }
        public boolean receivedIdentByte() { return _receivedIdentByte; }
    }
}
