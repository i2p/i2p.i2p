package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Basic delivery unit containing the datagram.  This also maintains a cache
 * of object instances to allow rapid reuse.
 *
 */
class UDPPacket {
    private I2PAppContext _context;
    private final DatagramPacket _packet;
    private volatile short _priority;
    private volatile long _initializeTime;
    private volatile long _expiration;
    private final byte[] _data;
    private final byte[] _validateBuf;
    private final byte[] _ivBuf;
    private volatile int _markedType;
    private volatile RemoteHostId _remoteHost;
    private volatile boolean _released;
    private volatile Exception _releasedBy;
    private volatile Exception _acquiredBy;
    private long _enqueueTime;
    private long _receivedTime;
    //private long _beforeValidate;
    //private long _afterValidate;
    //private long _beforeReceiveFragments;
    //private long _afterHandlingTime;
    private int _validateCount;
    // private boolean _isInbound;
  
    //  Warning - this mixes contexts in a multi-router JVM
    private static final Queue<UDPPacket> _packetCache;
    private static final boolean CACHE = true;
    private static final int MIN_CACHE_SIZE = 64;
    private static final int MAX_CACHE_SIZE = 256;
    static {
        if (CACHE) {
            long maxMemory = Runtime.getRuntime().maxMemory();
            if (maxMemory == Long.MAX_VALUE)
                maxMemory = 96*1024*1024l;
            int csize = (int) Math.max(MIN_CACHE_SIZE, Math.min(MAX_CACHE_SIZE, maxMemory / (1024*1024)));
            _packetCache = new LinkedBlockingQueue(csize);
        } else {
            _packetCache = null;
        }
    }
    
    /**
     *  Actually it is one less than this, we assume
     *  if a received packet is this big it is truncated.
     *  This is bigger than PeerState.LARGE_MTU, as the far-end's
     *  LARGE_MTU may be larger than ours.
     *
     *  Due to longstanding bugs, a packet may be larger than LARGE_MTU
     *  (acks and padding). Together with an increase in the LARGE_MTU to
     *  1492 in release 0.8.9, routers from 0.8.9 - 0.8.11 can generate
     *  packets up to 1536. Data packets are always a multiple of 16,
     *  so make this 4 + a multiple of 16.
     */
    static final int MAX_PACKET_SIZE = 1572;
    public static final int IV_SIZE = 16;
    public static final int MAC_SIZE = 16;
    
    /** Message types, 4 bits max */
    public static final int PAYLOAD_TYPE_SESSION_REQUEST = 0;
    public static final int PAYLOAD_TYPE_SESSION_CREATED = 1;
    public static final int PAYLOAD_TYPE_SESSION_CONFIRMED = 2;
    public static final int PAYLOAD_TYPE_RELAY_REQUEST = 3;
    public static final int PAYLOAD_TYPE_RELAY_RESPONSE = 4;
    public static final int PAYLOAD_TYPE_RELAY_INTRO = 5;
    public static final int PAYLOAD_TYPE_DATA = 6;
    public static final int PAYLOAD_TYPE_TEST = 7;
    /** @since 0.8.1 */
    public static final int PAYLOAD_TYPE_SESSION_DESTROY = 8;
    
    // various flag fields for use in the data packets
    public static final byte DATA_FLAG_EXPLICIT_ACK = (byte)(1 << 7);
    public static final byte DATA_FLAG_ACK_BITFIELDS = (1 << 6);
    // unused
    public static final byte DATA_FLAG_ECN = (1 << 4);
    public static final byte DATA_FLAG_WANT_ACKS = (1 << 3);
    public static final byte DATA_FLAG_WANT_REPLY = (1 << 2);
    // unused
    public static final byte DATA_FLAG_EXTENDED = (1 << 1);
    
    public static final byte BITFIELD_CONTINUATION = (byte)(1 << 7);
    
    private static final int MAX_VALIDATE_SIZE = MAX_PACKET_SIZE;

    private UDPPacket(I2PAppContext ctx) {
        //ctx.statManager().createRateStat("udp.fetchRemoteSlow", "How long it takes to grab the remote ip info", "udp", UDPTransport.RATES);
        // the data buffer is clobbered on init(..), but we need it to bootstrap
        _data = new byte[MAX_PACKET_SIZE];
        _packet = new DatagramPacket(_data, MAX_PACKET_SIZE);
        _validateBuf = new byte[MAX_VALIDATE_SIZE];
        _ivBuf = new byte[IV_SIZE];
        init(ctx);
    }

    private void init(I2PAppContext ctx) {
        _context = ctx;
        //_dataBuf = _dataCache.acquire();
        Arrays.fill(_data, (byte)0);
        //_packet = new DatagramPacket(_data, MAX_PACKET_SIZE);
        //
        // WARNING -
        // Doesn't seem like we should have to do this every time,
        // from reading the DatagramPacket javadocs,
        // but we get massive corruption without it.
        _packet.setData(_data);
        // _isInbound = inbound;
        _initializeTime = _context.clock().now();
        _markedType = -1;
        _validateCount = 0;
        _remoteHost = null;
        _released = false;
    }
    
  /****
    public void writeData(byte src[], int offset, int len) { 
        verifyNotReleased();
        System.arraycopy(src, offset, _data, 0, len);
        _packet.setLength(len);
        resetBegin();
    }
  ****/

    /** */
    public DatagramPacket getPacket() { verifyNotReleased(); return _packet; }
    public short getPriority() { verifyNotReleased(); return _priority; }
    public long getExpiration() { verifyNotReleased(); return _expiration; }
    public long getBegin() { verifyNotReleased(); return _initializeTime; }
    public long getLifetime() { /** verifyNotReleased(); */ return _context.clock().now() - _initializeTime; }
    public void resetBegin() { _initializeTime = _context.clock().now(); }
    /** flag this packet as a particular type for accounting purposes */
    public void markType(int type) { verifyNotReleased(); _markedType = type; }
    /** 
     * flag this packet as a particular type for accounting purposes, with
     * 1 implying the packet is an ACK, otherwise it is a data packet
     *
     */
    public int getMarkedType() { verifyNotReleased(); return _markedType; }
    
    private int _messageType;
    private int _fragmentCount;
    /** only for debugging and stats, does not go on the wire */
    int getMessageType() { return _messageType; }
    /** only for debugging and stats, does not go on the wire */
    void setMessageType(int type) { _messageType = type; }
    int getFragmentCount() { return _fragmentCount; }
    void setFragmentCount(int count) { _fragmentCount = count; }

    RemoteHostId getRemoteHost() {
        if (_remoteHost == null) {
            //long before = System.currentTimeMillis();
            InetAddress addr = _packet.getAddress();
            byte ip[] = addr.getAddress();
            int port = _packet.getPort();
            _remoteHost = new RemoteHostId(ip, port);
            //long timeToFetch = System.currentTimeMillis() - before;
            //if (timeToFetch > 50)
            //    _context.statManager().addRateData("udp.fetchRemoteSlow", timeToFetch, getLifetime());
        }
        return _remoteHost;
    }
    
    /**
     * Validate the packet against the MAC specified, returning true if the
     * MAC matches, false otherwise.
     *
     */
    public boolean validate(SessionKey macKey) {
        verifyNotReleased(); 
        //_beforeValidate = _context.clock().now();
        boolean eq = false;
        Arrays.fill(_validateBuf, (byte)0);
        
        // validate by comparing _data[0:15] and
        // HMAC(payload + IV + (payloadLength ^ protocolVersion), macKey)
        
        int payloadLength = _packet.getLength() - MAC_SIZE - IV_SIZE;
        if (payloadLength > 0) {
            int off = 0;
            System.arraycopy(_data, _packet.getOffset() + MAC_SIZE + IV_SIZE, _validateBuf, off, payloadLength);
            off += payloadLength;
            System.arraycopy(_data, _packet.getOffset() + MAC_SIZE, _validateBuf, off, IV_SIZE);
            off += IV_SIZE;
            DataHelper.toLong(_validateBuf, off, 2, payloadLength ^ PacketBuilder.PROTOCOL_VERSION);
            off += 2;

            eq = _context.hmac().verify(macKey, _validateBuf, 0, off, _data, _packet.getOffset(), MAC_SIZE);
            /*
            Hash hmac = _context.hmac().calculate(macKey, buf.getData(), 0, off);

            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder str = new StringBuilder(128);
                str.append(_packet.getLength()).append(" byte packet received, payload length ");
                str.append(payloadLength);
                str.append("\nIV: ").append(Base64.encode(buf.getData(), payloadLength, IV_SIZE));
                str.append("\nIV2: ").append(Base64.encode(_data, MAC_SIZE, IV_SIZE));
                str.append("\nlen: ").append(DataHelper.fromLong(buf.getData(), payloadLength + IV_SIZE, 2));
                str.append("\nMAC key: ").append(macKey.toBase64());
                str.append("\ncalc HMAC: ").append(Base64.encode(hmac.getData()));
                str.append("\nread HMAC: ").append(Base64.encode(_data, _packet.getOffset(), MAC_SIZE));
                str.append("\nraw: ").append(Base64.encode(_data, _packet.getOffset(), _packet.getLength()));
                _log.debug(str.toString());
            }
            eq = DataHelper.eq(hmac.getData(), 0, _data, _packet.getOffset(), MAC_SIZE);
             */
        } else {
            //if (_log.shouldLog(Log.WARN))
            //    _log.warn("Payload length is " + payloadLength);
        }
        
        //_afterValidate = _context.clock().now();
        _validateCount++;
        return eq;
    }
    
    /**
     * Decrypt this valid packet, overwriting the _data buffer's payload
     * with the decrypted data (leaving the MAC and IV unaltered)
     * 
     */
    public void decrypt(SessionKey cipherKey) {
        verifyNotReleased(); 
        Arrays.fill(_ivBuf, (byte)0);
        System.arraycopy(_data, MAC_SIZE, _ivBuf, 0, IV_SIZE);
        int len = _packet.getLength();
        _context.aes().decrypt(_data, _packet.getOffset() + MAC_SIZE + IV_SIZE, _data, _packet.getOffset() + MAC_SIZE + IV_SIZE, cipherKey, _ivBuf, len - MAC_SIZE - IV_SIZE);
    }

    /** the UDPReceiver has tossed it onto the inbound queue */
    void enqueue() { _enqueueTime = _context.clock().now(); }
    /** a packet handler has pulled it off the inbound queue */
    void received() { _receivedTime = _context.clock().now(); }

    /** a packet handler has decrypted and verified the packet and is about to parse out the good bits */
    //void beforeReceiveFragments() { _beforeReceiveFragments = _context.clock().now(); }
    /** a packet handler has finished parsing out the good bits */
    //void afterHandling() { _afterHandlingTime = _context.clock().now(); } 
      
    /** the UDPReceiver has tossed it onto the inbound queue */
    //long getTimeSinceEnqueue() { return (_enqueueTime > 0 ? _context.clock().now() - _enqueueTime : 0); }

    /** a packet handler has pulled it off the inbound queue */
    long getTimeSinceReceived() { return (_receivedTime > 0 ? _context.clock().now() - _receivedTime : 0); }

    /** a packet handler has decrypted and verified the packet and is about to parse out the good bits */
    //long getTimeSinceReceiveFragments() { return (_beforeReceiveFragments > 0 ? _context.clock().now() - _beforeReceiveFragments : 0); }
    /** a packet handler has finished parsing out the good bits */
    //long getTimeSinceHandling() { return (_afterHandlingTime > 0 ? _context.clock().now() - _afterHandlingTime : 0); }
    
    // Following 5: All used only for stats in PacketHandler, commented out

    /** when it was added to the endpoint's receive queue */
    //long getEnqueueTime() { return _enqueueTime; }
    /** when it was pulled off the endpoint receive queue */
    //long getReceivedTime() { return _receivedTime; }
    /** when we began validate() */
    //long getBeforeValidate() { return _beforeValidate; }
    /** when we finished validate() */
    //long getAfterValidate() { return _afterValidate; }
    /** how many times we tried to validate the packet */
    //int getValidateCount() { return _validateCount; }
    
    @Override
    public String toString() {
        verifyNotReleased(); 
        StringBuilder buf = new StringBuilder(256);
        buf.append(_packet.getLength());
        buf.append(" byte packet with ");
        buf.append(_packet.getAddress().getHostAddress()).append(":");
        buf.append(_packet.getPort());
        //buf.append(" id=").append(System.identityHashCode(this));
        buf.append(" msg type=").append(_messageType);
        buf.append(" mark type=").append(_markedType);
        buf.append(" frag count=").append(_fragmentCount);

        buf.append(" sinceEnqueued=").append((_enqueueTime > 0 ? _context.clock().now()-_enqueueTime : -1));
        buf.append(" sinceReceived=").append((_receivedTime > 0 ? _context.clock().now()-_receivedTime : -1));
        //buf.append(" beforeReceiveFragments=").append((_beforeReceiveFragments > 0 ? _context.clock().now()-_beforeReceiveFragments : -1));
        //buf.append(" sinceHandled=").append((_afterHandlingTime > 0 ? _context.clock().now()-_afterHandlingTime : -1));
        //buf.append("\ndata=").append(Base64.encode(_packet.getData(), _packet.getOffset(), _packet.getLength()));
        return buf.toString();
    }
    
    /**
     *  @param inbound unused
     */
    public static UDPPacket acquire(I2PAppContext ctx, boolean inbound) {
        UDPPacket rv = null;
        if (CACHE) {
            rv = _packetCache.poll();
            if (rv != null)
                rv.init(ctx);
        }
        if (rv == null)
            rv = new UDPPacket(ctx);
        //if (rv._acquiredBy != null) {
        //    _log.log(Log.CRIT, "Already acquired!  current stack trace is:", new Exception());
        //    _log.log(Log.CRIT, "Earlier acquired:", rv._acquiredBy);
        //}
        //rv._acquiredBy = new Exception("acquired on");
        return rv;
    }

    public void release() {
        verifyNotReleased();
        _released = true;
        //_releasedBy = new Exception("released by");
        //_acquiredBy = null;
        //
        //_dataCache.release(_dataBuf);
        if (!CACHE)
            return;
        _packetCache.offer(this);
    }
    
    /**
     *  Call at shutdown/startup to not hold ctx refs
     *  @since 0.9.2
     */
    public static void clearCache() {
        if (CACHE)
            _packetCache.clear();
    }

    private void verifyNotReleased() {
        if (!CACHE) return;
        if (_released) {
            Log log = _context.logManager().getLog(UDPPacket.class);
            log.error("Already released", new Exception());
            //log.log(Log.CRIT, "Released by: ", _releasedBy);
            //log.log(Log.CRIT, "Acquired by: ", _acquiredBy);
        }
    }
}
