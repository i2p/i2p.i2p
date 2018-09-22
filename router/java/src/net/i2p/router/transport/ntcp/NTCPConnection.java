package net.i2p.router.transport.ntcp;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet6Address;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Adler32;

import com.southernstorm.noise.protocol.CipherState;

import net.i2p.crypto.SipHashInline;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.FIFOBandwidthLimiter.Request;
import net.i2p.router.transport.ntcp.NTCP2Payload.Block;
import net.i2p.router.util.PriBlockingQueue;
import net.i2p.util.ByteCache;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.HexDump;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Coordinate the connection to a single peer.
 * NTCP 1 or 2.
 *
 * Public only for UI peers page. Not a public API, not for external use.
 *
 * The NTCP transport sends individual I2NP messages AES/256/CBC encrypted with
 * a simple checksum.  The unencrypted message is encoded as follows:
 *<pre>
 *  +-------+-------+--//--+---//----+-------+-------+-------+-------+
 *  | sizeof(data)  | data | padding | adler checksum of sz+data+pad |
 *  +-------+-------+--//--+---//----+-------+-------+-------+-------+
 *</pre>
 * That message is then encrypted with the DH/2048 negotiated session key
 * (station to station authenticated per the EstablishState class) using the
 * last 16 bytes of the previous encrypted message as the IV.
 *
 * One special case is a metadata message where the sizeof(data) is 0.  In
 * that case, the unencrypted message is encoded as:
 *<pre>
 *  +-------+-------+-------+-------+-------+-------+-------+-------+
 *  |       0       |      timestamp in seconds     | uninterpreted             
 *  +-------+-------+-------+-------+-------+-------+-------+-------+
 *          uninterpreted           | adler checksum of sz+data+pad |
 *  +-------+-------+-------+-------+-------+-------+-------+-------+
 *</pre>
 *
 */
public class NTCPConnection implements Closeable {
    private final RouterContext _context;
    private final Log _log;
    private SocketChannel _chan;
    private SelectionKey _conKey;
    private final FIFOBandwidthLimiter.CompleteListener _inboundListener;
    private final FIFOBandwidthLimiter.CompleteListener _outboundListener;
    /**
     * queue of ByteBuffer containing data we have read and are ready to process, oldest first
     * unbounded and lockless
     */
    private final Queue<ByteBuffer> _readBufs;
    /**
     * list of ByteBuffers containing fully populated and encrypted data, ready to write,
     * and already cleared through the bandwidth limiter.
     * unbounded and lockless
     */
    private final Queue<ByteBuffer> _writeBufs;
    /** Requests that were not granted immediately */
    private final Set<FIFOBandwidthLimiter.Request> _bwInRequests;
    private final Set<FIFOBandwidthLimiter.Request> _bwOutRequests;
    private long _establishedOn;
    private volatile EstablishState _establishState;
    private final NTCPTransport _transport;
    private final boolean _isInbound;
    private final AtomicBoolean _closed = new AtomicBoolean();
    private final RouterAddress _remAddr;
    private RouterIdentity _remotePeer;
    private long _clockSkew; // in seconds
    /**
     * pending unprepared OutNetMessage instances
     */
    //private final CoDelPriorityBlockingQueue<OutNetMessage> _outbound;
    private final PriBlockingQueue<OutNetMessage> _outbound;
    /**
     *  current prepared OutNetMessages, or empty - synchronize to modify or read
     */
    private final List<OutNetMessage> _currentOutbound;
    private SessionKey _sessionKey;
    private byte _prevWriteEnd[];
    /** current partially read I2NP message */
    private ReadState _curReadState;
    private final AtomicInteger _messagesRead = new AtomicInteger();
    private final AtomicInteger _messagesWritten = new AtomicInteger();
    private long _lastSendTime;
    private long _lastReceiveTime;
    private long _lastRateUpdated;
    private final long _created;
    // prevent sending meta before established
    private long _nextMetaTime = Long.MAX_VALUE;
    private final AtomicInteger _consecutiveZeroReads = new AtomicInteger();

    private static final int BLOCK_SIZE = 16;
    private static final int META_SIZE = BLOCK_SIZE;

    private boolean _sendingMeta;
    /** how many consecutive sends were failed due to (estimated) send queue time */
    //private int _consecutiveBacklog;
    private long _nextInfoTime;
    private boolean _mayDisconnect;
    
    /*
     *  Update frequency for send/recv rates in console peers page
     */
    private static final long STAT_UPDATE_TIME_MS = 30*1000;

    /*
     *  Should be longer than 2 * EventPumper.MAX_EXPIRE_IDLE_TIME so it doesn't
     *  interfere with the timeout, at least at first
     */
    private static final int META_FREQUENCY = 45*60*1000;

    /** how often we send our routerinfo unsolicited */
    private static final int INFO_FREQUENCY = 50*60*1000;

    /**
     *  Why this is 16K, and where it is documented, good question?
     *  We claim we can do 32K datagrams so this is a problem.
     *  Needs to be fixed. But SSU can handle it?
     *  In the meantime, don't let the transport bid on big messages.
     */
    static final int BUFFER_SIZE = 16*1024;
    private static final int MAX_DATA_READ_BUFS = 16;
    private static final ByteCache _dataReadBufs = ByteCache.getInstance(MAX_DATA_READ_BUFS, BUFFER_SIZE);
    /** 2 bytes for length and 4 for CRC */
    static final int NTCP1_MAX_MSG_SIZE = BUFFER_SIZE - (2 + 4);

    private static final int INFO_PRIORITY = OutNetMessage.PRIORITY_MY_NETDB_STORE_LOW;
    private static final String FIXED_RI_VERSION = "0.9.12";
    private static final AtomicLong __connID = new AtomicLong();
    private final long _connID = __connID.incrementAndGet();
    
    //// NTCP2 things

    /** See spec. Max Noise payload 65535,
     *  minus 16 byte MAC and 3 byte block header.
     *  Includes 9-byte I2NP header.
     */
    static final int NTCP2_MAX_MSG_SIZE = 65516;
    private static final int PADDING_RAND_MIN = 16;
    private static final int PADDING_MAX = 64;
    private static final int SIP_IV_LENGTH = 8;
    private static final int NTCP2_FAIL_READ = 1024;
    private static final long NTCP2_FAIL_TIMEOUT = 10*1000;
    private static final long NTCP2_TERMINATION_CLOSE_DELAY = 50;
    // don't make combined messages too big, to minimize latency
    // Tunnel data msgs are 1024 + 4 + 9 + 3 = 1040, allow 5
    private static final int NTCP2_PREFERRED_PAYLOAD_MAX = 5 * 1040;
    static final int REASON_UNSPEC = 0;
    static final int REASON_TERMINATION = 1;
    static final int REASON_TIMEOUT = 2;
    static final int REASON_AEAD = 4;
    static final int REASON_OPTIONS = 5;
    static final int REASON_SIGTYPE = 6;
    static final int REASON_SKEW = 7;
    static final int REASON_PADDING = 8;
    static final int REASON_FRAMING = 9;
    static final int REASON_PAYLOAD = 10;
    static final int REASON_MSG1 = 11;
    static final int REASON_MSG2 = 12;
    static final int REASON_MSG3 = 13;
    static final int REASON_FRAME_TIMEOUT = 14;
    static final int REASON_SIGFAIL = 15;
    static final int REASON_S_MISMATCH = 16;
    static final int REASON_BANNED = 17;
    static final int PADDING_MIN_DEFAULT_INT = 0;
    static final int PADDING_MAX_DEFAULT_INT = 1;
    private static final float PADDING_MIN_DEFAULT = PADDING_MIN_DEFAULT_INT / 16.0f;
    private static final float PADDING_MAX_DEFAULT = PADDING_MAX_DEFAULT_INT / 16.0f;
    static final int DUMMY_DEFAULT = 0;
    static final int DELAY_DEFAULT = 0;
    private static final NTCP2Options OUR_PADDING = new NTCP2Options(PADDING_MIN_DEFAULT, PADDING_MAX_DEFAULT,
                                                                     PADDING_MIN_DEFAULT, PADDING_MAX_DEFAULT,
                                                                     DUMMY_DEFAULT, DUMMY_DEFAULT,
                                                                     DELAY_DEFAULT, DELAY_DEFAULT);
    private static final int MIN_PADDING_RANGE = 16;
    private static final int MAX_PADDING_RANGE = 128;
    private NTCP2Options _paddingConfig;
    private int _version;
    private CipherState _sender;
    private long _sendSipk1, _sendSipk2;
    private byte[] _sendSipIV;


    /**
     * Create an inbound connected (though not established) NTCP connection.
     * Caller MUST call transport.establishing(this) after construction.
     * Caller MUST key.attach(this) after construction.
     */
    public NTCPConnection(RouterContext ctx, NTCPTransport transport, SocketChannel chan, SelectionKey key) {
        this(ctx, transport, null, true);
        _chan = chan;
        _version = 1;
        _conKey = key;
        _establishState = new InboundEstablishState(ctx, transport, this);
    }

    /**
     * Create an outbound unconnected NTCP connection.
     * Caller MUST call transport.establishing(this) after construction.
     *
     * @param version must be 1 or 2
     * @throws DataFormatException if there's a problem with the address
     */
    public NTCPConnection(RouterContext ctx, NTCPTransport transport, RouterIdentity remotePeer,
                          RouterAddress remAddr, int version) throws DataFormatException {
        this(ctx, transport, remAddr, false);
        _remotePeer = remotePeer;
        _version = version;
        if (version == 1) {
            _establishState = new OutboundEstablishState(ctx, transport, this);
        } else {
            try {
                _establishState = new OutboundNTCP2State(ctx, transport, this);
            } catch (IllegalArgumentException iae) {
                throw new DataFormatException("bad address? " + remAddr, iae);
            }
        }
    }

    /**
     * Base constructor in/out
     * @since 0.9.36
     */
    private NTCPConnection(RouterContext ctx, NTCPTransport transport, RouterAddress remAddr, boolean isIn) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _created = ctx.clock().now();
        _transport = transport;
        _remAddr = remAddr;
        _lastSendTime = _created;
        _lastReceiveTime = _created;
        _lastRateUpdated = _created;
        _readBufs = new ConcurrentLinkedQueue<ByteBuffer>();
        _writeBufs = new ConcurrentLinkedQueue<ByteBuffer>();
        _bwInRequests = new ConcurrentHashSet<Request>(2);
        _bwOutRequests = new ConcurrentHashSet<Request>(8);
        //_outbound = new CoDelPriorityBlockingQueue(ctx, "NTCP-Connection", 32);
        _outbound = new PriBlockingQueue<OutNetMessage>(ctx, "NTCP-Connection", 32);
        _currentOutbound = new ArrayList<OutNetMessage>(1);
        _isInbound = isIn;
        _inboundListener = new InboundListener();
        _outboundListener = new OutboundListener();
    }

    /**
     *  Valid for inbound; valid for outbound shortly after creation
     */
    public SocketChannel getChannel() { return _chan; }

    /**
     *  Valid for inbound; valid for outbound shortly after creation
     */
    public SelectionKey getKey() { return _conKey; }
    public void setChannel(SocketChannel chan) { _chan = chan; }
    public void setKey(SelectionKey key) { _conKey = key; }

    public boolean isInbound() { return _isInbound; }
    public boolean isEstablished() { return _establishState.isComplete(); }

    /**
     *  @since IPv6
     */
    public boolean isIPv6() {
        return _chan != null &&
               _chan.socket().getInetAddress() instanceof Inet6Address;
    }

    /**
     *  Only valid during establishment;
     *  replaced with EstablishState.VERIFIED or FAILED afterward
     */
    EstablishState getEstablishState() { return _establishState; }

    /**
     *  Only valid for outbound; null for inbound
     */
    public RouterAddress getRemoteAddress() { return _remAddr; }

    /**
     *  Valid for outbound; valid for inbound after handshake
     */
    public RouterIdentity getRemotePeer() { return _remotePeer; }

    /**
     *  Valid for outbound; valid for inbound after handshake
     */
    public void setRemotePeer(RouterIdentity ident) { _remotePeer = ident; }

    /** 
     * We are Bob. NTCP1 only.
     *
     * Caller MUST call recvEncryptedI2NP() after, for any remaining bytes in receive buffer
     *
     * @param clockSkew OUR clock minus ALICE's clock in seconds (may be negative, obviously, but |val| should
     *                  be under 1 minute)
     * @param prevWriteEnd exactly 16 bytes, not copied, do not corrupt, the write AES IV
     * @param prevReadEnd 16 or more bytes, last 16 bytes copied as the read AES IV
     */
    void finishInboundEstablishment(SessionKey key, long clockSkew, byte prevWriteEnd[], byte prevReadEnd[]) {
        NTCPConnection toClose = locked_finishInboundEstablishment(key, clockSkew, prevWriteEnd, prevReadEnd);
        if (toClose != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Old connection closed: " + toClose + " replaced by " + this);
            _context.statManager().addRateData("ntcp.inboundEstablishedDuplicate", toClose.getUptime());
            toClose.close();
        }
        enqueueInfoMessage();
    }
    
    /** 
     * We are Bob. NTCP1 only.
     *
     * @param clockSkew OUR clock minus ALICE's clock in seconds (may be negative, obviously, but |val| should
     *                  be under 1 minute)
     * @param prevWriteEnd exactly 16 bytes, not copied, do not corrupt, the write AES IV
     * @param prevReadEnd 16 or more bytes, last 16 bytes copied as the read AES IV
     * @return old conn to be closed by caller, or null
     */
    private synchronized NTCPConnection locked_finishInboundEstablishment(
            SessionKey key, long clockSkew, byte prevWriteEnd[], byte prevReadEnd[]) {
        if (_establishState == EstablishBase.VERIFIED) {
            IllegalStateException ise = new IllegalStateException("Already finished on " + this);
            _log.error("Already finished", ise);
            throw ise;
        }
        byte[] prevReadBlock = new byte[BLOCK_SIZE];
        System.arraycopy(prevReadEnd, prevReadEnd.length - BLOCK_SIZE, prevReadBlock, 0, BLOCK_SIZE);
        _curReadState = new NTCP1ReadState(prevReadBlock);
        _sessionKey = key;
        _clockSkew = clockSkew;
        _prevWriteEnd = prevWriteEnd;
        _establishedOn = _context.clock().now();
        NTCPConnection rv = _transport.inboundEstablished(this);
        _nextMetaTime = _establishedOn + (META_FREQUENCY / 2) + _context.random().nextInt(META_FREQUENCY);
        _nextInfoTime = _establishedOn + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
        _establishState = EstablishBase.VERIFIED;
        return rv;
    }

    /**
     * A positive number means our clock is ahead of theirs.
     *  @return seconds
     */
    public long getClockSkew() { return _clockSkew; }

    /** @return milliseconds */
    public long getUptime() { 
        if (!isEstablished())
            return getTimeSinceCreated();
        else
            return _context.clock().now() -_establishedOn; 
    }

    public int getMessagesSent() { return _messagesWritten.get(); }

    public int getMessagesReceived() { return _messagesRead.get(); }

    public int getOutboundQueueSize() {
            int queued = _outbound.size();
            synchronized(_currentOutbound) {
                queued += _currentOutbound.size();
            }
            return queued;
    }

    /** @since 0.9.36 */
    private boolean hasCurrentOutbound() {
        synchronized(_currentOutbound) {
            return ! _currentOutbound.isEmpty();
        }
    }
    
    /** @return milliseconds */
    public long getTimeSinceSend() { return _context.clock().now()-_lastSendTime; }

    /** @return milliseconds */
    public long getTimeSinceReceive() { return _context.clock().now()-_lastReceiveTime; }

    /** @return milliseconds */
    public long getTimeSinceCreated() { return _context.clock().now()-_created; }

    /**
     *  @return when this connection was created (not established)
     *  @since 0.9.20
     */
    public long getCreated() { return _created; }

    /**
     *  The NTCP2 version, for the console.
     *  For outbound, will not change.
     *  For inbound, defaults to 1, may change to 2 after establishment.
     *
     *  @return the version, 1 or 2
     *  @since 0.9.36
     */
    public int getVersion() { return _version; }

    /**
     *  Set version 2 from InboundEstablishState.
     *  Just for logging, so we know before finishInboundEstablish() is called.
     *
     *  @since 0.9.36
     */
    public void setVersion(int ver) { _version = ver; }

    /**
     * Sets to true.
     * @since 0.9.24
     */
    public void setMayDisconnect() { _mayDisconnect = true; }

    /**
     * @since 0.9.24
     */
    public boolean getMayDisconnect() { return _mayDisconnect; }

    /**
     *  workaround for EventPumper
     *  @since 0.8.12
     */
    void clearZeroRead() {
        _consecutiveZeroReads.set(0);
    }

    /**
     *  workaround for EventPumper
     *  @return value after incrementing
     *  @since 0.8.12
     */
    int gotZeroRead() {
        return _consecutiveZeroReads.incrementAndGet();
    }

    public boolean isClosed() { return _closed.get(); }

    public void close() { close(false); }

    public void close(boolean allowRequeue) {
        if (!_closed.compareAndSet(false,true)) {
            _log.logCloseLoop("NTCPConnection", this);
            return;
        }
        if (_log.shouldLog(Log.INFO)) {
            _log.info("Closing connection " + toString(), new Exception("cause"));
        }
        NTCPConnection toClose = locked_close(allowRequeue);
        if (toClose != null && toClose != this) {
            // won't happen as of 0.9.37
            if (_log.shouldLog(Log.WARN))
                _log.warn("Multiple connections on remove, closing " + toClose + " (already closed " + this + ")");
            _context.statManager().addRateData("ntcp.multipleCloseOnRemove", toClose.getUptime());
            toClose.close();
        }
    }
    
    /**
     *  Close and release EstablishState resources.
     *  @param e may be null
     *  @since 0.9.16
     */
    void closeOnTimeout(String cause, Exception e) {
        EstablishState es = _establishState;
        close();
        es.close(cause, e);
    }

    /**
     * @return usually this, but could be a second connection with the same peer...
     *         only this or null as of 0.9.37
     */
    private synchronized NTCPConnection locked_close(boolean allowRequeue) {
        if (_chan != null) try { _chan.close(); } catch (IOException ioe) { }
        if (_conKey != null) _conKey.cancel();
        _establishState = EstablishBase.FAILED;
        NTCPConnection old = _transport.removeCon(this);
        _transport.getReader().connectionClosed(this);
        _transport.getWriter().connectionClosed(this);

        for (FIFOBandwidthLimiter.Request req :_bwInRequests) {
            req.abort();
            // we would like to return read ByteBuffers via EventPumper.releaseBuf(),
            // but we can't risk releasing it twice
        }
        _bwInRequests.clear();
        for (FIFOBandwidthLimiter.Request req :_bwOutRequests) {
            req.abort();
        }
        _bwOutRequests.clear();

        _writeBufs.clear();
        ByteBuffer bb;
        while ((bb = _readBufs.poll()) != null) {
            EventPumper.releaseBuf(bb);
        }

        List<OutNetMessage> pending = new ArrayList<OutNetMessage>();
        //_outbound.drainAllTo(pending);
        _outbound.drainTo(pending);
        synchronized(_currentOutbound) {
            if (!_currentOutbound.isEmpty())
                pending.addAll(_currentOutbound);
            _currentOutbound.clear();
        }
        for (OutNetMessage msg : pending) {
            _transport.afterSend(msg, false, allowRequeue, msg.getLifetime());
        }
        // zero out everything we can
        if (_curReadState != null) {
            _curReadState.destroy();
            _curReadState = null;
        }
        if (_sender != null) {
            _sender.destroy();
            _sender = null;
        }
        _sendSipk1 = 0;
        _sendSipk2 = 0;
        if (_sendSipIV != null) {
            Arrays.fill(_sendSipIV, (byte) 0);
            _sendSipIV = null;
        }
        return old;
    }
    
    /**
     * toss the message onto the connection's send queue
     */
    public void send(OutNetMessage msg) {
        if (!_outbound.offer(msg)) {
            if (_log.shouldWarn())
                _log.warn("outbound queue full on " + this + ", dropping message " + msg);
            _transport.afterSend(msg, false, false, msg.getLifetime());
            return;
        }
        if (isEstablished() && !hasCurrentOutbound())
            _transport.getWriter().wantsWrite(this, "enqueued");
    }

    public boolean isBacklogged() { return _outbound.isBacklogged(); }
     
    public boolean tooBacklogged() {
        // perhaps we could take into account the size of the queued messages too, our
        // current transmission rate, and how much time is left before the new message's expiration?
        // ok, maybe later...
        if (getUptime() < 10*1000) // allow some slack just after establishment
            return false;
        if (_outbound.isBacklogged()) { // bloody arbitrary.  well, its half the average message lifetime...
            int size = _outbound.size();
            if (_log.shouldLog(Log.WARN)) {
                int writeBufs = _writeBufs.size();
                boolean currentOutboundSet;
                long seq;
                synchronized(_currentOutbound) {
                    currentOutboundSet = !_currentOutbound.isEmpty();
                    seq = currentOutboundSet ? _currentOutbound.get(0).getSeqNum() : -1;
                }
                try {
                    _log.warn("Too backlogged: size is " + size 
                              + ", wantsWrite? " + (0 != (_conKey.interestOps()&SelectionKey.OP_WRITE))
                              + ", currentOut set? " + currentOutboundSet
                              + ", id: " + seq
                              + ", writeBufs: " + writeBufs + " on " + toString());
                } catch (RuntimeException e) {}  // java.nio.channels.CancelledKeyException
            }
            return true;
        } else {
            return false;
        }
    }
    
    /**
     *  Inject a DatabaseStoreMessage with our RouterInfo. NTCP 1 or 2.
     *
     *  Externally, this is only called by NTCPTransport for outbound cons,
     *  before the con is established, but we know what version it is.
     *
     *  Internally, may be called for outbound or inbound, but only after the
     *  con is established, so we know what the version is.
     */
    void enqueueInfoMessage() {
        if (_version == 1) {
            enqueueInfoMessageNTCP1();
            // may change to 2 for inbound
        } else if (_isInbound) {
            // TODO or if outbound and it's not right at the beginning
            // TODO flood
            sendOurRouterInfo(false);
        }
        // don't need to send for NTCP 2 outbound, it's in msg 3
    }
    
    /**
     *  Inject a DatabaseStoreMessage with our RouterInfo. NTCP 1 only.
     */
    private void enqueueInfoMessageNTCP1() {
        int priority = INFO_PRIORITY;
        if (_log.shouldDebug())
            _log.debug("SENDING INFO message pri. " + priority + ": " + toString());
        DatabaseStoreMessage dsm = new DatabaseStoreMessage(_context);
        dsm.setEntry(_context.router().getRouterInfo());
        // We are injecting directly, so we can use a null target.
        OutNetMessage infoMsg = new OutNetMessage(_context, dsm, _context.clock().now()+10*1000, priority, null);
        infoMsg.beginSend();
        send(infoMsg);
    }

    /** 
     * We are Alice. NTCP1 only.
     *
     * Caller MUST call recvEncryptedI2NP() after, for any remaining bytes in receive buffer
     *
     * @param clockSkew OUR clock minus BOB's clock in seconds (may be negative, obviously, but |val| should
     *                  be under 1 minute)
     * @param prevWriteEnd exactly 16 bytes, not copied, do not corrupt
     * @param prevReadEnd 16 or more bytes, last 16 bytes copied
     */
    synchronized void finishOutboundEstablishment(SessionKey key, long clockSkew, byte prevWriteEnd[], byte prevReadEnd[]) {
        if (_establishState == EstablishBase.VERIFIED) {
            IllegalStateException ise = new IllegalStateException("Already finished on " + this);
            _log.error("Already finished", ise);
            throw ise;
        }
        byte[] prevReadBlock = new byte[BLOCK_SIZE];
        System.arraycopy(prevReadEnd, prevReadEnd.length - BLOCK_SIZE, prevReadBlock, 0, BLOCK_SIZE);
        _curReadState = new NTCP1ReadState(prevReadBlock);
        _sessionKey = key;
        _clockSkew = clockSkew;
        _prevWriteEnd = prevWriteEnd;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("outbound established (key=" + key + " skew=" + clockSkew +
                       " prevWriteEnd: " + Base64.encode(prevWriteEnd) + " prevReadBlock: " + Base64.encode(prevReadBlock));

        _establishedOn = _context.clock().now();
        _establishState = EstablishBase.VERIFIED;
        _transport.markReachable(getRemotePeer().calculateHash(), false);
        _nextMetaTime = _establishedOn + (META_FREQUENCY / 2) + _context.random().nextInt(META_FREQUENCY);
        _nextInfoTime = _establishedOn + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
        if (!_outbound.isEmpty())
            _transport.getWriter().wantsWrite(this, "outbound established");
    }
    
    /**
     * Prepare the next I2NP message for transmission.  This should be run from
     * the Writer thread pool. NTCP 1 or 2.
     * 
     * This is the entry point as called from Writer.Runner.run()
     * 
     * @param prep an instance of PrepBuffer to use as scratch space
     *
     */
    synchronized void prepareNextWrite(PrepBuffer prep) {
        if (_closed.get())
            return;
        // Must be established or else session key is null and we can't encrypt
        // This is normal for OB conns but can happen rarely for IB also.
        // wantsWrite() is called at end of OB establishment, and
        // enqueueInfoMessage() is called at end of IB establishment.
        if (!isEstablished()) {
            return;
        }
        if (_version == 1)
            prepareNextWriteFast(prep);
        else
            prepareNextWriteNTCP2(prep);
    }

    /**
     * Prepare the next I2NP message for transmission.  This should be run from
     * the Writer thread pool. NTCP 1 only.
     *
     * Caller must synchronize.
     * @param buf a PrepBuffer to use as scratch space
     *
     */
    private void prepareNextWriteFast(PrepBuffer buf) {
        long now = _context.clock().now();
        if (_nextMetaTime <= now) {
            sendMeta();
            _nextMetaTime = now + (META_FREQUENCY / 2) + _context.random().nextInt(META_FREQUENCY / 2);
        }
      
        OutNetMessage msg;
        synchronized (_currentOutbound) {
            if (!_currentOutbound.isEmpty()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("attempt for multiple outbound messages with " + _currentOutbound.size() + " already waiting and " + _outbound.size() + " queued");
                return;
            }
            while (true) {
                msg = _outbound.poll();
                if (msg == null)
                    return;
                if (msg.getExpiration() >= now)
                    break;
                if (_log.shouldWarn())
                    _log.warn("dropping message expired on queue: " + msg + " on " + this);
                _transport.afterSend(msg, false, false, msg.getLifetime());
            }
            _currentOutbound.add(msg);
        }

        bufferedPrepare(msg, buf);
        _context.aes().encrypt(buf.unencrypted, 0, buf.encrypted, 0, _sessionKey, _prevWriteEnd, 0, buf.unencryptedLength);
        System.arraycopy(buf.encrypted, buf.encrypted.length-16, _prevWriteEnd, 0, _prevWriteEnd.length);
        _transport.getPumper().wantsWrite(this, buf.encrypted);

        // for every 6-12 hours that we are connected to a peer, send them
	// our updated netDb info (they may not accept it and instead query
	// the floodfill netDb servers, but they may...)
        if (_nextInfoTime <= now) {
            // perhaps this should check to see if we are bw throttled, etc?
            enqueueInfoMessage();
            _nextInfoTime = now + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
        }
    }
    
    /**
     * Serialize the message/checksum/padding/etc for transmission, but leave off
     * the encryption.  This should be called from a Writer thread
     * 
     * @param msg message to send
     * @param buf PrepBuffer to use as scratch space
     */
    private void bufferedPrepare(OutNetMessage msg, PrepBuffer buf) {
        I2NPMessage m = msg.getMessage();
        // 2 offset for size
        int sz = m.toByteArray(buf.unencrypted, 2) - 2;
        int min = 2 + sz + 4;
        int rem = min % 16;
        int padding = 0;
        if (rem > 0)
            padding = 16 - rem;
        buf.unencryptedLength = min+padding;
        DataHelper.toLong(buf.unencrypted, 0, 2, sz);
        if (padding > 0) {
            _context.random().nextBytes(buf.unencrypted, 2+sz, padding);
        }
        
        buf.crc.update(buf.unencrypted, 0, buf.unencryptedLength-4);
        
        long val = buf.crc.getValue();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Outbound message " + _messagesWritten + " has crc " + val
                       + " sz=" +sz + " rem=" + rem + " padding=" + padding);
        
        DataHelper.toLong(buf.unencrypted, buf.unencryptedLength-4, 4, val);
        // TODO object churn
        // 1) store the length only
        // 2) in prepareNextWriteFast(), pull a byte buffer off a queue and encrypt to that
        // 3) change EventPumper.wantsWrite() to take a ByteBuffer arg
        // 4) in EventPumper.processWrite(), release the byte buffer
        buf.encrypted = new byte[buf.unencryptedLength];
    }

    static class PrepBuffer {
        final byte unencrypted[];
        int unencryptedLength;
        final Adler32 crc;
        byte encrypted[];
        
        public PrepBuffer() {
            unencrypted = new byte[BUFFER_SIZE];
            crc = new Adler32();
        }

        public void init() {
            unencryptedLength = 0;
            encrypted = null;
            crc.reset();
        }
    }

    /**
     * Prepare the next I2NP message for transmission.  This should be run from
     * the Writer thread pool.
     *
     * Caller must synchronize.
     *
     * @param buf we use buf.enencrypted only
     * @since 0.9.36
     */
    private void prepareNextWriteNTCP2(PrepBuffer buf) {
        int size = OutboundNTCP2State.MAC_SIZE;
        List<Block> blocks = new ArrayList<Block>(4);
        long now = _context.clock().now();
        synchronized (_currentOutbound) {
            if (!_currentOutbound.isEmpty()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("attempt for multiple outbound messages with " + _currentOutbound.size() + " already waiting and " + _outbound.size() + " queued");
                return;
            }
            OutNetMessage msg;
            while (true) {
                msg = _outbound.poll();
                if (msg == null)
                    return;
                if (msg.getExpiration() >= now)
                    break;
                if (_log.shouldWarn())
                    _log.warn("dropping message expired on queue: " + msg + " on " + this);
                _transport.afterSend(msg, false, false, msg.getLifetime());
            }
            _currentOutbound.add(msg);
            I2NPMessage m = msg.getMessage();
            Block block = new NTCP2Payload.I2NPBlock(m);
            blocks.add(block);
            size += block.getTotalLength();
            // now add more (maybe)
            if (size < NTCP2_PREFERRED_PAYLOAD_MAX) {
                // keep adding as long as we will be under 5 KB
                while (true) {
                    msg = _outbound.peek();
                    if (msg == null)
                        break;
                    m = msg.getMessage();
                    int msz = m.getMessageSize() - 7;
                    if (size + msz > NTCP2_PREFERRED_PAYLOAD_MAX)
                        break;
                    OutNetMessage msg2 = _outbound.poll();
                    if (msg2 == null)
                        break;
                    if (msg2 != msg) {
                        // if it wasn't the one we sized, put it back
                        _outbound.offer(msg2);
                        break;
                    }
                    if (msg.getExpiration() >= now) {
                        block = new NTCP2Payload.I2NPBlock(m);
                        blocks.add(block);
                        size += NTCP2Payload.BLOCK_HEADER_SIZE + msz;
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("dropping message expired on queue: " + msg + " on " + this);
                        _transport.afterSend(msg, false, false, msg.getLifetime());
                    }
                }
            }
        }
        if (_nextMetaTime <= now && size + (NTCP2Payload.BLOCK_HEADER_SIZE + 4) <= BUFFER_SIZE) {
            Block block = new NTCP2Payload.DateTimeBlock(_context);
            blocks.add(block);
            size += block.getTotalLength();
            _nextMetaTime = now + (META_FREQUENCY / 2) + _context.random().nextInt(META_FREQUENCY / 2);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending NTCP2 datetime block");
        }
        // 1024 is an estimate, do final check below
        if (_nextInfoTime <= now && size + 1024 <= BUFFER_SIZE) {
            RouterInfo ri = _context.router().getRouterInfo();
            Block block = new NTCP2Payload.RIBlock(ri, false);
            int sz = block.getTotalLength();
            if (size + sz <= BUFFER_SIZE) {
                blocks.add(block);
                size += sz;
                _nextInfoTime = now + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
                if (_log.shouldDebug())
                    _log.debug("SENDING NTCP2 RI block");
            } // else wait until next time
        }
        int availForPad = BUFFER_SIZE - (size + NTCP2Payload.BLOCK_HEADER_SIZE);
        if (availForPad > 0) {
            int padlen = getPaddingSize(size, availForPad);
            // all zeros is fine here
            //Block block = new NTCP2Payload.PaddingBlock(_context, padlen);
            Block block = new NTCP2Payload.PaddingBlock(padlen);
            blocks.add(block);
            size += block.getTotalLength();
        }
        byte[] tmp = size <= BUFFER_SIZE ? buf.unencrypted : new byte[size];
        sendNTCP2(tmp, blocks);
    }

    /**
     *  NTCP2 only
     *
     *  @param dataSize the total size of the data we are sending
     *  @param availForPad the available size for padding, not including padding block header,
     *                     must be greater than zero
     *  @return min 0 max availForPad
     *  @since 0.9.36
     */
    private int getPaddingSize(int dataSize, int availForPad) {
        // since we're calculating with percentages, get at least a
        // 0-16 range with the default 0% min 6% max,
        // even for small dataSize.
        if (dataSize < 256)
            dataSize = 256;
        // what we want to send, calculated in proportion to data size
        int minSend = (int) (dataSize * _paddingConfig.getSendMin());
        int maxSend = (int) (dataSize * _paddingConfig.getSendMax());
        // the absolute min and max we can send
        int min = Math.min(minSend, availForPad);
        int max = Math.min(maxSend, availForPad);
        int range = max - min;
        if (range < MIN_PADDING_RANGE) {
            // reduce min to enforce minimum range if possible
            min = Math.max(0, min - (MIN_PADDING_RANGE - range));
            range = max - min;
        } else if (range > MAX_PADDING_RANGE) {
            // Don't send too much, no matter what the config says
            range = MAX_PADDING_RANGE;
        }
        int padlen = min;
        if (range > 0)
            padlen += _context.random().nextInt(1 + range);
        if (_log.shouldDebug())
            _log.debug("Padding params:" +
                      " data size: " + dataSize +
                      " avail: " + availForPad +
                      " minSend: " + minSend +
                      " maxSend: " + maxSend +
                      " min: " + min +
                      " max: " + max +
                      " range: " + range +
                      " padlen: " + padlen);
        return padlen;
    }

    /**
     *  NTCP2 only
     *
     *  @since 0.9.36
     */
    private void sendOurRouterInfo(boolean shouldFlood) {
        RouterInfo ri = _context.router().getRouterInfo();
        if (ri == null)
            return;
        sendRouterInfo(ri, shouldFlood);
    }

    /**
     *  NTCP2 only
     *
     *  @since 0.9.36
     */
    private void sendRouterInfo(RouterInfo ri, boolean shouldFlood) {
        // no synch needed, sendNTCP2() is synched
        if (_log.shouldDebug())
            _log.debug("Sending router info for: " + ri.getHash() + " flood? " + shouldFlood);
        List<Block> blocks = new ArrayList<Block>(2);
        Block block = new NTCP2Payload.RIBlock(ri, shouldFlood);
        int size = block.getTotalLength();
        if (size + NTCP2Payload.BLOCK_HEADER_SIZE > BUFFER_SIZE) {
            if (_log.shouldWarn())
                _log.warn("RI too big: " + ri);
            return;
        }
        blocks.add(block);
        int availForPad = BUFFER_SIZE - (size + NTCP2Payload.BLOCK_HEADER_SIZE);
        if (availForPad > 0) {
            int padlen = getPaddingSize(size, availForPad);
            // all zeros is fine here
            //block = new NTCP2Payload.PaddingBlock(_context, padlen);
            block = new NTCP2Payload.PaddingBlock(padlen);
            blocks.add(block);
        }
        // use a "read buf" for the temp array
        ByteArray dataBuf = acquireReadBuf();
        sendNTCP2(dataBuf.getData(), blocks);
        releaseReadBuf(dataBuf);
    }

    /**
     *  NTCP 1 or 2.
     *  For NTCP1, sends termination and then closes the connection after a brief delay.
     *  For NTCP2, simply closes the connection immediately.
     *
     *  @since 0.9.36
     */
    void sendTerminationAndClose() {
        ReadState rs = null;
        synchronized (this) {
            if (_version == 2 && isEstablished())
                rs = _curReadState;
        }
        if (rs != null)
            sendTermination(REASON_TIMEOUT, rs.getFramesReceived());
        else
            close();
    }

    /**
     *  NTCP2 only. Also closes the connection after a brief delay.
     *
     *  @since 0.9.36
     */
    private void sendTermination(int reason, int validFramesRcvd) {
        // TODO add param to clear queues?
        // no synch needed, sendNTCP2() is synched
        if (_log.shouldInfo())
            _log.info("Sending termination, reason: " + reason + ", vaild frames rcvd: " + validFramesRcvd + " on " + this);
        List<Block> blocks = new ArrayList<Block>(2);
        Block block = new NTCP2Payload.TerminationBlock(reason, validFramesRcvd);
        int plen = block.getTotalLength();
        blocks.add(block);
        int padlen = getPaddingSize(plen, PADDING_MAX);
        if (padlen > 0) {
            // all zeros is fine here
            //block = new NTCP2Payload.PaddingBlock(_context, padlen);
            block = new NTCP2Payload.PaddingBlock(padlen);
            blocks.add(block);
        }
        // use a "read buf" for the temp array
        ByteArray dataBuf = acquireReadBuf();
        synchronized(this) {
            if (_sender != null) {
                sendNTCP2(dataBuf.getData(), blocks);
                _sender.destroy();
                // this "plugs" the NTCP2 sender, so sendNTCP2()
                // won't send any more after the termination.
                _sender = null;
                new DelayedCloser();
            }
        }
        releaseReadBuf(dataBuf);
    }

    /**
     *  This constructs the payload from the blocks, using the
     *  tmp byte array, then encrypts the payload and
     *  passes it to the pumper for writing.
     *
     *  @param tmp to be used for output of NTCP2Payload.writePayload(),
     *         must have room for block output. May be released immediately on return.
     *  @since 0.9.36
     */
    private synchronized void sendNTCP2(byte[] tmp, List<Block> blocks) {
        if (_sender == null) {
            if (_log.shouldInfo())
                _log.info("sender gone", new Exception());
            return;
        }
        int payloadlen = NTCP2Payload.writePayload(tmp, 0, blocks);
        int framelen = payloadlen + OutboundNTCP2State.MAC_SIZE;
        // TODO use a buffer
        byte[] enc = new byte[2 + framelen];
        try {
            _sender.encryptWithAd(null, tmp, 0, enc, 2, payloadlen);
        } catch (GeneralSecurityException gse) {
            // TODO anything else?
            _log.error("data enc", gse);
            return;
        }

        // siphash ^ len
        long sipIV = SipHashInline.hash24(_sendSipk1, _sendSipk2, _sendSipIV);
        enc[0] = (byte) ((framelen >> 8) ^ (sipIV >> 8));
        enc[1] = (byte) (framelen ^ sipIV);
        if (_log.shouldDebug()) {
            StringBuilder buf = new StringBuilder(256);
            buf.append("Sending ").append(blocks.size())
               .append(" blocks in ").append(framelen)
               .append(" byte NTCP2 frame:");
            for (int i = 0; i < blocks.size(); i++) {
                buf.append("\n    ").append(i).append(": ").append(blocks.get(i).toString());
            }
            _log.debug(buf.toString());
        }
        _transport.getPumper().wantsWrite(this, enc);
        toLong8LE(_sendSipIV, 0, sipIV);
    }
    
    /** 
     * async callback after the outbound connection was completed (this should NOT block, 
     * as it occurs in the selector thread)
     */
    void outboundConnected() {
        _conKey.interestOps(_conKey.interestOps() | SelectionKey.OP_READ);
        // schedule up the beginning of our handshaking by calling prepareNextWrite on the
        // writer thread pool
        _transport.getWriter().wantsWrite(this, "outbound connected");
    }

    /**
     *  The FifoBandwidthLimiter.CompleteListener callback.
     *  Does the delayed read.
     */
    private class InboundListener implements FIFOBandwidthLimiter.CompleteListener {
        public void complete(FIFOBandwidthLimiter.Request req) {
            removeIBRequest(req);
            ByteBuffer buf = (ByteBuffer)req.attachment();
            if (_closed.get()) {
                EventPumper.releaseBuf(buf);
                return;
            }
            _context.statManager().addRateData("ntcp.throttledReadComplete", (_context.clock().now()-req.getRequestTime()));
            recv(buf);
            // our reads used to be bw throttled (during which time we were no
            // longer interested in reading from the network), but we aren't
            // throttled anymore, so we should resume being interested in reading
            _transport.getPumper().wantsRead(NTCPConnection.this);
        }
    }

    /**
     *  The FifoBandwidthLimiter.CompleteListener callback.
     *  Does the delayed write.
     */
    private class OutboundListener implements FIFOBandwidthLimiter.CompleteListener {
        public void complete(FIFOBandwidthLimiter.Request req) {
            removeOBRequest(req);
            ByteBuffer buf = (ByteBuffer)req.attachment();
            if (!_closed.get()) {
                _context.statManager().addRateData("ntcp.throttledWriteComplete", (_context.clock().now()-req.getRequestTime()));
                write(buf);
            }
        }
    }

    private void removeIBRequest(FIFOBandwidthLimiter.Request req) {
        _bwInRequests.remove(req);
    }

    private void addIBRequest(FIFOBandwidthLimiter.Request req) {
        _bwInRequests.add(req);
    }
    
    private void removeOBRequest(FIFOBandwidthLimiter.Request req) {
        _bwOutRequests.remove(req);
    }

    private void addOBRequest(FIFOBandwidthLimiter.Request req) {
        _bwOutRequests.add(req);
    }
    
    /**
     * We have read the data in the buffer, but we can't process it locally yet,
     * because we're choked by the bandwidth limiter.  Cache the contents of
     * the buffer (not copy) and register ourselves to be notified when the 
     * contents have been fully allocated
     */
    void queuedRecv(ByteBuffer buf, FIFOBandwidthLimiter.Request req) {
        req.attach(buf);
        req.setCompleteListener(_inboundListener);
        addIBRequest(req);
    }

    /** ditto for writes */
    void queuedWrite(ByteBuffer buf, FIFOBandwidthLimiter.Request req) {
        req.attach(buf);
        req.setCompleteListener(_outboundListener);
        addOBRequest(req);
    }
    
    /**
     * The contents of the buffer have been read and can be processed asap.
     * This should not block, and the NTCP connection now owns the buffer
     * to do with as it pleases BUT it should eventually copy out the data
     * and call EventPumper.releaseBuf().
     */
    void recv(ByteBuffer buf) {
        if (isClosed()) {
            if (_log.shouldWarn())
                _log.warn("recv() on closed con");
            return;
        }
        synchronized(this) {
            _bytesReceived += buf.remaining();
            updateStats();
        }
        _readBufs.offer(buf);
        _transport.getReader().wantsRead(this);
    }

    /**
     * The contents of the buffer have been encrypted / padded / etc and have
     * been fully allocated for the bandwidth limiter.
     */
    void write(ByteBuffer buf) {
        _writeBufs.offer(buf);
        _transport.getPumper().wantsWrite(this);
    }
    
    /** @return null if none available */
    ByteBuffer getNextReadBuf() {
        return _readBufs.poll();
    }

    /**
     * Replaces getWriteBufCount()
     * @since 0.8.12
     */
    boolean isWriteBufEmpty() {
        return _writeBufs.isEmpty();
    }

    /** @return null if none available */
    ByteBuffer getNextWriteBuf() {
        return _writeBufs.peek(); // not remove!  we removeWriteBuf afterwards
    }
    
    /**
     *  Remove the buffer, which _should_ be the one at the head of _writeBufs
     */
    void removeWriteBuf(ByteBuffer buf) {
        // never clear OutNetMessages during establish phase
        boolean clearMessage = isEstablished();
        synchronized(this) {
            _bytesSent += buf.capacity();
            if (_sendingMeta && (buf.capacity() == META_SIZE)) {
                _sendingMeta = false;
                clearMessage = false;
            }
            updateStats();
        }
        _writeBufs.remove(buf);
        if (clearMessage) {
            List<OutNetMessage> msgs = null;
            // see synchronization comments in prepareNextWriteFast()
            synchronized (_currentOutbound) {
                if (!_currentOutbound.isEmpty()) {
                    msgs = new ArrayList<OutNetMessage>(_currentOutbound);
                    _currentOutbound.clear();
                }
            }
            // push through the bw limiter to reach _writeBufs
            if (!_outbound.isEmpty())
                _transport.getWriter().wantsWrite(this, "write completed");
            if (msgs != null) {
                _lastSendTime = _context.clock().now();
                // stats once is fine for all of them
                _context.statManager().addRateData("ntcp.sendTime", msgs.get(0).getSendTime());
                for (OutNetMessage msg : msgs) {
                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug("I2NP message " + _messagesWritten + "/" + msg.getMessageId() + " sent after " 
                                  + msg.getSendTime() + "/"
                                  + msg.getLifetime()
                                  + " with " + buf.capacity() + " bytes (uid=" + System.identityHashCode(msg)+" on " + toString() + ")");
                    }
                    _transport.sendComplete(msg);
                }
                _messagesWritten.addAndGet(msgs.size());
            }
        } else {
            // push through the bw limiter to reach _writeBufs
            if (!_outbound.isEmpty())
                _transport.getWriter().wantsWrite(this, "write completed");
            if (_log.shouldDebug())
                _log.debug("I2NP meta message sent completely");
            // need to increment as EventPumper will close conn if not completed
            _messagesWritten.incrementAndGet();
        }
    }
        
    private long _bytesReceived;
    private long _bytesSent;
    /** _bytesReceived when we last updated the rate */
    private long _lastBytesReceived;
    /** _bytesSent when we last updated the rate */
    private long _lastBytesSent;
    private float _sendBps;
    private float _recvBps;
    
    public synchronized float getSendRate() { return _sendBps; }
    public synchronized float getRecvRate() { return _recvBps; }
    
    /**
     *  Stats only for console
     */
    private synchronized void updateStats() {
        long now = _context.clock().now();
        long time = now - _lastRateUpdated;
        // If enough time has passed...
        // Perhaps should synchronize, but if so do the time check before synching...
        // only for console so don't bother....
        if (time >= STAT_UPDATE_TIME_MS) {
            long totS = _bytesSent;
            long totR = _bytesReceived;
            long sent = totS - _lastBytesSent; // How much we sent meanwhile
            long recv = totR - _lastBytesReceived; // How much we received meanwhile
            _lastBytesSent = totS;
            _lastBytesReceived = totR;
            _lastRateUpdated = now;

            _sendBps = (0.9f)*_sendBps + (0.1f)*(sent*1000f)/time;
            _recvBps = (0.9f)*_recvBps + (0.1f)*((float)recv*1000)/time;
        }
    }
        
    /**
     * Connection must be established!
     *
     * The contents of the buffer include some fraction of one or more
     * encrypted and encoded I2NP messages.  individual i2np messages are
     * encoded as "sizeof(data)+data+pad+crc", and those are encrypted
     * with the session key and the last 16 bytes of the previous encrypted
     * i2np message.
     *
     * The NTCP connection now owns the buffer
     * BUT it must copy out the data
     * as reader will call EventPumper.releaseBuf().
     *
     * This is the entry point as called from Reader.processRead()
     */
    synchronized void recvEncryptedI2NP(ByteBuffer buf) {
        if (_curReadState == null)
            throw new IllegalStateException("not established");
        _curReadState.receive(buf);
    }
    
   /* 
    * One special case is a metadata message where the sizeof(data) is 0.  In
    * that case, the unencrypted message is encoded as:
    * 
    * <pre>
    *  +-------+-------+-------+-------+-------+-------+-------+-------+
    *  |       0       |      timestamp in seconds     | uninterpreted             
    *  +-------+-------+-------+-------+-------+-------+-------+-------+
    *          uninterpreted           | adler checksum of sz+data+pad |
    *  +-------+-------+-------+-------+-------+-------+-------+-------+
    * </pre>
    * 
    *  Caller must synch
    * 
    *  @param unencrypted 16 bytes starting at off
    *  @param off the offset
    */
    private void readMeta(byte unencrypted[], int off) {
        Adler32 crc = new Adler32();
        crc.update(unencrypted, off, META_SIZE - 4);
        long expected = crc.getValue();
        long read = DataHelper.fromLong(unencrypted, off + META_SIZE - 4, 4);
        if (read != expected) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("I2NP metadata message had a bad CRC value");
            _context.statManager().addRateData("ntcp.corruptMetaCRC", 1);
            close();
            return;
        }
        long ts = DataHelper.fromLong(unencrypted, off + 2, 4);
        receiveTimestamp(ts);
    }

    /**
     *  Handle a received timestamp, NTCP 1 or 2.
     *  Caller must synch
     *
     *  @param ts his timestamp in seconds, NOT ms
     *  @since 0.9.36 pulled out of readMeta() above
     */
    private void receiveTimestamp(long ts) {
        long ourTs = (_context.clock().now() + 500) / 1000;
        long newSkew = (ourTs - ts);
        if (Math.abs(newSkew*1000) > Router.CLOCK_FUDGE_FACTOR) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Peer's skew jumped too far (from " + _clockSkew + " s to " + newSkew + " s): " + toString());
            _context.statManager().addRateData("ntcp.corruptSkew", newSkew);
            close();
            return;
        }
        _context.statManager().addRateData("ntcp.receiveMeta", newSkew);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Received NTCP metadata, old skew of " + _clockSkew + " s, new skew of " + newSkew + "s.");
        // FIXME does not account for RTT
        _clockSkew = newSkew;
    }

    /**
     * One special case is a metadata message where the sizeof(data) is 0.  In
     * that case, the unencrypted message is encoded as:
     *
     *<pre>
     *  +-------+-------+-------+-------+-------+-------+-------+-------+
     *  |       0       |      timestamp in seconds     | uninterpreted             
     *  +-------+-------+-------+-------+-------+-------+-------+-------+
     *          uninterpreted           | adler checksum of sz+data+pad |
     *  +-------+-------+-------+-------+-------+-------+-------+-------+
     *</pre>
     *
     * Caller must synchronize.
     */
    private void sendMeta() {
        byte[] data = new byte[META_SIZE];
        DataHelper.toLong(data, 0, 2, 0);
        DataHelper.toLong(data, 2, 4, (_context.clock().now() + 500) / 1000);
        _context.random().nextBytes(data, 6, 6);
        Adler32 crc = new Adler32();
        crc.update(data, 0, META_SIZE - 4);
        DataHelper.toLong(data, META_SIZE - 4, 4, crc.getValue());
        _context.aes().encrypt(data, 0, data, 0, _sessionKey, _prevWriteEnd, 0, META_SIZE);
        System.arraycopy(data, META_SIZE - 16, _prevWriteEnd, 0, _prevWriteEnd.length);
        // perhaps this should skip the bw limiter to reduce clock skew issues?
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending NTCP metadata");
        _sendingMeta = true;
        _transport.getPumper().wantsWrite(this, data);
    }
    
    private static final int MAX_HANDLERS = 4;

    /**
     *  FIXME static queue mixes handlers from different contexts in multirouter JVM
     */
    private final static LinkedBlockingQueue<I2NPMessageHandler> _i2npHandlers = new LinkedBlockingQueue<I2NPMessageHandler>(MAX_HANDLERS);

    private final static I2NPMessageHandler acquireHandler(RouterContext ctx) {
        I2NPMessageHandler rv = _i2npHandlers.poll();
        if (rv == null)
            rv = new I2NPMessageHandler(ctx);
        return rv;
    }

    private static void releaseHandler(I2NPMessageHandler handler) {
        _i2npHandlers.offer(handler);
    }
    
    private static ByteArray acquireReadBuf() {
        return _dataReadBufs.acquire();
    }

    private static void releaseReadBuf(ByteArray buf) {
        _dataReadBufs.release(buf, false);
    }

    /**
     *  Call at transport shutdown
     *  @since 0.8.8
     */
    static void releaseResources() {
        _i2npHandlers.clear();
    }

    private interface ReadState {
        public void receive(ByteBuffer buf);
        public void destroy();
        public int getFramesReceived();
    }

    /**
     * Read the unencrypted message (16 bytes at a time).
     * verify the checksum, and pass it on to
     * an I2NPMessageHandler.  The unencrypted message is encoded as follows:
     *
     *<pre>
     *  +-------+-------+--//--+---//----+-------+-------+-------+-------+
     *  | sizeof(data)  | data | padding | adler checksum of sz+data+pad |
     *  +-------+-------+--//--+---//----+-------+-------+-------+-------+
     *</pre>
     *
     * sizeof(data)+data+pad+crc.
     *
     * perhaps to reduce the per-con memory footprint, we can acquire/release
     * the ReadState._data and ._bais when _size is > 0, so there are only
     * J 16KB buffers for the cons actually transmitting, instead of one per
     * con (including idle ones)
     *
     * Call all methods from synchronized parent method.
     *
     */
    private class NTCP1ReadState implements ReadState {
        private int _size;
        private ByteArray _dataBuf;
        private int _nextWrite;
        private final Adler32 _crc;
        private long _stateBegin;
        private int _blocks;
        /** encrypted block of the current I2NP message being read */
        private byte _curReadBlock[];
        /** next byte to which data should be placed in the _curReadBlock */
        private int _curReadBlockIndex;
        private final byte _decryptBlockBuf[];
        /** last AES block of the encrypted I2NP message (to serve as the next block's IV) */
        private byte _prevReadBlock[];

        /**
         *  @param prevReadBlock 16 bytes AES IV
         */
        public NTCP1ReadState(byte[] prevReadBlock) {
            _crc = new Adler32();
            _prevReadBlock = prevReadBlock;
            _curReadBlock = new byte[BLOCK_SIZE];
            _decryptBlockBuf = new byte[BLOCK_SIZE];
            init();
        }

        private void init() {
            _size = -1;
            _nextWrite = 0;
            _stateBegin = -1;
            _blocks = -1;
            _crc.reset();
            if (_dataBuf != null)
                releaseReadBuf(_dataBuf);
            _dataBuf = null;
            _curReadBlockIndex = 0;
        }

        /** @since 0.9.36 */
        public void destroy() {
            if (_dataBuf != null) {
                releaseReadBuf(_dataBuf);
                _dataBuf = null;
            }
            // TODO zero things out
        }
        
        /**
         * Connection must be established!
         *
         * The contents of the buffer include some fraction of one or more
         * encrypted and encoded I2NP messages.  individual i2np messages are
         * encoded as "sizeof(data)+data+pad+crc", and those are encrypted
         * with the session key and the last 16 bytes of the previous encrypted
         * i2np message.
         *
         * The NTCP connection now owns the buffer
         * BUT it must copy out the data
         * as reader will call EventPumper.releaseBuf().
         *
         * @since 0.9.36 moved from parent class
         */
        public void receive(ByteBuffer buf) {
            // hasArray() is false for direct buffers, at least on my system...
            if (_curReadBlockIndex == 0 && buf.hasArray()) {
                // fast way
                int tot = buf.remaining();
                if (tot >= 32 && tot % 16 == 0) {
                    recvEncryptedFast(buf);
                    return;
                }
            }

            while (buf.hasRemaining() && !_closed.get()) {
                int want = Math.min(buf.remaining(), BLOCK_SIZE - _curReadBlockIndex);
                if (want > 0) {
                    buf.get(_curReadBlock, _curReadBlockIndex, want);
                    _curReadBlockIndex += want;
                }
                if (_curReadBlockIndex >= BLOCK_SIZE) {
                    // cbc
                    _context.aes().decryptBlock(_curReadBlock, 0, _sessionKey, _decryptBlockBuf, 0);
                    xor16(_prevReadBlock, _decryptBlockBuf);
                    boolean ok = recvUnencryptedI2NP();
                    if (!ok) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Read buffer " + System.identityHashCode(buf) + " contained corrupt data, IV was: " + Base64.encode(_decryptBlockBuf));
                        _context.statManager().addRateData("ntcp.corruptDecryptedI2NP", 1);
                        return;
                    }
                    byte swap[] = _prevReadBlock;
                    _prevReadBlock = _curReadBlock;
                    _curReadBlock = swap;
                    _curReadBlockIndex = 0;
                }
            }
        }

        /**
         *  Decrypt directly out of the ByteBuffer instead of copying the bytes
         *  16 at a time to the _curReadBlock / _prevReadBlock flip buffers.
         *
         *  More efficient but can only be used if buf.hasArray == true AND
         *  _curReadBlockIndex must be 0 and buf.getRemaining() % 16 must be 0
         *  and buf.getRemaining() must be >= 16.
         *  All this is true for most incoming buffers.
         *  In theory this could be fixed up to handle the other cases too but that's hard.
         *  Caller must synchronize!
         *
         *  @since 0.8.12, moved from parent class in 0.9.36
         */
        private void recvEncryptedFast(ByteBuffer buf) {
            byte[] array = buf.array();
            int pos = buf.arrayOffset() + buf.position();
            int end = pos + buf.remaining();

            // Copy to _curReadBlock for next IV...
            System.arraycopy(array, end - BLOCK_SIZE, _curReadBlock, 0, BLOCK_SIZE);
            // call aes().decrypt() to decrypt all at once, in place
            // decrypt() will offload to the JVM/OS for larger sizes
            _context.aes().decrypt(array, pos, array, pos, _sessionKey, _prevReadBlock, buf.remaining());

            for ( ; pos < end; pos += BLOCK_SIZE) {
                boolean ok = receiveBlock(array, pos);
                if (!ok) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Read buffer " + System.identityHashCode(buf) + " contained corrupt data");
                    _context.statManager().addRateData("ntcp.corruptDecryptedI2NP", 1);
                    return;
                }
            }
            // ...and flip to _prevReadBlock for next time
            byte swap[] = _prevReadBlock;
            _prevReadBlock = _curReadBlock;
            _curReadBlock = swap;
        }
    
        /**
         *  Append the next 16 bytes of cleartext to the read state.
         *  _decryptBlockBuf contains another cleartext block of I2NP to parse.
         *  Caller must synchronize!
         *
         *  @return success
         *  @since 0.9.36 moved from parent class
         */
        private boolean recvUnencryptedI2NP() {
            return receiveBlock(_decryptBlockBuf, 0);
        }

        /**
         *  Caller must synchronize
         *  @param buf 16 bytes starting at off
         *  @param off offset
         *  @return success, only false on initial block with invalid size
         */
        private boolean receiveBlock(byte buf[], int off) {
            if (_size == -1) {
                return receiveInitial(buf, off);
            } else {
                receiveSubsequent(buf, off);
                return true;
            }
        }

        /**
         *  Caller must synchronize
         *
         *  @param buf 16 bytes starting at off
         *  @param off offset
         *  @return success
         */
        private boolean receiveInitial(byte buf[], int off) {
            _size = (int)DataHelper.fromLong(buf, off, 2);
            if (_size > BUFFER_SIZE) {
                // this is typically an AES decryption error, not actually a large I2NP message
                if (_log.shouldLog(Log.WARN))
                    _log.warn("I2NP message too big - size: " + _size + " Closing " + NTCPConnection.this.toString(), new Exception());
                _context.statManager().addRateData("ntcp.corruptTooLargeI2NP", _size);
                close();
                return false;
            }
            if (_size == 0) {
                readMeta(buf, off);
                init();
            } else {
                _stateBegin = _context.clock().now();
                _dataBuf = acquireReadBuf();
                System.arraycopy(buf, off + 2, _dataBuf.getData(), 0, BLOCK_SIZE - 2);
                _nextWrite += BLOCK_SIZE - 2;
                _crc.update(buf, off, BLOCK_SIZE);
                _blocks++;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("new I2NP message with size: " + _size + " for message " + _messagesRead);
            }
            return true;
        }

        /**
         *  Caller must synchronize
         *
         *  @param buf 16 bytes starting at off
         *  @param off offset
         */
        private void receiveSubsequent(byte buf[], int off) {
            _blocks++;
            int remaining = _size - _nextWrite;
            int blockUsed = Math.min(BLOCK_SIZE, remaining);
            if (remaining > 0) {
                System.arraycopy(buf, off, _dataBuf.getData(), _nextWrite, blockUsed);
                _nextWrite += blockUsed;
                remaining -= blockUsed;
            }
            if ( (remaining <= 0) && (BLOCK_SIZE - blockUsed < 4) ) {
                // we've received all the data but not the 4-byte checksum
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("crc wraparound required on block " + _blocks + " in message " + _messagesRead);
                _crc.update(buf, off, BLOCK_SIZE);
                return;
            } else if (remaining <= 0) {
                receiveLastBlock(buf, off);
            } else {
                _crc.update(buf, off, BLOCK_SIZE);
            }
        }

        /**
         *  This checks the checksum in buf only.
         *  All previous data, including that in buf, must have been copied to _dataBuf.
         *  Note that the checksum does not cover the padding.
         *  Caller must synchronize.
         *
         *  @param buf 16 bytes starting at off
         *  @param off offset of the 16-byte block (NOT of the checksum only)
         */
        private void receiveLastBlock(byte buf[], int off) {
            // on the last block
            long expectedCrc = DataHelper.fromLong(buf, off + BLOCK_SIZE - 4, 4);
            _crc.update(buf, off, BLOCK_SIZE - 4);
            long val = _crc.getValue();
            if (val == expectedCrc) {
                try {
                    I2NPMessageHandler h = acquireHandler(_context);

                    // Don't do readMessage(InputStream). I2NPMessageImpl.readBytes() copies the data
                    // from a stream to a temp buffer.
                    // We could extend BAIS to adjust the protected count variable to _size
                    // so that readBytes() doesn't read too far, but it could still read too far.
                    // So use the new handler method that limits the size.
                    h.readMessage(_dataBuf.getData(), 0, _size);
                    I2NPMessage read = h.lastRead();
                    long timeToRecv = _context.clock().now() - _stateBegin;
                    releaseHandler(h);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("I2NP message " + _messagesRead + "/" + (read != null ? read.getUniqueId() : 0) 
                                   + " received after " + timeToRecv + " with " + _size +"/"+ (_blocks*16) + " bytes on " + NTCPConnection.this.toString());
                    _context.statManager().addRateData("ntcp.receiveTime", timeToRecv);
                    _context.statManager().addRateData("ntcp.receiveSize", _size);

                    // FIXME move end of try block here.
                    // On the input side, move releaseHandler() and init() to a finally block.

                    if (read != null) {
                        _transport.messageReceived(read, _remotePeer, null, timeToRecv, _size);
                        _lastReceiveTime = _context.clock().now();
                        _messagesRead.incrementAndGet();
                    }
                } catch (I2NPMessageException ime) {
                    if (_log.shouldLog(Log.WARN)) {
                        _log.warn("Error parsing I2NP message on " + NTCPConnection.this +
                                  "\nDUMP:\n" + HexDump.dump(_dataBuf.getData(), 0, _size),
                                  ime);
                    }
                    _context.statManager().addRateData("ntcp.corruptI2NPIME", 1);
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("CRC incorrect for message " + _messagesRead + " (calc=" + val + " expected=" + expectedCrc +
                              ") size=" + _size + " blocks=" + _blocks + " on: " + NTCPConnection.this);
                    _context.statManager().addRateData("ntcp.corruptI2NPCRC", 1);
            }
            // get it ready for the next I2NP message
            init();
        }

        /*
         * Dummy.
         * @return 0 always
         * @since 0.9.36
         */
        public int getFramesReceived() { return 0; }
    }

    //// NTCP2 below here

    /** 
     * We are Alice. NTCP2 only.
     *
     * Caller MUST call recvEncryptedI2NP() after, for any remaining bytes in receive buffer
     *
     * @param clockSkew OUR clock minus BOB's clock in seconds (may be negative, obviously, but |val| should
     *                  be under 1 minute)
     * @param sender use to send to Bob
     * @param receiver use to receive from Bob
     * @param sip_ab 24 bytes to init SipHash to Bob
     * @param sip_ba 24 bytes to init SipHash from Bob
     * @since 0.9.36
     */
    synchronized void finishOutboundEstablishment(CipherState sender, CipherState receiver,
                                                  byte[] sip_ab, byte[] sip_ba, long clockSkew) {
        finishEstablishment(sender, receiver, sip_ab, sip_ba, clockSkew);
        _paddingConfig = OUR_PADDING;
        _transport.markReachable(getRemotePeer().calculateHash(), false);
        if (!_outbound.isEmpty())
            _transport.getWriter().wantsWrite(this, "outbound established");
        // NTCP2 outbound cannot have extra data
    }

    /** 
     * We are Bob. NTCP2 only.
     *
     * Caller MUST call recvEncryptedI2NP() after, for any remaining bytes in receive buffer
     *
     * @param clockSkew OUR clock minus ALICE's clock in seconds (may be negative, obviously, but |val| should
     *                  be under 1 minute)
     * @param sender use to send to Alice
     * @param receiver use to receive from Alice
     * @param sip_ba 24 bytes to init SipHash to Alice
     * @param sip_ab 24 bytes to init SipHash from Alice
     * @param hisPadding may be null
     * @since 0.9.36
     */
    synchronized void finishInboundEstablishment(CipherState sender, CipherState receiver,
                                                 byte[] sip_ba, byte[] sip_ab, long clockSkew,
                                                 NTCP2Options hisPadding) {
        finishEstablishment(sender, receiver, sip_ba, sip_ab, clockSkew);
        if (hisPadding != null) {
            _paddingConfig = OUR_PADDING.merge(hisPadding);
            if (_log.shouldDebug())
                _log.debug("Got padding options:" +
                          "\nhis padding options: " + hisPadding +
                          "\nour padding options: " + OUR_PADDING +
                          "\nmerged config is:    " + _paddingConfig);
        } else {
            _paddingConfig = OUR_PADDING;
        }
        NTCPConnection toClose = _transport.inboundEstablished(this);
        if (toClose != null && toClose != this) {
            if (_log.shouldWarn())
                _log.warn("Old connection closed: " + toClose + " replaced by " + this);
            _context.statManager().addRateData("ntcp.inboundEstablishedDuplicate", toClose.getUptime());
            toClose.close();
        }
        enqueueInfoMessage();
    }

    /** 
     * We are Bob. NTCP2 only.
     * This is only for invalid payload received in message 3. We send a termination and close.
     * There will be no receiving.
     *
     * @param sender use to send to Alice
     * @param sip_ba 24 bytes to init SipHash to Alice
     * @since 0.9.36
     */
    synchronized void failInboundEstablishment(CipherState sender, byte[] sip_ba, int reason) {
        _sender = sender;
        _sendSipk1 = fromLong8LE(sip_ba, 0);
        _sendSipk2 = fromLong8LE(sip_ba, 8);
        _sendSipIV = new byte[SIP_IV_LENGTH];
        System.arraycopy(sip_ba, 16, _sendSipIV, 0, SIP_IV_LENGTH);
        _establishState = EstablishBase.VERIFIED;
        _establishedOn = _context.clock().now();
        _nextMetaTime = Long.MAX_VALUE;
        _nextInfoTime = Long.MAX_VALUE;
        _paddingConfig = OUR_PADDING;
        sendTermination(reason, 0);
    }

    /** 
     * We are Alice or Bob. NTCP2 only.
     *
     * @param clockSkew see above
     * @param sender use to send
     * @param receiver use to receive
     * @param sip_send 24 bytes to init SipHash out
     * @param sip_recv 24 bytes to init SipHash in
     * @since 0.9.36
     */
    private synchronized void finishEstablishment(CipherState sender, CipherState receiver,
                                                  byte[] sip_send, byte[] sip_recv, long clockSkew) {
        if (_establishState == EstablishBase.VERIFIED) {
            IllegalStateException ise = new IllegalStateException("Already finished on " + this);
            _log.error("Already finished", ise);
            throw ise;
        }
        _sender = sender;
        _sendSipk1 = fromLong8LE(sip_send, 0);
        _sendSipk2 = fromLong8LE(sip_send, 8);
        _sendSipIV = new byte[SIP_IV_LENGTH];
        System.arraycopy(sip_send, 16, _sendSipIV, 0, SIP_IV_LENGTH);
        if (_log.shouldDebug())
            _log.debug("Send SipHash keys: " + _sendSipk1 + ' ' + _sendSipk2 + ' ' + Base64.encode(_sendSipIV));
        _clockSkew = clockSkew;
        _establishState = EstablishBase.VERIFIED;
        _establishedOn = _context.clock().now();
        _nextMetaTime = _establishedOn + (META_FREQUENCY / 2) + _context.random().nextInt(META_FREQUENCY);
        _nextInfoTime = _establishedOn + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
        _curReadState = new NTCP2ReadState(receiver, sip_recv);
    }

    /**
     * Read the encrypted message
     *
     * Call all methods from synchronized parent method.
     *
     * @since 0.9.36
     */
    private class NTCP2ReadState implements ReadState, NTCP2Payload.PayloadCallback {
        // temp to read the encrypted lengh into
        private final byte[] _recvLen = new byte[2];
        private final long _sipk1, _sipk2;
        // the siphash ratchet, as a byte array
        private final byte[] _sipIV = new byte[SIP_IV_LENGTH];
        private final CipherState _rcvr;
        // the size of the next frame, only valid if _received >= 0
        private int _framelen;
        // bytes received, -2 to _framelen
        private int _received = -2;
        private ByteArray _dataBuf;
        // Valid frames received in data phase
        private int _frameCount;
        // for logging only
        private int _blockCount;
        private boolean _terminated;

        /**
         *  @param keyData using first 24 bytes
         */
        public NTCP2ReadState(CipherState rcvr, byte[] keyData) {
            _rcvr = rcvr;
            _sipk1 = fromLong8LE(keyData, 0);
            _sipk2 = fromLong8LE(keyData, 8);
            System.arraycopy(keyData, 16, _sipIV, 0, SIP_IV_LENGTH);
            if (_log.shouldDebug())
                _log.debug("Recv SipHash keys: " + _sipk1 + ' ' + _sipk2 + ' ' + Base64.encode(_sipIV));
        }

        public void receive(ByteBuffer buf) {
            if (_terminated) {
                if (_log.shouldWarn())
                    _log.warn("Got " + buf.remaining() + " after termination on " + NTCPConnection.this);
                return;
            }
            while (buf.hasRemaining()) {
                if (_received == -2) {
                    _recvLen[0] = buf.get();
                    _received++;
                }
                if (_received == -1 && buf.hasRemaining()) {
                    _recvLen[1] = buf.get();
                    _received++;
                    long sipIV = SipHashInline.hash24(_sipk1, _sipk2, _sipIV);
                    //if (_log.shouldDebug())
                    //    _log.debug("Got Encrypted frame length: " + DataHelper.fromLong(_recvLen, 0, 2) +
                    //               " byte 1: " + (_recvLen[0] & 0xff) + " byte 2: " + (_recvLen[1] & 0xff) +
                    //               " decrypting with keys " + _sipk1 + ' ' + _sipk2 + ' ' + Base64.encode(_sipIV) + ' ' + sipIV);
                    _recvLen[0] ^= (byte) (sipIV >> 8);
                    _recvLen[1] ^= (byte) sipIV;
                    toLong8LE(_sipIV, 0, sipIV);
                    _framelen = (int) DataHelper.fromLong(_recvLen, 0, 2);
                    if (_framelen < OutboundNTCP2State.MAC_SIZE) {
                        if (_log.shouldWarn())
                            _log.warn("Short frame length: " + _framelen + " on " + NTCPConnection.this);
                        destroy();
                        // set a random length, then close
                        delayedClose(buf, _frameCount);
                        return;
                    }
                    //if (_log.shouldDebug())
                    //    _log.debug("Next frame length: " + _framelen);
                }
                int remaining = buf.remaining();
                if (remaining <= 0)
                    return;
                if (_received == 0 && remaining >= _framelen) {
                    // shortcut, zero copy, decrypt directly to the ByteBuffer,
                    // overwriting the encrypted data
                    byte[] data = buf.array();
                    int pos = buf.position();
                    boolean ok = decryptAndProcess(data, pos);
                    buf.position(pos + _framelen);
                    if (!ok) {
                        // decryptAndProcess called destroy() and set _terminated
                        delayedClose(buf, _frameCount);
                        return;
                    }
                    continue;
                }

                // allocate ByteArray,
                // unless we have one already and it's big enough
                if (_received == 0 && (_dataBuf == null || _dataBuf.getData().length < _framelen)) {
                    if (_dataBuf != null && _dataBuf.getData().length == BUFFER_SIZE)
                        releaseReadBuf(_dataBuf);
                    if (_framelen > BUFFER_SIZE) {
                        if (_log.shouldInfo())
                            _log.info("Allocating big ByteArray: " + _framelen);
                        byte[] data = new byte[_framelen];
                        _dataBuf = new ByteArray(data);
                    } else {
                        _dataBuf = acquireReadBuf();
                    }
                }

                // We now have a ByteArray in _dataBuf,
                // copy from ByteBuffer to ByteArray
                int toGet = Math.min(buf.remaining(), _framelen - _received);
                byte[] data = _dataBuf.getData();
                buf.get(data, _received, toGet);
                _received += toGet;
                if (_received < _framelen)
                    return;
                // decrypt to the ByteArray, overwriting the encrypted data
                boolean ok = decryptAndProcess(data, 0);
                // release buf only if we're not going around again
                if (!ok || buf.remaining() < 2) {
                    if (!ok)
                        delayedClose(buf, _frameCount);
                    // delayedClose() may have zeroed out _databuf
                    if (_dataBuf != null) {
                        if (_dataBuf.getData().length == BUFFER_SIZE)
                            releaseReadBuf(_dataBuf);
                        _dataBuf = null;
                    }
                    if (!ok)
                        return;
                }
                // go around again
            }
        }

        /**
         *  Decrypts in place.
         *  Length is _framelen
         *  Side effects: Sets _received = -2, increments _frameCount and _blockCount if valid,
         *  calls destroy() and sets _terminated if termination block received or if invalid.
         *
         *  Does not call close() on failure. Caller MUST call delayedClose() if this returns false.
         *
         *  @return success, false for fatal error (AEAD) only
         */
        private boolean decryptAndProcess(byte[] data, int off) {
            if (_log.shouldDebug())
                _log.debug("Decrypting frame " + _frameCount + " with " + _framelen + " bytes");
            try {
                _rcvr.decryptWithAd(null, data, off, data, off, _framelen);
            } catch (GeneralSecurityException gse) {
                // TODO set a random length, then close
                if (_log.shouldWarn())
                    _log.warn("Bad AEAD data phase frame " + _frameCount +
                              " with " + _framelen + " bytes on " + NTCPConnection.this, gse);
                destroy();
                return false;
            }
            // no payload processing errors in the data phase are fatal
            try {
                int blocks = NTCP2Payload.processPayload(_context, this, data, off,
                                                         _framelen - OutboundNTCP2State.MAC_SIZE, false);
                if (_log.shouldDebug())
                    _log.debug("Processed " + blocks + " blocks in frame");
                _blockCount += blocks;
            } catch (IOException ioe) {
                if (_log.shouldWarn())
                    _log.warn("Fail payload " + NTCPConnection.this, ioe);
            } catch (DataFormatException dfe) {
                if (_log.shouldWarn())
                    _log.warn("Fail payload " + NTCPConnection.this, dfe);
            } catch (I2NPMessageException ime) {
                if (_log.shouldWarn())
                    _log.warn("Error parsing I2NP message on " + NTCPConnection.this, ime);
                _context.statManager().addRateData("ntcp.corruptI2NPIME", 1);
            }
            _received = -2;
            _frameCount++;
            return true;
        }

        public void destroy() {
            if (_log.shouldInfo())
                _log.info("NTCP2 read state destroy() on " + NTCPConnection.this, new Exception("I did it"));
            if (_dataBuf != null && _dataBuf.getData().length == BUFFER_SIZE)
                releaseReadBuf(_dataBuf);
            _dataBuf = null;
            _rcvr.destroy();
            _terminated = true;
        }

        public int getFramesReceived() { return _frameCount; }

        //// PayloadCallbacks

        public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
            if (_log.shouldDebug())
                _log.debug("Got updated RI");
            _messagesRead.incrementAndGet();
            try {
                Hash h = ri.getHash();
                RouterInfo old = _context.netDb().store(h, ri);
                if (flood && !ri.equals(old)) {
                    FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                    if (fndf.floodConditional(ri)) {
                        if (_log.shouldDebug())
                            _log.debug("Flooded the RI: " + h);
                    } else {
                        if (_log.shouldInfo())
                            _log.info("Flood request but we didn't: " + h);
                    }
                }
            } catch (IllegalArgumentException iae) {
                throw new DataFormatException("RI store fail: " + ri, iae);
            }
        }

        public void gotDateTime(long time) {
            if (_log.shouldDebug())
                _log.debug("Got updated datetime block");
            receiveTimestamp((time + 500) / 1000);
            // update skew
        }

        public void gotI2NP(I2NPMessage msg) {
            if (_log.shouldDebug())
                _log.debug("Got I2NP msg: " + msg);
            long timeToRecv = 0; // _context.clock().now() - _stateBegin;
            int size = 100; // FIXME
            _transport.messageReceived(msg, _remotePeer, null, timeToRecv, size);
            _lastReceiveTime = _context.clock().now();
            _messagesRead.incrementAndGet();
        }

        public void gotOptions(byte[] options, boolean isHandshake) {
            NTCP2Options hisPadding = NTCP2Options.fromByteArray(options);
            if (hisPadding == null) {
                if (_log.shouldWarn())
                    _log.warn("Got options length " + options.length + " on: " + this);
                return;
            }
            _paddingConfig = OUR_PADDING.merge(hisPadding);
            if (_log.shouldDebug())
                _log.debug("Got padding options:" +
                          "\nhis padding options: " + hisPadding +
                          "\nour padding options: " + OUR_PADDING +
                          "\nmerged config is:    " + _paddingConfig);
        }

        public void gotTermination(int reason, long lastReceived) {
            if (_log.shouldInfo())
                _log.info("Got Termination: " + reason + " total rcvd: " + lastReceived + " on " + NTCPConnection.this);
            // close() calls destroy() sets _terminated
            close();
        }

        public void gotUnknown(int type, int len) {
            if (_log.shouldWarn())
                _log.warn("Got unknown block type " + type + " length " + len + " on " + NTCPConnection.this);
        }

        public void gotPadding(int paddingLength, int frameLength) {
            if (_log.shouldDebug())
                _log.debug("Got " + paddingLength +
                          " bytes padding in " + frameLength +
                          " byte frame; ratio: " + (((float) paddingLength) / ((float) frameLength)) +
                          " configured min: " + _paddingConfig.getRecvMin() +
                          " configured max: " + _paddingConfig.getRecvMax());
        }
    }

    /**
     * After an AEAD failure, read a random number of bytes,
     * with a brief timeout, and then fail.
     * This replaces _curReadState, so no more messages will be received.
     *
     * Call this only on data phase AEAD failure.
     * For other failures, use sendTermination().
     *
     * @param buf possibly with data remaining
     * @param validFramesRcvd to be sent in termination message
     * @since 0.9.36
     */
    private void delayedClose(ByteBuffer buf, int validFramesRcvd) {
        int toRead = 18 + _context.random().nextInt(NTCP2_FAIL_READ);
        int remaining = toRead - buf.remaining();
        if (remaining > 0) {
            if (_log.shouldWarn())
                _log.warn("delayed close, AEAD failure after " + validFramesRcvd +
                          " good frames, to read: " + toRead + " on " + this, new Exception("I did it"));
            _curReadState = new NTCP2FailState(toRead, validFramesRcvd);
            _curReadState.receive(buf);
        } else {
            if (_log.shouldWarn())
                _log.warn("immediate close, AEAD failure after " + validFramesRcvd +
                          " good frames and reading " + toRead + " on " + this, new Exception("I did it"));
            sendTermination(REASON_AEAD, validFramesRcvd);
        }
    }

    /**
     * After an AEAD failure, read a random number of bytes,
     * with a brief timeout, and then fail.
     *
     * Call all methods from synchronized parent method.
     *
     * @since 0.9.36
     */
    private class NTCP2FailState extends SimpleTimer2.TimedEvent implements ReadState {
        private final int _toRead;
        private final int _validFramesRcvd;
        private int _read;

        /**
         *  @param toRead how much left to read
         *  @param validFramesRcvd to be sent in termination message
         */
        public NTCP2FailState(int toRead, int validFramesRcvd) {
            super(_context.simpleTimer2());
            _toRead = toRead;
            _validFramesRcvd = validFramesRcvd;
            schedule(NTCP2_FAIL_TIMEOUT);
        }

        public void receive(ByteBuffer buf) {
            _read += buf.remaining();
            if (_read >= _toRead) {
                cancel();
                // only do this once
                _read = Integer.MIN_VALUE;
                if (_log.shouldWarn())
                    _log.warn("close after AEAD failure and reading " + _toRead + " on " + NTCPConnection.this);
                sendTermination(REASON_AEAD, _validFramesRcvd);
            }
        }

        public void destroy() {
            cancel();
        }

        public void timeReached() {
            // only do this once
            _read = Integer.MIN_VALUE;
            if (_log.shouldWarn())
                _log.warn("timeout after AEAD failure waiting for more data on " + NTCPConnection.this);
            sendTermination(REASON_AEAD, _validFramesRcvd);
        }

        public int getFramesReceived() { return 0; }
    }

    /**
     * Close after a brief timeout.
     * Construct after sending termination.
     *
     * @since 0.9.36
     */
    private class DelayedCloser extends SimpleTimer2.TimedEvent {

        /** schedules itself */
        public DelayedCloser() {
            super(_context.simpleTimer2());
            schedule(NTCP2_TERMINATION_CLOSE_DELAY);
        }

        public void timeReached() {
            close();
        }
    }

    //// Utils

    /**
     *  XOR a into b. Modifies b. a is unmodified.
     *  @param a 16 bytes
     *  @param b 16 bytes
     *  @since 0.9.36
     */
    private static void xor16(byte[] a, byte[] b) {
        for (int i = 0; i < BLOCK_SIZE; i++) {
            b[i] ^= a[i];
        }
    }

    /**
     * Little endian.
     * Same as DataHelper.fromlongLE(src, offset, 8) but allows negative result
     *
     * @throws ArrayIndexOutOfBoundsException
     * @since 0.9.36
     */
    private static long fromLong8LE(byte src[], int offset) {
        long rv = 0;
        for (int i = offset + 7; i >= offset; i--) {
            rv <<= 8;
            rv |= src[i] & 0xFF;
        }
        return rv;
    }
    
    /**
     * Little endian.
     * Same as DataHelper.fromlongLE(target, offset, 8, value) but allows negative value
     *
     */
    private static void toLong8LE(byte target[], int offset, long value) {
        int limit = offset + 8;
        for (int i = offset; i < limit; i++) {
            target[i] = (byte) value;
            value >>= 8;
        }
    }

    @Override
    public String toString() {
        return "NTCP" + _version + " conn " +
               _connID +
               (_isInbound ? (" from " + _chan.socket().getInetAddress() + " port " + _chan.socket().getPort() + ' ')
                           : (" to " + _remAddr.getHost() + " port " + _remAddr.getPort() + ' ')) +
               (_remotePeer == null ? "unknown" : _remotePeer.calculateHash().toBase64().substring(0,6)) +
               (isEstablished() ? "" : " not established") +
               " created " + DataHelper.formatDuration(getTimeSinceCreated()) + " ago," +
               " last send " + DataHelper.formatDuration(getTimeSinceSend()) + " ago," +
               " last recv " + DataHelper.formatDuration(getTimeSinceReceive()) + " ago," +
               " sent " + _messagesWritten + ',' +
               " rcvd " + _messagesRead;
    }
}
