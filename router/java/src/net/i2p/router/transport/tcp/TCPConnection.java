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
import net.i2p.stat.RateStat;
import net.i2p.stat.Rate;
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
    private RateStat _sendRate;
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
        _context.statManager().createRateStat("tcp.probabalisticDropQueueSize", "How many bytes were queued to be sent when a message as dropped probabalistically?", "TCP", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l } );
        _context.statManager().createRateStat("tcp.queueSize", "How many bytes were queued on a connection?", "TCP", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l } );
        _context.statManager().createRateStat("tcp.sendBps", "How fast are we sending data to a peer?", "TCP", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l } );
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
        String peer = _ident.calculateHash().toBase64().substring(0,6);
        String name = "TCP Read [" + peer + "]";

        _sendRate = new RateStat("tcp.sendRatePeer", "How many bytes are in the messages sent to " + peer, peer, new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        
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
            OutNetMessage msg = (OutNetMessage)msgs.get(i);
            msg.timestamp("closeConnection");
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
        msg.timestamp("TCPConnection.addMessage");
        List expired = null;
        int remaining = 0;
        synchronized (_pendingMessages) {
            _pendingMessages.add(msg);
            expired = locked_expireOld();
            locked_throttle();
            remaining = _pendingMessages.size();
            _pendingMessages.notifyAll();
        }
        if (expired != null) {
            for (int i = 0; i < expired.size(); i++) {
                OutNetMessage cur = (OutNetMessage)expired.get(i);
                cur.timestamp("TCPConnection.addMessage expired");
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Message " + cur.getMessageId() + " expired on the queue to " 
                              + _ident.getHash().toBase64().substring(0,6)
                              + " (queue size " + remaining + ") with lifetime " 
                              + cur.getLifetime());
                sent(cur, false, 0);
            }
        }
    }
    
    private boolean shouldDropProbabalistically() {
        return Boolean.valueOf(_context.getProperty("tcp.dropProbabalistically", "false")).booleanValue();
    }
    
    /**
     * Implement a probabalistic dropping of messages on the queue to the 
     * peer along the lines of RFC2309.
     *
     */
    private void locked_throttle() {
        if (!shouldDropProbabalistically()) return;
        int bytesQueued = 0;
        long earliestExpiration = -1;
        for (int i = 0; i < _pendingMessages.size(); i++) {
            OutNetMessage msg = (OutNetMessage)_pendingMessages.get(i);
            bytesQueued += (int)msg.getMessageSize();
            if ( (earliestExpiration < 0) || (msg.getExpiration() < earliestExpiration) )
                earliestExpiration = msg.getExpiration();
        }

        if (bytesQueued > 0)
            _context.statManager().addRateData("tcp.queueSize", bytesQueued, _pendingMessages.size());

        long sendRate = getSendRate();
        long bytesSendableUntilFirstExpire = sendRate * (earliestExpiration - _context.clock().now()) / 1000;
        
        // try to keep the queue less than half full
        long excessQueued = bytesQueued - (bytesSendableUntilFirstExpire/2); 
        if ( (excessQueued > 0) && (_pendingMessages.size() > 1) && (_transport != null) )
            locked_probabalisticDrop(excessQueued);
    }
    
    /** how many Bps we are sending data to the peer (or 2KBps if we don't know) */
    public long getSendRate() {
        if (_sendRate == null) return 2*1024;
        _sendRate.coallesceStats();
        Rate r = _sendRate.getRate(60*1000);
        if (r == null) {
            return 2*1024;
        } else if (r.getLastEventCount() <= 2) {
            r = _sendRate.getRate(5*60*1000);
            if (r.getLastEventCount() <= 2)
                r = _sendRate.getRate(60*60*1000);
        }
        
        if (r.getLastEventCount() <= 2) {
            return 2*1024;
        } else {
            long bps = (long)(r.getLastTotalValue() * 1000 / r.getLastTotalEventTime());
            _context.statManager().addRateData("tcp.sendBps", bps, 0);
            return bps;
        }
    }
    
    /**
     * Probabalistically drop messages in relation to their size vs how much
     * we've exceeded our target queue usage.
     */
    private void locked_probabalisticDrop(long excessBytesQueued) {
        for (int i = 0; i < _pendingMessages.size() && excessBytesQueued > 0; i++) {
            OutNetMessage msg = (OutNetMessage)_pendingMessages.get(i);
            int p = getDropProbability(msg.getMessageSize(), excessBytesQueued);
            if (_context.random().nextInt(100) > p) {
                _pendingMessages.remove(i);
                i--;
                msg.timestamp("Probabalistically dropped due to queue size " + excessBytesQueued);
                sent(msg, false, -1);
                _context.statManager().addRateData("tcp.probabalisticDropQueueSize", excessBytesQueued, msg.getLifetime());
                // since we've already dropped down this amount, lets reduce the
                // number of additional messages dropped
                excessBytesQueued -= msg.getMessageSize();
            }
        }
    }

    private int getDropProbability(long msgSize, long excessBytesQueued) {
        if (msgSize > excessBytesQueued)
            return 100;
        return (int)(100.0*(msgSize/excessBytesQueued));
    }
    
    private List locked_expireOld() {
        long now = _context.clock().now();
        List expired = null;
        for (int i = 0; i < _pendingMessages.size(); i++) {
            OutNetMessage cur = (OutNetMessage)_pendingMessages.get(i);
            if (cur.getExpiration() < now) {
                _pendingMessages.remove(i);
                if (expired == null)
                    expired = new ArrayList(1);
                expired.add(cur);
                i--;
            }
        }
        return expired;
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
            int queueSize = 0;
            synchronized (_pendingMessages) {
                queueSize = _pendingMessages.size();
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
                    cur.timestamp("TCPConnection.getNextMessage expired");
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Message " + cur.getMessageId() + " expired on the queue to " 
                                  + _ident.getHash().toBase64().substring(0,6)
                                  + " (queue size " + queueSize + ") with lifetime " 
                                  + cur.getLifetime());
                    sent(cur, false, 0);
                }
            }
        }
        
        if (msg != null)
            msg.timestamp("TCPConnection.getNextMessage retrieved");
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
        if (ok)
            _sendRate.addData(msg.getMessageSize(), msg.getLifetime());
    }
}
