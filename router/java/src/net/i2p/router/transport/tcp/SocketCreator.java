package net.i2p.router.transport.tcp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;

import net.i2p.util.Log;

class SocketCreator implements Runnable {
    private final static Log _log = new Log(SocketCreator.class);
    private String _host;
    private int _port;
    private Socket _socket;
    private boolean _keepOpen;
    private boolean _established;
    
    public SocketCreator(String host, int port) {
        this(host, port, true);
    }
    public SocketCreator(String host, int port, boolean keepOpen) {
        _host = host;
        _port = port;
        _socket = null;
        _keepOpen = keepOpen;
        _established = false;
    }
    
    public Socket getSocket() { return _socket; }
    
    public boolean couldEstablish() { return _established; }
    
    /** the first byte sent and received must be 0x42 */
    public final static int I2P_FLAG = 0x42;
    /** sent if we arent trying to talk */
    private final static int NOT_I2P_FLAG = 0x2B;
    
    public void run() {
        if (_keepOpen) {
            doEstablish();
        } else {
            checkEstablish();
        }
    }
    
    private void doEstablish() {
        try {
            _socket = new Socket(_host, _port);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Socket created");
            OutputStream os = _socket.getOutputStream();
            os.write(I2P_FLAG);
            os.flush();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("I2P flag sent");
            int val = _socket.getInputStream().read();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Value read: [" + val + "] == flag? [" + I2P_FLAG + "]");
            if (val != I2P_FLAG) {
                _socket.close();
                _socket = null;
            }
            return;
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection to " + _host + ':' + _port, uhe);
            return;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection to " + _host + ':' + _port + ": "+ ioe.getMessage());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error establishing", ioe);
            _socket = null;
            return;
        } finally {
            synchronized (this) {
                notifyAll();
            }
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
            
            OutputStream os = _socket.getOutputStream();
            os.write(NOT_I2P_FLAG);
            os.flush();
            
            int val = _socket.getInputStream().read();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Value read: [" + val + "] == flag? [" + I2P_FLAG + "]");
            
            _socket.close();
            _socket = null;
            _established = (val == I2P_FLAG);
            return;
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection to " + _host + ':' + _port, uhe);
            return;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection to " + _host + ':' + _port + ": "+ ioe.getMessage());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error establishing", ioe);
            _socket = null;
            return;
        } finally {
            synchronized (this) {
                notifyAll();
            }
        }
    }
}
