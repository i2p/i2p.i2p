package net.i2p.router.transport.tcp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.i2p.crypto.AESInputStream;
import net.i2p.crypto.AESOutputStream;
import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageReader;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.BandwidthLimitedInputStream;
import net.i2p.router.transport.BandwidthLimitedOutputStream;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;

/**
 * Wraps a connection - this contains a reader thread (via I2NPMessageReader) and
 * a writer thread (ConnectionRunner).  The writer reads the pool of outbound
 * messages and writes them in order, while the reader fires off events
 *
 */
class TCPConnection implements I2NPMessageReader.I2NPMessageEventListener {
    private Log _log;
    protected static int _idCounter = 0;
    protected int _id;
    protected DHSessionKeyBuilder _builder;
    protected Socket _socket;
    protected String _remoteHost;
    protected int _remotePort;
    protected I2NPMessageReader _reader;
    protected InputStream _in;
    protected OutputStream _out;
    protected RouterIdentity _remoteIdentity;
    protected TCPTransport _transport;
    protected ConnectionRunner _runner;
    protected List _toBeSent;
    protected SessionKey _key;
    protected ByteArray _extraBytes;
    protected byte[] _iv;
    private boolean _closed;
    private boolean _weInitiated;
    private long _created;
    protected RouterContext _context;
    
    public TCPConnection(RouterContext context, Socket s, boolean locallyInitiated) throws IOException {
        _context = context;
        _log = context.logManager().getLog(TCPConnection.class);
        _context.statManager().createRateStat("tcp.queueSize", "How many messages were already in the queue when a new message was added (only when it wasnt empty)?", 
                                              "TCP Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tcp.writeTimeLarge", "How long it takes to write a message that is over 2K?", 
                                              "TCP Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tcp.writeTimeSmall", "How long it takes to write a message that is under 2K?", 
                                              "TCP Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tcp.writeTimeSlow", "How long it takes to write a message (ignoring messages transferring in under a second)?", 
                                              "TCP Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _id = ++_idCounter;
        _weInitiated = locallyInitiated;
        _closed = false;
        _socket = s;
        _created = -1;
        _toBeSent = new ArrayList();
        try {
            _in = _socket.getInputStream();
            _out = _socket.getOutputStream();
        } catch (IOException ioe) {
            _log.error("Error getting streams for the connection", ioe);
        }
        _builder = new DHSessionKeyBuilder();
        _extraBytes = null;

        // sun keeps the socket's InetAddress around after its been closed, but kaffe (and the rest of classpath)
        // doesn't, so we've got to check & cache it here if we want to log it later.  (kaffe et al are acting per
        // spec, btw)
        try {
            _remoteHost = s.getInetAddress() + "";
            _remotePort = s.getPort();
        } catch (NullPointerException npe) {
            throw new IOException("kaffe is being picky since the socket closed too fast...");
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Connected with peer: " + _remoteHost + ":" + _remotePort);
    }
    
    /** how long has this connection been around for, or -1 if it isn't established yet */
    public long getLifetime() { 
        if (_created > 0) 
            return _context.clock().now() - _created; 
        else
            return -1;
    }
    
    protected boolean weInitiatedConnection() { return _weInitiated; }
    
    public RouterIdentity getRemoteRouterIdentity() { return _remoteIdentity; }
    int getId() { return _id; }
    int getPendingMessageCount() { 
        synchronized (_toBeSent) {
            return _toBeSent.size(); 
        }
    }
    
    protected void exchangeKey() throws IOException, DataFormatException {
        BigInteger myPub = _builder.getMyPublicValue();
        byte myPubBytes[] = myPub.toByteArray();
        DataHelper.writeLong(_out, 2, myPubBytes.length);
        _out.write(myPubBytes);

        int rlen = (int)DataHelper.readLong(_in, 2);
        byte peerPubBytes[] = new byte[rlen];
        int read = DataHelper.read(_in, peerPubBytes); 
    
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("rlen: " + rlen + " peerBytes: " + DataHelper.toString(peerPubBytes) + " read: " + read);

        BigInteger peerPub = new NativeBigInteger(1, peerPubBytes);
        _builder.setPeerPublicValue(peerPub);

        _key = _builder.getSessionKey();
        _extraBytes = _builder.getExtraBytes();
        _iv = new byte[16];
        System.arraycopy(_extraBytes.getData(), 0, _iv, 0, Math.min(_extraBytes.getData().length, _iv.length));
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Session key: " + _key.toBase64() + " extra bytes: " + _extraBytes.getData().length);
    }
    
    protected boolean identifyStationToStation() throws IOException, DataFormatException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(10*1024);
        _context.router().getRouterInfo().writeBytes(baos);
        Hash keyHash = _context.sha().calculateHash(_key.getData());
        keyHash.writeBytes(baos);
        Signature sig = _context.dsa().sign(baos.toByteArray(), _context.keyManager().getSigningPrivateKey());
        sig.writeBytes(baos);
    
        byte encr[] = _context.AESEngine().safeEncrypt(baos.toByteArray(),  _key, _iv, 10*1024);
        DataHelper.writeLong(_out, 2, encr.length);
        _out.write(encr);

        // we've identified ourselves, now read who they are
        int rlen = (int)DataHelper.readLong(_in, 2);
        byte pencr[] = new byte[rlen];
        int read = DataHelper.read(_in, pencr);
        if (read != rlen) 
            throw new DataFormatException("Not enough data in peer ident");
        byte decr[] = _context.AESEngine().safeDecrypt(pencr, _key, _iv);
        if (decr == null)
            throw new DataFormatException("Unable to decrypt - failed exchange?");

        ByteArrayInputStream bais = new ByteArrayInputStream(decr);
        RouterInfo peer = new RouterInfo();
        peer.readBytes(bais);
        _remoteIdentity = peer.getIdentity();
        Hash peerKeyHash = new Hash();
        peerKeyHash.readBytes(bais);

        if (!peerKeyHash.equals(keyHash)) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Peer tried to spoof!");
            return false;
        }
	
        Signature rsig = new Signature();
        rsig.readBytes(bais);
        byte signedData[] = new byte[decr.length - rsig.getData().length];
        System.arraycopy(decr, 0, signedData, 0, signedData.length);
        boolean valid = _context.dsa().verifySignature(rsig, signedData, _remoteIdentity.getSigningPublicKey());
        if (valid) 
            _context.netDb().store(_remoteIdentity.getHash(), peer);
        return valid;
    }
    
    protected final static int ESTABLISHMENT_TIMEOUT = 10*1000; // 10 second lag (not necessarily for the entire establish)
    
    public RouterIdentity establishConnection() {
        BigInteger myPub = _builder.getMyPublicValue();
        try {
            _socket.setSoTimeout(ESTABLISHMENT_TIMEOUT);
            exchangeKey();
            // key exchanged.  now say who we are and prove it
            boolean ok = identifyStationToStation();

            if (!ok)
                throw new DataFormatException("Station to station identification failed!  MITM?");
            else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("TCP connection " + _id + " established with " 
                              + _remoteIdentity.getHash().toBase64());
                _in = new AESInputStream(_context, new BandwidthLimitedInputStream(_context, _in, _remoteIdentity), _key, _iv);
                _out = new AESOutputStream(_context, new BandwidthLimitedOutputStream(_context, _out, _remoteIdentity), _key, _iv);
                _socket.setSoTimeout(0);
                established();
                return _remoteIdentity;
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error establishing connection", ioe);
            closeConnection();
            return null;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error establishing connection", dfe);
            closeConnection();
            return null;
        } catch (Throwable t) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("jrandom is paranoid so we're catching it all during establishConnection", t);
            closeConnection();
            return null;
        } 
    }
    
    protected void established() { _created = _context.clock().now(); }
    
    public void runConnection() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Run connection");
        _runner = new ConnectionRunner();
        Thread t = new I2PThread(_runner);
        t.setName("Run Conn [" + _id + "]");
        t.setDaemon(true);
        t.start();
        _reader = new I2NPMessageReader(_context, _in, this, "TCP Read [" + _id + ":" + _transport.getListenPort() + "]");
        _reader.startReading();
    }
    
    public void setTransport(TCPTransport trans) { _transport = trans; }
    
    /** dont bitch about expiring messages if they don't even last 60 seconds */
    private static final long MIN_MESSAGE_LIFETIME_FOR_PENALTY = 60*1000;
    
    public void addMessage(OutNetMessage msg) {
        msg.timestamp("TCPConnection.addMessage");
        int totalPending = 0;
        boolean fail = false;
        long beforeAdd = _context.clock().now();
        StringBuffer pending = new StringBuffer(64);
        List removed = null;
        synchronized (_toBeSent) {
            for (int i = 0; i < _toBeSent.size(); i++) {
                OutNetMessage cur = (OutNetMessage)_toBeSent.get(i);
                if (cur.getExpiration() < beforeAdd) {
                    if (cur.getLifetime() > MIN_MESSAGE_LIFETIME_FOR_PENALTY) {
                        fail = true;
                        break;
                    } else {
                        // yeah, it expired, so drop it, but it wasn't our
                        // fault (since it was almost expired when we got it
                        if (removed == null)
                            removed = new ArrayList(2);
                        removed.add(cur);
                        _toBeSent.remove(i);
                        i--;
                    }
                }
            }
            if (!fail) {
                _toBeSent.add(msg);
            }
            totalPending = _toBeSent.size();
            pending.append(totalPending).append(": ");
            if (fail) {
                for (int i = 0; i < totalPending; i++) {
                    OutNetMessage cur = (OutNetMessage)_toBeSent.get(i);
                    pending.append(cur.getMessageSize()).append(" byte ");
                    pending.append(cur.getMessageType()).append(" message added");
                    pending.append(" added ").append(cur.getLifetime()).append(" ms ago, ");
                }
            }
            
            // the ConnectionRunner.getNext does a wait() until we have messages
            _toBeSent.notifyAll();
        }
        long afterAdd = _context.clock().now();

        if (totalPending >= 2)
            _context.statManager().addRateData("tcp.queueSize", totalPending-1, 0);

        if (removed != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("messages expired on the queue to " + _remoteIdentity.getHash().toBase64() 
                          + " but they weren't that old: " + removed.size());
            for (int i = 0; i < removed.size(); i++) {
                OutNetMessage cur = (OutNetMessage)removed.get(i);
                msg.timestamp("TCPConnection.addMessage expired but not our fault");
                _transport.afterSend(cur, false, false);
            }
        }
        
        if (fail) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("messages expired on the queue to " + _remoteIdentity.getHash().toBase64() + ": " + pending.toString());

            if (_out instanceof BandwidthLimitedOutputStream) {
                BandwidthLimitedOutputStream o = (BandwidthLimitedOutputStream)_out;
                FIFOBandwidthLimiter.Request req = o.getCurrentRequest();
                if (req != null) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("When the messages timed out, our outbound con requested " 
                                   + req.getTotalOutboundRequested() + " bytes (" + req.getPendingOutboundRequested() 
                                   + " pending) after waiting " + (_context.clock().now() - req.getRequestTime()) + "ms");
                }
            }
            // do we really want to give them a comm error because they're so.damn.slow reading their stream?
            _context.profileManager().commErrorOccurred(_remoteIdentity.getHash());
            
            msg.timestamp("TCPConnection.addMessage saw an expired queued message");
            _transport.afterSend(msg, false, false);
            // should we really be closing a connection if they're that slow?  
            // yeah, i think we should.
            closeConnection();
        } else {

            long diff = afterAdd - beforeAdd;
            if (diff > 500) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lock contention adding a message: " + diff + "ms to " 
                              + _remoteIdentity.getHash().toBase64() + ": " + totalPending);
            }

            msg.timestamp("TCPConnection.addMessage after toBeSent.add and notify");

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Add message with toBeSent.size = " + totalPending + " to " + _remoteIdentity.getHash().toBase64());
            if (totalPending <= 0) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("WTF, total pending after adding " + msg.getMessage().getClass().getName() + " <= 0! " + msg);
            }
        }
    }
    
    void closeConnection() {
        if (_closed) return;
            _closed = true;
        if (_remoteIdentity != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Closing the connection to " + _remoteIdentity.getHash().toBase64(), 
                          new Exception("Closed by"));
        } else {
            if (_socket != null) {
                if (_log.shouldLog(Log.WARN)) {
                    _log.warn("Closing the unestablished connection with " 
                              + _remoteHost  + ":" 
                              + _remotePort, new Exception("Closed by"));
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Closing the unestablished connection", new Exception("Closed by"));
            }
        }
        if (_reader != null) _reader.stopReading();
        if (_runner != null) _runner.stopRunning();
        if (_in != null) try { _in.close(); } catch (IOException ioe) {}
        if (_out != null) try { _out.close(); } catch (IOException ioe) {}
        if (_socket != null) try { _socket.close(); } catch (IOException ioe) {}
        if (_toBeSent != null) {
            long now = _context.clock().now();
            synchronized (_toBeSent) {
                for (Iterator iter = _toBeSent.iterator(); iter.hasNext(); ) {
                    OutNetMessage msg = (OutNetMessage)iter.next();
                    msg.timestamp("TCPTransport.closeConnection caused fail");
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Connection closed to " + _remoteIdentity.getHash().toBase64() 
                                  + " while the message was sitting on the TCP Connection's queue!  too slow by: " 
                                  + (now-msg.getExpiration()) + "ms: " + msg);
                    _transport.afterSend(msg, false, false);
                }
                _toBeSent.clear();
            }
        }
        _transport.connectionClosed(this);
    }
    
    List getPendingMessages() { return _toBeSent; }
    
    public void disconnected(I2NPMessageReader reader) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Remote disconnected: " + _remoteIdentity.getHash().toBase64());
        closeConnection();
    }
    
    public void messageReceived(I2NPMessageReader reader, I2NPMessage message, long msToReceive) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Message received from " + _remoteIdentity.getHash().toBase64());
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2*1024);
            message.writeBytes(baos);
            int size = baos.size();
            // this is called by the I2NPMessageReader's thread, so it delays the reading from this peer only
            //_log.debug("Delaying inbound for size " + size);
            //BandwidthLimiter.getInstance().delayInbound(_remoteIdentity, size);
            _transport.messageReceived(message, _remoteIdentity, null, msToReceive, size);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("How did we read a message that is poorly formatted...", dfe);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("How did we read a message that can't be written to memory...", ioe);
        } 
    }
    
    public void readError(I2NPMessageReader reader, Exception error) {
        if (_log.shouldLog(Log.ERROR))
            _log.error("Error reading from stream to " + _remoteIdentity.getHash().toBase64() + ": " + error.getMessage());
        if (_log.shouldLog(Log.WARN))
            _log.warn("Error reading from stream to " + _remoteIdentity.getHash().toBase64(), error);
    }
    
    /**
     * If we are taking an absurdly long time to send out a message, drop it 
     * since we're overloaded.
     *
     */
    private static final long MAX_LIFETIME_BEFORE_OUTBOUND_EXPIRE = 15*1000;
    
    class ConnectionRunner implements Runnable {
        private boolean _running;
        public void run() {
            _running = true;
            while (_running) {
                OutNetMessage nextMessage = getNext();
                if (nextMessage != null) {
                    boolean sent = doSend(nextMessage);
                    if (!sent) {
                        _running = false;
                    }
                }
            }
            
            closeConnection();
        }
        
        private OutNetMessage getNext() {
            OutNetMessage msg = null;
            while (msg == null) {
                synchronized (_toBeSent) {
                    if (_toBeSent.size() <= 0) {
                        try {
                            _toBeSent.wait();
                        } catch (InterruptedException ie) {}
                    }
                    
                    boolean ancientFound = locked_expireOldMessages();
                    if (ancientFound) {
                        _running = false;
                        return null;
                    }
                    
                    if (_toBeSent.size() > 0) {
                        msg = (OutNetMessage)_toBeSent.remove(0);
                    }
                }
            }
            return msg;
        }
        
        /**
         * Fail any messages that have expired on the queue
         *
         * @return true if any of the messages expired are really really old 
         *              (indicating a hung connection)
         */
        private boolean locked_expireOldMessages() {
            long now = _context.clock().now();
            List timedOut = null;
            for (int i = 0; i < _toBeSent.size(); i++) {
                OutNetMessage cur = (OutNetMessage)_toBeSent.get(i);
                if (cur.getExpiration() < now) {
                    if (timedOut == null)
                        timedOut = new ArrayList(2);
                    timedOut.add(cur);
                    _toBeSent.remove(i);
                    i--;
                } else {
                    long lifetime = cur.timestamp("TCPConnection.runner.locked_expireOldMessages still ok with " 
                                                  + (i) + " ahead and " + (_toBeSent.size()-i-1) 
                                                  + " behind on the queue");
                    if (lifetime > MAX_LIFETIME_BEFORE_OUTBOUND_EXPIRE) {
                        cur.timestamp("TCPConnection.runner.locked_expireOldMessages lifetime too long - " + lifetime);
                        if (timedOut == null)
                            timedOut = new ArrayList(2);
                        timedOut.add(cur);
                        _toBeSent.remove(i);
                        i--;
                    }
                }
            }

            boolean reallySlowFound = false;
            
            if (timedOut != null) {
                for (int i = 0; i < timedOut.size(); i++) {
                    OutNetMessage failed = (OutNetMessage)timedOut.get(i);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Message " + i + "/" + timedOut.size() 
                                  + " timed out while sitting on the TCP Connection's queue!  was too slow by: " 
                                  + (now-failed.getExpiration()) + "ms to " 
                                  + _remoteIdentity.getHash().toBase64() + ": " + failed);
                    failed.timestamp("TCPConnection.runner.locked_expireOldMessages expired with " + _toBeSent.size() + " left");
                    _transport.afterSend(failed, false, false);
                    if (failed.getLifetime() >= MIN_MESSAGE_LIFETIME_FOR_PENALTY)
                        reallySlowFound = true;
                }
            }
            return reallySlowFound;
        }

        /**
         * send the message
         *
         * @return true if the message was sent ok, false if the connection b0rked
         */
        private boolean doSend(OutNetMessage msg) {
            msg.timestamp("TCPConnection.runner.doSend fetched");
            long afterExpire = _context.clock().now();

            long remaining = msg.getExpiration() - afterExpire;
            if (remaining < 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Message " + msg.getMessageType() + "/" + msg.getMessageId() 
                              + " expired before doSend (too slow by " + remaining + "ms)");
                _transport.afterSend(msg, false, false);
                return true;
            }
            
            byte data[] = msg.getMessageData();
            if (data == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("message " + msg.getMessageType() + "/" + msg.getMessageId() 
                              + " expired before it could be sent");
                _transport.afterSend(msg, false, false);
                return true;
            }
            msg.timestamp("TCPConnection.runner.doSend before sending " 
                          + data.length + " bytes");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending " + data.length + " bytes to " 
                           + _remoteIdentity.getHash().toBase64());

            long exp = msg.getMessage().getMessageExpiration().getTime();

            long beforeWrite = 0;
            try {
                synchronized (_out) {
                    beforeWrite = _context.clock().now();
                    _out.write(data);
                    _out.flush();
                }
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("IO error writing out a " + data.length + " byte message to " 
                              + _remoteIdentity.getHash().toBase64());
                return false;
            }

            long end = _context.clock().now();
            long timeLeft = exp - end;

            msg.timestamp("TCPConnection.runner.doSend sent and flushed " + data.length + " bytes");

            if (_log.shouldLog(Log.INFO))
                _log.info("Message " + msg.getMessageType()
                          + " (expiring in " + timeLeft + "ms) sent to " 
                          + _remoteIdentity.getHash().toBase64() + " from " 
                          + _context.routerHash().toBase64()
                          + " over connection " + _id + " with " + data.length 
                          + " bytes in " + (end - afterExpire) + "ms (write took "
                          + (end - beforeWrite) + "ms, prepare took "
                          + (beforeWrite - afterExpire) + "ms)");

            long lifetime = msg.getLifetime();
            if (lifetime > 10*1000) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("The processing of the message took way too long (" + lifetime 
                              + "ms) - time left (" + timeLeft + ") to " 
                              + _remoteIdentity.getHash().toBase64() + "\n" + msg.toString());
            }
            _transport.afterSend(msg, true, (end-beforeWrite));

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("doSend - message sent completely: " 
                           + msg.getMessageSize() + " byte " + msg.getMessageType()  + " message to " 
                           + _remoteIdentity.getHash().toBase64());
            if (end - afterExpire > 1000) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Actual sending took too long ( " + (end-afterExpire) 
                              + "ms) sending " + data.length + " bytes to " 
                              + _remoteIdentity.getHash().toBase64());
            }
            if (data.length > 2*1024)
                _context.statManager().addRateData("tcp.writeTimeLarge", end - beforeWrite, end - beforeWrite);
            else
                _context.statManager().addRateData("tcp.writeTimeSmall", end - beforeWrite, end - beforeWrite);
            if (end-beforeWrite > 1*1024)
                _context.statManager().addRateData("tcp.writeTimeSlow", end - beforeWrite, end - beforeWrite);
            return true;
        }
        
        public void stopRunning() { 
            _running = false; 
            // stop the wait(...)
            synchronized (_toBeSent) {
                _toBeSent.notifyAll();
            }
        }
    }
}
