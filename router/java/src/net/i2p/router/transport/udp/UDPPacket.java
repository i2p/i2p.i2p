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
    private DatagramPacket _packet;
    private short _priority;
    private long _initializeTime;
    private long _expiration;
    private byte[] _data;
    private ByteArray _dataBuf;
  
    private static final List _packetCache;
    static {
        _packetCache = new ArrayList(256);
        _log = I2PAppContext.getGlobalContext().logManager().getLog(UDPPacket.class);
    }
    
    private static final boolean CACHE = false;
      
    private static final int MAX_PACKET_SIZE = 2048;
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
    public static final byte DATA_FLAG_EXPLICIT_NACK = (1 << 6);
    public static final byte DATA_FLAG_NUMACKS = (1 << 5);
    public static final byte DATA_FLAG_ECN = (1 << 4);
    public static final byte DATA_FLAG_WANT_ACKS = (1 << 3);
    public static final byte DATA_FLAG_WANT_REPLY = (1 << 2);
    public static final byte DATA_FLAG_EXTENDED = (1 << 1);
    
    private static final int MAX_VALIDATE_SIZE = MAX_PACKET_SIZE;
    private static final ByteCache _validateCache = ByteCache.getInstance(16, MAX_VALIDATE_SIZE);
    private static final ByteCache _ivCache = ByteCache.getInstance(16, IV_SIZE);
    private static final ByteCache _dataCache = ByteCache.getInstance(64, MAX_PACKET_SIZE);

    private UDPPacket(I2PAppContext ctx) {
        _context = ctx;
        _dataBuf = _dataCache.acquire();
        _data = _dataBuf.getData(); 
        _packet = new DatagramPacket(_data, MAX_PACKET_SIZE);
        _initializeTime = _context.clock().now();
    }
    
    public void initialize(short priority, long expiration, InetAddress host, int port) {
        _priority = priority;
        _expiration = expiration;
        resetBegin();
        Arrays.fill(_data, (byte)0x00);
        _packet.setLength(0);
        _packet.setAddress(host);
        _packet.setPort(port);
    }
    
    public void writeData(byte src[], int offset, int len) { 
        System.arraycopy(src, offset, _data, 0, len);
        _packet.setLength(len);
        resetBegin();
    }
    public DatagramPacket getPacket() { return _packet; }
    public short getPriority() { return _priority; }
    public long getExpiration() { return _expiration; }
    public long getLifetime() { return _context.clock().now() - _initializeTime; }
    public void resetBegin() { _initializeTime = _context.clock().now(); }
    
    /**
     * Validate the packet against the MAC specified, returning true if the
     * MAC matches, false otherwise.
     *
     */
    public boolean validate(SessionKey macKey) {
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
        ByteArray iv = _ivCache.acquire();
        System.arraycopy(_data, MAC_SIZE, iv.getData(), 0, IV_SIZE);
        _context.aes().decrypt(_data, _packet.getOffset() + MAC_SIZE + IV_SIZE, _data, _packet.getOffset() + MAC_SIZE + IV_SIZE, cipherKey, iv.getData(), _packet.getLength() - MAC_SIZE - IV_SIZE);
        _ivCache.release(iv);
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(64);
        buf.append(_packet.getLength());
        buf.append(" byte packet with ");
        buf.append(_packet.getAddress().getHostAddress()).append(":");
        buf.append(_packet.getPort());
        return buf.toString();
    }
    
    
    public static UDPPacket acquire(I2PAppContext ctx) {
        if (CACHE) {
            synchronized (_packetCache) {
                if (_packetCache.size() > 0) {
                    UDPPacket rv = (UDPPacket)_packetCache.remove(0);
                    rv._context = ctx;
                    rv._log = ctx.logManager().getLog(UDPPacket.class);
                    rv.resetBegin();
                    Arrays.fill(rv._data, (byte)0x00);
                    return rv;
                }
            }
        }
        return new UDPPacket(ctx);
    }
    
    public void release() {
        _dataCache.release(_dataBuf);
        if (!CACHE) return;
        synchronized (_packetCache) {
            _packet.setLength(0);
            _packet.setPort(1);
            if (_packetCache.size() <= 64)
                _packetCache.add(this);
        }
    }
}
