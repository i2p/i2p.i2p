package net.i2p.router.transport.tcp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;

import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Helper class to coordinate the creation of sockets to I2P routers
 *
 */
class SocketCreator implements SimpleTimer.TimedEvent {
    private final static Log _log = new Log(SocketCreator.class);
    private String _host;
    private int _port;
    private Socket _socket;
    private boolean _keepOpen;
    private boolean _established;
    private long _created;
    private long _timeoutMs;
    private String _caller;
    
    public SocketCreator(String host, int port) {
        this(host, port, true);
    }
    public SocketCreator(String host, int port, boolean keepOpen) {
        _host = host;
        _port = port;
        _socket = null;
        _keepOpen = keepOpen;
        _established = false;
        _created = System.currentTimeMillis();
    }
    
    public Socket getSocket() { return _socket; }
    
    public boolean couldEstablish() { return _established; }
    
    /** the first byte sent and received must be 0x42 */
    public final static int I2P_FLAG = 0x42;
    /** sent if we arent trying to talk */
    private final static int NOT_I2P_FLAG = 0x2B;
    
    /**
     * Blocking call to determine whether the socket configured can be reached
     * (and whether it is a valid I2P router).  The socket created to test this
     * will be closed afterwards.
     *
     * @param timeoutMs max time to wait for validation
     * @return true if the peer is reachable and sends us the I2P_FLAG, false
     *         otherwise
     */
    public boolean verifyReachability(long timeoutMs) {
        _timeoutMs = timeoutMs;
        _caller = Thread.currentThread().getName();
        SimpleTimer.getInstance().addEvent(this, timeoutMs);
        checkEstablish();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("veriyReachability complete, established? " + _established);
        return _established;
    }
    
    /**
     * Blocking call to establish a socket connection to the peer.  After either
     * the timeout has expired or the socket has been created, the socket and/or
     * its status can be accessed via couldEstablish() and getSocket(), 
     * respectively.  If the socket could not be established in the given time
     * frame, the socket is closed.
     *
     */
    public void establishConnection(long timeoutMs) {
        _timeoutMs = timeoutMs;
        _caller = Thread.currentThread().getName();
        SimpleTimer.getInstance().addEvent(this, timeoutMs);
        doEstablish();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("EstablishConnection complete, established? " + _established);
    }
    
    /**
     * Called when the timeout was reached - depending on our configuration and
     * whether a connection was established, we may want to tear down the socket.
     *
     */
    public void timeReached() {
        long duration = System.currentTimeMillis() - _created;
        if (!_keepOpen) {
            if (_socket != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(_caller + ": timeReached(), dont keep open, and we have a socket.  kill it (" 
                               + duration + "ms, delay " + _timeoutMs + ")");
                try { _socket.close(); } catch (IOException ioe) {}
                _socket = null;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(_caller + ": timeReached(), dont keep open, but we don't have a socket.  noop");
            }
        } else {
            if (_established) {
                // noop
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(_caller + ": timeReached(), keep open, and we have an established socket.  noop");
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(_caller + ": timeReached(), keep open, but we havent established yet.  kill the socket! (" 
                               + duration + "ms, delay " + _timeoutMs + ")");
                if (_socket != null) try { _socket.close(); } catch (IOException ioe) {}
                _socket = null;
            }
        }
    }
    
    /**
     * Create the socket with the intent of keeping it open 
     *
     */
    private void doEstablish() {
        try {
            _socket = new Socket(_host, _port);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Socket created");
            
            if (_socket == null) return;
            OutputStream os = _socket.getOutputStream();
            os.write(I2P_FLAG);
            os.flush();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("I2P flag sent");
            
            if (_socket == null) return;
            int val = _socket.getInputStream().read();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Value read: [" + val + "] == flag? [" + I2P_FLAG + "]");
            if (val != I2P_FLAG) {
                if (_socket != null)
                    _socket.close();
                _socket = null;
            }
            _established = true;
            return;
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection to " + _host + ':' + _port, uhe);
            if (_socket != null) try { _socket.close(); } catch (IOException ioe2) {}
            _socket = null;
            return;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection to " + _host + ':' + _port + ": "+ ioe.getMessage());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error establishing", ioe);
            if (_socket != null) try { _socket.close(); } catch (IOException ioe2) {}
            _socket = null;
            return;
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unknown error establishing connection to " + _host + ':' + _port + ": " + e.getMessage());
            if (_socket != null) try { _socket.close(); } catch (IOException ioe2) {}
            _socket = null;
            return;
        } 
    }
    
    /**
     * Try to establish the connection, but don't actually send the I2P flag.  The
     * other side will timeout waiting for it and consider it a dropped connection,
     * but since they will have sent us the I2P flag first we will still know they are
     * reachable.
     *
     */
    private void checkEstablish() {
        try {
            _socket = new Socket(_host, _port);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Socket created (but we're not sending the flag, since we're just testing them)");
            
            if (_socket == null) return;
            OutputStream os = _socket.getOutputStream();
            os.write(NOT_I2P_FLAG);
            os.flush();
            
            if (_socket == null) return;
            int val = _socket.getInputStream().read();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Value read: [" + val + "] == flag? [" + I2P_FLAG + "]");
            
            
            if (_socket == null) return;
            _socket.close();
            _socket = null;
            _established = (val == I2P_FLAG);
            return;
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection to " + _host + ':' + _port, uhe);
            if (_socket != null) try { _socket.close(); } catch (IOException ioe) {}
            return;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection to " + _host + ':' + _port + ": "+ ioe.getMessage());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error establishing", ioe);
            if (_socket != null) try { _socket.close(); } catch (IOException ioe2) {}
            _socket = null;
            return;
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unknown error establishing connection to " + _host + ':' + _port + ": " + e.getMessage());
            if (_socket != null) try { _socket.close(); } catch (IOException ioe) {}
            _socket = null;
            return;
        } 
    }
}
