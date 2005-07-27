package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.HMACSHA256Generator;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Basic delivery unit containing the datagram.  This also maintains a cache
 * of object instances to allow rapid reuse.
 *
 */
public class UDPPacket {
    private I2PAppContext _context;
    private static Log _log;
    private volatile DatagramPacket _packet;
    private volatile short _priority;
    private volatile long _initializeTime;
    private volatile long _expiration;
    private volatile byte[] _data;
    private volatile ByteArray _dataBuf;
    private volatile int _markedType;
    private volatile RemoteHostId _remoteHost;
    private volatile boolean _released;
    private volatile Exception _releasedBy;
    private volatile Exception _acquiredBy;
  
    private static final List _packetCache;
    static {
        _packetCache = new ArrayList(256);
        _log = I2PAppContext.getGlobalContext().logManager().getLog(UDPPacket.class);
    }
    
    private static final boolean CACHE = false; // TODO: support caching to cut churn down a /lot/
      
    static final int MAX_PACKET_SIZE = 2048;
    public static final int IV_SIZE = 16;
    public static final int MAC_SIZE = 16;
    
    public static final int PAYLOAD_TYPE_SESSION_REQUEST = 0;
    public static final int PAYLOAD_TYPE_SESSION_CREATED = 1;
    public static final int PAYLOAD_TYPE_SESSION_CONFIRMED = 2;
    public static final int PAYLOAD_TYPE_RELAY_REQUEST = 3;
    public static final int PAYLOAD_TYPE_RELAY_RESPONSE = 4;
    public static final int PAYLOAD_TYPE_RELAY_INTRO = 5;
    public static final int PAYLOAD_TYPE_DATA = 6;
    
    // various flag fields for use in the data packets
    public static final byte DATA_FLAG_EXPLICIT_ACK = (byte)(1 << 7);
    public static final byte DATA_FLAG_ACK_BITFIELDS = (1 << 6);
    public static final byte DATA_FLAG_ECN = (1 << 4);
    public static final byte DATA_FLAG_WANT_ACKS = (1 << 3);
    public static final byte DATA_FLAG_WANT_REPLY = (1 << 2);
    public static final byte DATA_FLAG_EXTENDED = (1 << 1);
    
    public static final byte BITFIELD_CONTINUATION = (byte)(1 << 7);
    
    private static final int MAX_VALIDATE_SIZE = MAX_PACKET_SIZE;
    private static final ByteCache _validateCache = ByteCache.getInstance(64, MAX_VALIDATE_SIZE);
    private static final ByteCache _ivCache = ByteCache.getInstance(64, IV_SIZE);
    private static final ByteCache _dataCache = ByteCache.getInstance(128, MAX_PACKET_SIZE);

    private UDPPacket(I2PAppContext ctx) {
        _context = ctx;
        _dataBuf = _dataCache.acquire();
        _data = _dataBuf.getData(); 
        _packet = new DatagramPacket(_data, MAX_PACKET_SIZE);
        _initializeTime = _context.clock().now();
        _markedType = -1;
        _remoteHost = null;
    }
    
    public void initialize(int priority, long expiration, InetAddress host, int port) {
        _priority = (short)priority;
        _expiration = expiration;
        resetBegin();
        Arrays.fill(_data, (byte)0x00);
        //_packet.setLength(0);
        _packet.setAddress(host);
        _packet.setPort(port);
        _remoteHost = null;
        _released = false;
        _releasedBy = null;
    }
    
    public void writeData(byte src[], int offset, int len) { 
        verifyNotReleased();
        System.arraycopy(src, offset, _data, 0, len);
        _packet.setLength(len);
        resetBegin();
    }
    public DatagramPacket getPacket() { verifyNotReleased(); return _packet; }
    public short getPriority() { verifyNotReleased(); return _priority; }
    public long getExpiration() { verifyNotReleased(); return _expiration; }
    public long getBegin() { verifyNotReleased(); return _initializeTime; }
    public long getLifetime() { verifyNotReleased(); return _context.clock().now() - _initializeTime; }
    public void resetBegin() { _initializeTime = _context.clock().now(); }
    /** flag this packet as a particular type for accounting purposes */
    public void markType(int type) { verifyNotReleased(); _markedType = type; }
    /** 
     * flag this packet as a particular type for accounting purposes, with
     * 1 implying the packet is an ACK, otherwise it is a data packet
     *
     */
    public int getMarkedType() { verifyNotReleased(); return _markedType; }
    
    public RemoteHostId getRemoteHost() {
        if (_remoteHost == null)
            _remoteHost = new RemoteHostId(_packet.getAddress().getAddress(), _packet.getPort());
        return _remoteHost;
    }
    
    /**
     * Validate the packet against the MAC specified, returning true if the
     * MAC matches, false otherwise.
     *
     */
    public boolean validate(SessionKey macKey) {
        verifyNotReleased(); 
        boolean eq = false;
        ByteArray buf = _validateCache.acquire();
        
        // validate by comparing _data[0:15] and
        // HMAC(payload + IV + payloadLength, macKey)
        
        int payloadLength = _packet.getLength() - MAC_SIZE - IV_SIZE;
        if (payloadLength > 0) {
            int off = 0;
            System.arraycopy(_data, _packet.getOffset() + MAC_SIZE + IV_SIZE, buf.getData(), off, payloadLength);
            off += payloadLength;
            System.arraycopy(_data, _packet.getOffset() + MAC_SIZE, buf.getData(), off, IV_SIZE);
            off += IV_SIZE;
            DataHelper.toLong(buf.getData(), off, 2, payloadLength);
            off += 2;

            eq = _context.hmac().verify(macKey, buf.getData(), 0, off, _data, _packet.getOffset(), MAC_SIZE);
            /*
            Hash hmac = _context.hmac().calculate(macKey, buf.getData(), 0, off);

            if (_log.shouldLog(Log.DEBUG)) {
                StringBuffer str = new StringBuffer(128);
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
            if (_log.shouldLog(Log.WARN))
                _log.warn("Payload length is " + payloadLength);
        }
        
        _validateCache.release(buf);
        return eq;
    }
    
    /**
     * Decrypt this valid packet, overwriting the _data buffer's payload
     * with the decrypted data (leaving the MAC and IV unaltered)
     * 
     */
    public void decrypt(SessionKey cipherKey) {
        verifyNotReleased(); 
        ByteArray iv = _ivCache.acquire();
        System.arraycopy(_data, MAC_SIZE, iv.getData(), 0, IV_SIZE);
        int len = _packet.getLength();
        _context.aes().decrypt(_data, _packet.getOffset() + MAC_SIZE + IV_SIZE, _data, _packet.getOffset() + MAC_SIZE + IV_SIZE, cipherKey, iv.getData(), len - MAC_SIZE - IV_SIZE);
        _ivCache.release(iv);
    }
    
    public String toString() {
        verifyNotReleased(); 
        StringBuffer buf = new StringBuffer(64);
        buf.append(_packet.getLength());
        buf.append(" byte packet with ");
        buf.append(_packet.getAddress().getHostAddress()).append(":");
        buf.append(_packet.getPort());
        buf.append(" id=").append(System.identityHashCode(this));
        buf.append("\ndata=").append(Base64.encode(_packet.getData(), _packet.getOffset(), _packet.getLength()));
        return buf.toString();
    }
    
    public static UDPPacket acquire(I2PAppContext ctx) {
        UDPPacket rv = null;
        if (CACHE) {
            synchronized (_packetCache) {
                if (_packetCache.size() > 0) {
                    rv = (UDPPacket)_packetCache.remove(0);
                }
            }
            /*
            if (rv != null) {
                rv._context = ctx;
                //rv._log = ctx.logManager().getLog(UDPPacket.class);
                rv.resetBegin();
                Arrays.fill(rv._data, (byte)0x00);
                rv._markedType = -1;
                rv._dataBuf.setValid(0);
                rv._released = false;
                rv._releasedBy = null;
                rv._acquiredBy = null;
                rv.setPacketDataLength(0);
                synchronized (rv._packet) {
                    //rv._packet.setLength(0);
                    //rv._packet.setPort(1);
                }
            }
             */
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
        if (!CACHE) {
            _dataCache.release(_dataBuf);
            return;
        }
        synchronized (_packetCache) {
            if (_packetCache.size() <= 64) {
                _packetCache.add(this);
            } else {
                _dataCache.release(_dataBuf);
            }
        }
    }
    
    private void verifyNotReleased() {
        if (CACHE) return;
        if (_released) {
            _log.log(Log.CRIT, "Already released.  current stack trace is:", new Exception());
            _log.log(Log.CRIT, "Released by: ", _releasedBy);
            _log.log(Log.CRIT, "Acquired by: ", _acquiredBy);
        }
    }
}
