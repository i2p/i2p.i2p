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
import java.util.LinkedList;
import java.util.List;

import net.i2p.crypto.AESInputStream;
import net.i2p.crypto.AESOutputStream;
import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageReader;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.BandwidthLimitedInputStream;
import net.i2p.router.transport.BandwidthLimitedOutputStream;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

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
    protected int _maxQueuedMessages;
    private long _lastSliceRun;
    private boolean _closed;
    private boolean _weInitiated;
    private long _created;
    protected RouterContext _context;
    
    public final static String PARAM_MAX_QUEUED_MESSAGES = "i2np.tcp.maxQueuedMessages";
    private final static int DEFAULT_MAX_QUEUED_MESSAGES = 10;

    public TCPConnection(RouterContext context, Socket s, boolean locallyInitiated) {
        _context = context;
        _log = context.logManager().getLog(TCPConnection.class);
        _context.statManager().createRateStat("tcp.queueSize", "How many messages were already in the queue when a new message was added?", 
                                              "TCP Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
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
        _lastSliceRun = -1;

        if (_log.shouldLog(Log.INFO))
            _log.info("Connected with peer: " + s.getInetAddress() + ":" + s.getPort());
        updateMaxQueuedMessages();
    }
    
    /** how long has this connection been around for, or -1 if it isn't established yet */
    public long getLifetime() { 
        if (_created > 0) 
            return _context.clock().now() - _created; 
        else
            return -1;
    }
    
    protected boolean weInitiatedConnection() { return _weInitiated; }
    
    private void updateMaxQueuedMessages() {
        String str = _context.router().getConfigSetting(PARAM_MAX_QUEUED_MESSAGES);
        if ( (str != null) && (str.trim().length() > 0) ) {
            try {
                int max = Integer.parseInt(str);
                _maxQueuedMessages = max;
                return;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid max queued messages [" + str + "]");
            }
        }
        _maxQueuedMessages = DEFAULT_MAX_QUEUED_MESSAGES;
    }
    
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

        BigInteger peerPub = new BigInteger(1, peerPubBytes);
        _builder.setPeerPublicValue(peerPub);

        _key = _builder.getSessionKey();
        _extraBytes = _builder.getExtraBytes();
        _iv = new byte[16];
        System.arraycopy(_extraBytes.getData(), 0, _iv, 0, Math.min(_extraBytes.getData().length, _iv.length));
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Session key: " + _key.toBase64() + " extra bytes: " + _extraBytes.getData().length);
    }
    
    protected boolean identifyStationToStation() throws IOException, DataFormatException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        _context.router().getRouterInfo().getIdentity().writeBytes(baos);
        Hash keyHash = _context.sha().calculateHash(_key.getData());
        keyHash.writeBytes(baos);
        Signature sig = _context.dsa().sign(baos.toByteArray(), _context.keyManager().getSigningPrivateKey());
        sig.writeBytes(baos);
    
        byte encr[] = _context.AESEngine().safeEncrypt(baos.toByteArray(),  _key, _iv, 1024);
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
        _remoteIdentity = new RouterIdentity();
        _remoteIdentity.readBytes(bais);
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
        return _context.dsa().verifySignature(rsig, signedData, _remoteIdentity.getSigningPublicKey());
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
        _reader = new I2NPMessageReader(_context, _in, this, "TCP Read [" + _id + "]");
        _reader.startReading();
    }
    
    public void setTransport(TCPTransport trans) { _transport = trans; }
    
    public void addMessage(OutNetMessage msg) {
        msg.timestamp("TCPConnection.addMessage");
        int totalPending = 0;
        boolean fail = false;
        long beforeAdd = _context.clock().now();
        synchronized (_toBeSent) {
            if ( (_maxQueuedMessages > 0) && (_toBeSent.size() >= _maxQueuedMessages) ) {
                fail = true;
            } else { 
                _toBeSent.add(msg);
                totalPending = _toBeSent.size();
                // the ConnectionRunner.processSlice does a wait() until we have messages
            }
            _toBeSent.notifyAll();
        }
        long afterAdd = _context.clock().now();

        _context.statManager().addRateData("tcp.queueSize", totalPending-1, 0);

        if (fail) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("too many queued messages to " + _remoteIdentity.getHash().toBase64() + ": " + totalPending);

            msg.timestamp("TCPConnection.addMessage exceeded max queued");
            _transport.afterSend(msg, false);
            return;
        }
	
        long diff = afterAdd - beforeAdd;
        if (diff > 500) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Lock contention adding a message: " + diff + "ms");
        }

        msg.timestamp("TCPConnection.addMessage after toBeSent.add and notify");

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Add message with toBeSent.size = " + totalPending + " to " + _remoteIdentity.getHash().toBase64());
        if (totalPending <= 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("WTF, total pending after adding " + msg.getMessage().getClass().getName() + " <= 0! " + msg);
        }

        if (slicesTooLong()) {
            if (_log.shouldLog(Log.ERROR)) {
                long sliceTime = _context.clock().now()-_lastSliceRun;
                _log.error("onAdd: Slices are taking too long (" + sliceTime 
                           + "ms) - perhaps the remote side is disconnected or hung? remote=" 
                           + _remoteIdentity.getHash().toBase64());
            }
            closeConnection();
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
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Closing the unestablished connection with " 
                              + _socket.getInetAddress().toString() + ":" 
                              + _socket.getPort(), new Exception("Closed by"));
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
                        _log.warn("Connection closed while the message was sitting on the TCP Connection's queue!  too slow by: " 
                                  + (now-msg.getExpiration()) + "ms: " + msg);
                    _transport.afterSend(msg, false);
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
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
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

        if (slicesTooLong()) {
            if (_log.shouldLog(Log.ERROR)) {
                long sliceTime = _context.clock().now()-_lastSliceRun;
                _log.error("onReceive: Slices are taking too long (" + sliceTime 
                           + "ms) - perhaps the remote side is disconnected or hung?  peer = " 
                           + _remoteIdentity.getHash().toBase64());
            }
            closeConnection();
        }   
    }
    
    public void readError(I2NPMessageReader reader, Exception error) {
        if (_log.shouldLog(Log.ERROR))
            _log.error("Error reading from stream to " + _remoteIdentity.getHash().toBase64());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Error reading from stream to " + _remoteIdentity.getHash().toBase64(), error);
    }
    
    /** 
     * if a slice takes 2 minutes, fuck 'im.  slices at most send one I2NPMessage,
     * which can be up to around 32KB currently.  Basically a minimum 273bps (slower for
     * larger messages - perhaps this min-throughput should be implemented on the
     * output stream as part of the throttling code?  hmmm)
     */
    private final static long MAX_SLICE_DURATION = 120*1000;
    /**
     * Determine if the connection runner is hanging while running its slices.  This can 
     * occur if there's a broken TCP connection that hasn't timed out yet (3 minutes later..)
     * or if the other side's router is just b0rked and isn't .read()ing from its socket anymore.
     * In either case, if this is true then the connection should be closed.  Given the new threading / slice
     * model, a slice that doesn't do anything will take 30-ish seconds (all in .wait())
     *
     */
    private boolean slicesTooLong() {
        if (_lastSliceRun <= 0) return false;
        long diff = _context.clock().now() - _lastSliceRun;
        return (diff > MAX_SLICE_DURATION);
    }
    
    class ConnectionRunner implements Runnable {
        private boolean _running;
        public void run() {
            _running = true;
            while (_running) {
                long startSlice = _context.clock().now();
                _lastSliceRun = startSlice;
                boolean processOk = processSlice();
                if (!processOk) {
                    closeConnection();
                    return;
                }
                long endSlice = _context.clock().now();
            }
        }

        /**
         * Process a slice (push a message if available).
         *
         * @return true if the operation succeeded, false if there was a critical error
         */
        private boolean processSlice() {
            long start = _context.clock().now();

            OutNetMessage msg = null;
            int remaining = 0;
            List timedOut = new LinkedList();

            synchronized (_toBeSent) {
                // loop through, dropping expired messages, waiting until a non-expired
                // one is added, or 30 seconds have passed (catchall in case things bork)
                while (msg == null) {
                    if (_toBeSent.size() <= 0) {
                        try {
                            _toBeSent.wait(30*1000);
                        } catch (InterruptedException ie) {}
                    }
                    remaining = _toBeSent.size();
                    if (remaining <= 0) return true;
                    msg = (OutNetMessage)_toBeSent.remove(0);
                    remaining--;
                    if ( (msg.getExpiration() > 0) && (msg.getExpiration() < start) ) {
                        timedOut.add(msg);
                        msg = null; // keep looking
                    }
                }
            }

            for (Iterator iter = timedOut.iterator(); iter.hasNext(); ) {
                OutNetMessage failed = (OutNetMessage)iter.next();
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Message timed out while sitting on the TCP Connection's queue!  was too slow by: " 
                              + (start-msg.getExpiration()) + "ms to " 
                              + _remoteIdentity.getHash().toBase64() + ": " + msg);
                msg.timestamp("TCPConnection.runner.processSlice expired");
                _transport.afterSend(msg, false);
                return true;
            }

            if (remaining > 0) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("After pulling off a pending message, there are still " + remaining + 
                              " messages queued up for sending to " + _remoteIdentity.getHash().toBase64());
            }

            long afterExpire = _context.clock().now();

            if (msg != null) {
                msg.timestamp("TCPConnection.runner.processSlice fetched");
                //_log.debug("Processing slice - msg to be sent");

                byte data[] = msg.getMessageData();
                if (data == null) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("wtf, for some reason, an I2NPMessage couldn't be serialized...");
                    return true;
                }
                msg.timestamp("TCPConnection.runner.processSlice before sending " 
                              + data.length + " bytes");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Sending " + data.length + " bytes in the slice... to " 
                               + _remoteIdentity.getHash().toBase64());

                try {
                    synchronized (_out) {
                        _out.write(data);
                        _out.flush();
                    }
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("IO error writing out a " + data.length + " byte message to " 
                                  + _remoteIdentity.getHash().toBase64());
                    return false;
                }

                msg.timestamp("TCPConnection.runner.processSlice sent and flushed");
                long end = _context.clock().now();

                long timeLeft = msg.getMessage().getMessageExpiration().getTime() - end;
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message " + msg.getMessage().getClass().getName() 
                              + " (expiring in " + timeLeft + "ms) sent to " 
                              + _remoteIdentity.getHash().toBase64() + " from " 
                              + _context.routerHash().toBase64()
                              + " over connection " + _id + " with " + data.length 
                              + " bytes in " + (end - start) + "ms");
                if (timeLeft < 10*1000) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.warn("Very little time left... time to send [" + (end-start) 
                                  + "] time left [" + timeLeft + "] to "
                                  + _remoteIdentity.getHash().toBase64() + "\n" + msg.toString(), 
                                  msg.getCreatedBy());
                }

                long lifetime = msg.getLifetime();
                if (lifetime > 10*1000) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("The processing of the message took way too long (" + lifetime 
                                  + "ms) - time to send (" + (end-start) + "), time left (" + timeLeft 
                                  + ") to " + _remoteIdentity.getHash().toBase64() + "\n" + msg.toString());
                }
                _transport.afterSend(msg, true);

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Processing slice - message sent completely: " 
                               + msg.getMessage().getClass().getName()  + " to " 
                               + _remoteIdentity.getHash().toBase64());
                if (end - afterExpire > 1000) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Actual sending took too long ( " + (end-afterExpire) 
                                  + "ms) sending " + data.length + " bytes to " 
                                  + _remoteIdentity.getHash().toBase64());
                }
            } 
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
