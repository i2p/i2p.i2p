package net.i2p.router.transport.tcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.Socket;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessageReader;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Central choke point for a single TCP connection to a single peer.
 *
 */
public class TCPConnection {
    private Log _log;
    private RouterContext _context;
    private RouterIdentity _ident;
    private Hash _attemptedPeer;
    private TCPAddress _remoteAddress;
    private List _pendingMessages;
    private InputStream _in;
    private OutputStream _out;
    private Socket _socket;
    private TCPTransport _transport;
    private ConnectionRunner _runner;
    private I2NPMessageReader _reader;
    private long _started;
    private boolean _closed;
    
    public TCPConnection(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TCPConnection.class);
        _pendingMessages = new ArrayList(4);
        _ident = null;
        _remoteAddress = null;
        _in = null;
        _out = null;
        _socket = null;
        _transport = null;
        _started = -1;
        _closed = false;
        _runner = new ConnectionRunner(_context, this);
    }

    /** Who are we talking with (or null if not identified) */
    public RouterIdentity getRemoteRouterIdentity() { return _ident; }
    /** What is the peer's TCP address (using the IP address not hostname) */
    public TCPAddress getRemoteAddress() { return _remoteAddress; }
    /** Who we initially were trying to contact */
    public Hash getAttemptedPeer() { return _attemptedPeer; }
    /** Who are we talking with (or null if not identified) */
    public void setRemoteRouterIdentity(RouterIdentity ident) { _ident = ident; }
    /** What is the peer's TCP address (using the IP address not hostname) */
    public void setRemoteAddress(TCPAddress addr) { _remoteAddress = addr; }
    /** Who we initially were trying to contact */
    public void setAttemptedPeer(Hash peer) { _attemptedPeer = peer; }
    
    /** 
     * Actually start processing the messages on the connection (and reading
     * from the peer, of course).  This call should not block.
     *
     */
    public void runConnection() {
        String name = "TCP Read [" + _ident.calculateHash().toBase64().substring(0,6) + "]";
        _reader = new I2NPMessageReader(_context, _in, new MessageHandler(_transport, this), name);
        _reader.startReading();
        _runner.startRunning();
        _started = _context.clock().now();
    }
    
    /** 
     * Disconnect from the peer immediately.  This stops any related helper
     * threads, closes all streams, and fails all pending messages.  This can
     * be called multiple times safely.
     *
     */
    public synchronized void closeConnection() {
        if (_log.shouldLog(Log.INFO)) {
            if (_ident != null)
                _log.info("Connection between " + _ident.getHash().toBase64().substring(0,6) 
                          + " and " + _context.routerHash().toBase64().substring(0,6)
                          + " closed", new Exception("Closed by"));
            else
                _log.info("Connection between " + _remoteAddress 
                          + " and " + _context.routerHash().toBase64().substring(0,6)
                          + " closed", new Exception("Closed by"));
        }
        if (_closed) return;
        _closed = true;
        synchronized (_pendingMessages) {
            _pendingMessages.notifyAll();
        }
        if (_runner != null)
            _runner.stopRunning();
        if (_reader != null)
            _reader.stopReading();
        if (_in != null) try { _in.close(); } catch (IOException ioe) {}
        if (_out != null) try { _out.close(); } catch (IOException ioe) {}
        if (_socket != null) try { _socket.close(); } catch (IOException ioe) {}
        List msgs = clearPendingMessages();
        for (int i = 0; i < msgs.size(); i++) {
            OutNetMessage msg = (OutNetMessage)msgs.get(0);
            _transport.afterSend(msg, false, true, -1);
        }
        _context.profileManager().commErrorOccurred(_ident.getHash());
        _transport.addConnectionErrorMessage("Connection closed with "
                                             + _ident.getHash().toBase64().substring(0,6)
                                             + " after " + DataHelper.formatDuration(getLifetime()));
        _transport.connectionClosed(this);
    }
    
    /**
     * Pull off any unsent OutNetMessages from the queue
     *
     */
    public List clearPendingMessages() {
        List rv = null;
        synchronized (_pendingMessages) {
            rv = new ArrayList(_pendingMessages);
            _pendingMessages.clear();
            _pendingMessages.notifyAll();
        }
        return rv;
    }
    /**
     * Add the given message to the outbound queue, notifying our 
     * runners that we want to send it.
     *
     */
    public void addMessage(OutNetMessage msg) {
        synchronized (_pendingMessages) {
            _pendingMessages.add(msg);
            _pendingMessages.notifyAll();
        }
    }
    
    /** 
     * Blocking call to retrieve the next pending message.  As a side effect, 
     * this fails messages on the queue that have expired, and in turn never
     * returns an expired message.
     *
     * @return next message or null if the connection has been closed.
     */
    OutNetMessage getNextMessage() {
        OutNetMessage msg = null;
        while ( (msg == null) && (!_closed) ) {
            List expired = null;
            long now = _context.clock().now();
            synchronized (_pendingMessages) {
                for (int i = 0; i < _pendingMessages.size(); i++) {
                    OutNetMessage cur = (OutNetMessage)_pendingMessages.get(i);
                    if (cur.getExpiration() < now) {
                        if (expired == null)
                            expired = new ArrayList(1);
                        expired.add(cur);
                        _pendingMessages.remove(i);
                        i--;
                    }
                }
                
                if (_pendingMessages.size() > 0) {
                    msg = (OutNetMessage)_pendingMessages.remove(0);
                } else {
                    if (expired == null) {
                        try {
                            _pendingMessages.wait();
                        } catch (InterruptedException ie) {}
                    }
                }
            }
            if (expired != null) {
                for (int i = 0; i < expired.size(); i++) {
                    OutNetMessage cur = (OutNetMessage)expired.get(i);
                    sent(cur, false, 0);
                }
            }
        }
        return msg;
    }
    
    /** How long has this connection been active for? */
    public long getLifetime() { return (_started <= 0 ? -1 : _context.clock().now() - _started); }
    
    void setTransport(TCPTransport transport) { _transport = transport; }
    
    /**
     * Configure where this connection should read its data from.
     * This should have any necessary bandwidth limiting and 
     * encryption filters already wrapped in it.
     *
     */
    void setInputStream(InputStream in) { _in = in; }
    /**
     * Configure where this connection should write its data to.
     * This should have any necessary bandwidth limiting and 
     * encryption filters already wrapped in it.
     *
     */
    void setOutputStream(OutputStream out) { _out = out; }
    /**
     * Configure what underlying socket this connection uses.
     * This is only referenced when closing the connection, and 
     * only if it was set.
     */
    void setSocket(Socket socket) { _socket = socket; }
    
    /** Where this connection should write its data to. */
    OutputStream getOutputStream() { return _out; }

    /** Have we been closed already? */
    boolean getIsClosed() { return _closed; }
    RouterContext getRouterContext() { return _context; }
    
    /** 
     * The message was sent.
     *
     * @param msg message in question
     * @param ok was the message sent ok?
     * @param time how long did it take to write the message?
     */
    void sent(OutNetMessage msg, boolean ok, long time) {
        _transport.afterSend(msg, ok, true, time);
    }
}
