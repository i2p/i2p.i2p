package net.i2p.client.streaming.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Log;

/**
 * This contains solely the data that goes out on the wire,
 * including the local and remote port which is embedded in
 * the I2CP overhead, not in the packet itself.
 * This is the class used for inbound packets.
 * For local state saved for outbound packets, see the PacketLocal extension.
 *
 * <p>
 *
 * Contain a single packet transferred as part of a streaming connection.  
 * The data format is as follows:<ul>
 * <li>{@link #getSendStreamId sendStreamId} [4 byte value]</li>
 * <li>{@link #getReceiveStreamId receiveStreamId} [4 byte value]</li>
 * <li>{@link #getSequenceNum sequenceNum} [4 byte unsigned integer]</li>
 * <li>{@link #getAckThrough ackThrough} [4 byte unsigned integer]</li>
 * <li>number of NACKs [1 byte unsigned integer]</li>
 * <li>that many {@link #getNacks NACKs}</li>
 * <li>{@link #getResendDelay resendDelay} [1 byte integer]</li>
 * <li>flags [2 byte value]</li>
 * <li>option data size [2 byte integer]</li>
 * <li>option data specified by those flags [0 or more bytes]</li>
 * <li>payload [remaining packet size]</li>
 * </ul>
 *
 * <p>The flags field above specifies some metadata about the packet, and in
 * turn may require certain additional data to be included.  The flags are
 * as follows (with any data structures specified added to the options area
 * in the given order):</p><ol>
 * <li>{@link #FLAG_SYNCHRONIZE}: no option data</li>
 * <li>{@link #FLAG_CLOSE}: no option data</li>
 * <li>{@link #FLAG_RESET}: no option data</li>
 * <li>{@link #FLAG_SIGNATURE_INCLUDED}: {@link net.i2p.data.Signature}</li>
 * <li>{@link #FLAG_SIGNATURE_REQUESTED}: no option data</li>
 * <li>{@link #FLAG_FROM_INCLUDED}: {@link net.i2p.data.Destination}</li>
 * <li>{@link #FLAG_DELAY_REQUESTED}: 2 byte integer</li>
 * <li>{@link #FLAG_MAX_PACKET_SIZE_INCLUDED}: 2 byte integer</li>
 * <li>{@link #FLAG_PROFILE_INTERACTIVE}: no option data</li>
 * <li>{@link #FLAG_ECHO}: no option data</li>
 * <li>{@link #FLAG_NO_ACK}: no option data - this appears to be unused, we always ack, even for the first packet</li>
 * </ol>
 *
 * <p>If the signature is included, it uses the Destination's DSA key 
 * to sign the entire header and payload with the space in the options 
 * for the signature being set to all zeroes.</p>
 *
 * <p>If the sequenceNum is 0 and the SYN is not set, this is a plain ACK 
 * packet that should not be ACKed</p>
 *
 * NOTE: All setters unsynchronized.
 *
 */
class Packet {
    protected final I2PSession _session;
    private long _sendStreamId;
    private long _receiveStreamId;
    private long _sequenceNum;
    private long _ackThrough;
    protected long _nacks[];
    private int _resendDelay;
    private int _flags;
    private ByteArray _payload;
    // the next four are set only if the flags say so
    protected Signature _optionSignature;
    protected Destination _optionFrom;
    private int _optionDelay;
    private int _optionMaxSize;
    private int _localPort;
    private int _remotePort;
    
    /** 
     * The receiveStreamId will be set to this when the packet doesn't know 
     * what ID will be assigned by the remote peer (aka this is the initial
     * synchronize packet)
     *
     */
    public static final long STREAM_ID_UNKNOWN = 0l;
    
    public static final long MAX_STREAM_ID = 0xffffffffl;
    
    /**
     * This packet is creating a new socket connection (if the receiveStreamId
     * is STREAM_ID_UNKNOWN) or it is acknowledging a request to 
     * create a connection and in turn is accepting the socket.
     *
     */
    public static final int FLAG_SYNCHRONIZE = (1 << 0);
    /**
     * The sender of this packet will not be sending any more payload data.
     */
    public static final int FLAG_CLOSE = (1 << 1);
    /**
     * This packet is being sent to signify that the socket does not exist 
     * (or, if in response to an initial synchronize packet, that the 
     * connection was refused).
     *
     */
    public static final int FLAG_RESET = (1 << 2);
    /**
     * This packet contains a DSA signature from the packet's sender.  This 
     * signature is within the packet options.  All synchronize packets must
     * have this flag set.
     *
     */
    public static final int FLAG_SIGNATURE_INCLUDED = (1 << 3);
    /**
     * This packet wants the recipient to include signatures on subsequent
     * packets sent to the creator of this packet.
     */
    public static final int FLAG_SIGNATURE_REQUESTED = (1 << 4);
    /**
     * This packet includes the full I2P destination of the packet's sender.
     * The initial synchronize packet must have this flag set.
     */
    public static final int FLAG_FROM_INCLUDED = (1 << 5);
    /**
     * This packet includes an explicit request for the recipient to delay
     * sending any packets with data for a given amount of time.
     *
     */
    public static final int FLAG_DELAY_REQUESTED = (1 << 6);
    /**
     * This packet includes a request that the recipient not send any 
     * subsequent packets with payloads greater than a specific size.
     * If not set and no prior value was delivered, the maximum value 
     * will be assumed (approximately 32KB).
     *
     */
    public static final int FLAG_MAX_PACKET_SIZE_INCLUDED = (1 << 7);
    /**
     * If set, this packet is travelling as part of an interactive flow,
     * meaning it is more lag sensitive than throughput sensitive.  aka
     * send data ASAP rather than waiting around to send full packets.
     *
     */
    public static final int FLAG_PROFILE_INTERACTIVE = (1 << 8);
    /**
     * If set, this packet is a ping (if sendStreamId is set) or a 
     * ping reply (if receiveStreamId is set).
     */
    public static final int FLAG_ECHO = (1 << 9);
    
    /** 
     * If set, this packet doesn't really want to ack anything
     */
    public static final int FLAG_NO_ACK = (1 << 10);

    public static final int DEFAULT_MAX_SIZE = 32*1024;
    protected static final int MAX_DELAY_REQUEST = 65535;
    public static final int MIN_DELAY_CHOKE = 60001;
    public static final int SEND_DELAY_CHOKE = 61000;

    /**
     *  Does no initialization.
     *  See readPacket() for inbound packets, and the setters for outbound packets.
     */
    public Packet(I2PSession session) {
        _session = session;
    }
    
    /** @since 0.9.21 */
    public I2PSession getSession() {
        return _session;
    }

    private boolean _sendStreamIdSet = false;

    /** what stream do we send data to the peer on?
     * @return stream ID we use to send data
     */
    public long getSendStreamId() { return _sendStreamId; }

    public void setSendStreamId(long id) { 
        // allow resetting to the same id (race)
        if ( (_sendStreamIdSet) && (_sendStreamId > 0) && _sendStreamId != id)
            throw new RuntimeException("Send stream ID already set [" + _sendStreamId + ", " + id + "]");
        _sendStreamIdSet = true;
        _sendStreamId = id; 
    }
    
    private boolean _receiveStreamIdSet = false;

    /** 
     * stream the replies should be sent on.  this should be 0 if the
     * connection is still being built.
     * @return stream ID we use to get data, zero if the connection is still being built.
     */
    public long getReceiveStreamId() { return _receiveStreamId; }

    public void setReceiveStreamId(long id) { 
        // allow resetting to the same id (race)
        if ( (_receiveStreamIdSet) && (_receiveStreamId > 0) && _receiveStreamId != id) 
            throw new RuntimeException("Receive stream ID already set [" + _receiveStreamId + ", " + id + "]");
        _receiveStreamIdSet = true;
        _receiveStreamId = id; 
    }
    
    /** 0-indexed sequence number for this Packet in the sendStream
     * @return 0-indexed sequence number for current Packet in current sendStream
     */
    public long getSequenceNum() { return _sequenceNum; }
    public void setSequenceNum(long num) { _sequenceNum = num; }
    
    /** 
     * The highest packet sequence number that received
     * on the receiveStreamId.  This field is ignored on the initial
     * connection packet (where receiveStreamId is the unknown id) or
     * if FLAG_NO_ACK is set.
     *
     * @return The highest packet sequence number received on receiveStreamId, or -1 if FLAG_NO_ACK
     */
    public long getAckThrough() { 
        if (isFlagSet(FLAG_NO_ACK))
            return -1;
        else
            return _ackThrough; 
    }

    /** 
     * @param id if &lt; 0, sets FLAG_NO_ACK
     */
    public void setAckThrough(long id) { 
        if (id < 0)
            setFlag(FLAG_NO_ACK);
        _ackThrough = id; 
    }
    
    /**
     * List of packet sequence numbers below the getAckThrough() value
     * have not been received.  this may be null.
     *
     * @return List of packet sequence numbers not ACKed, or null if there are none.
     */
    public long[] getNacks() { return _nacks; }
    public void setNacks(long nacks[]) { _nacks = nacks; }
    
    /**
     * How long is the creator of this packet going to wait before
     * resending this packet (if it hasn't yet been ACKed).  The 
     * value is seconds since the packet was created.
     *
     * Unused.
     * Broken before release 0.7.8
     * Not to be used without sanitizing for huge values.
     * Setters from options did not divide by 1000, and the options default
     * is 1000, so the value sent in the 1-byte field was always
     * 1000 &amp; 0xff = 0xe8 = 232
     *
     * @return Delay before resending a packet in seconds.
     */
    public int getResendDelay() { return _resendDelay; }

    /**
     *  Unused.
     *  Broken before release 0.7.8
     *  See above
     */
    public void setResendDelay(int numSeconds) { _resendDelay = numSeconds; }
    
    public static final int MAX_PAYLOAD_SIZE = 32*1024;
    
    /** get the actual payload of the message.  may be null
     * @return the payload of the message, null if none.
     */
    public ByteArray getPayload() { return _payload; }

    public void setPayload(ByteArray payload) { 
        _payload = payload; 
        if ( (payload != null) && (payload.getValid() > MAX_PAYLOAD_SIZE) )
            throw new IllegalArgumentException("Too large payload: " + payload.getValid());
    }

    public int getPayloadSize() {
        return (_payload == null ? 0 : _payload.getValid());
    }

    /** does nothing right now */
    public void releasePayload() {
        //_payload = null;
    }

    public ByteArray acquirePayload() {
        _payload = new ByteArray(new byte[Packet.MAX_PAYLOAD_SIZE]);
        return _payload;
    }

    /** is a particular flag set on this packet?
     * @param flag bitmask of any flag(s)
     * @return true if set, false if not.
     */
    public boolean isFlagSet(int flag) { return 0 != (_flags & flag); }

    /**
     *  @param flag bitmask of any flag(s)
     */
    public void setFlag(int flag) { _flags |= flag; }

    /**
     *  @param flag bitmask of any flag(s)
     *  @param set true to set, false to clear
     */
    public void setFlag(int flag, boolean set) { 
        if (set)
            _flags |= flag; 
        else
            _flags &= ~flag;
    }

    private void setFlags(int flags) { _flags = flags; } 

    /**
     * The signature on the packet (only included if the flag for it is set)
     *
     * Warning, may be typed wrong on incoming packets for EdDSA
     * before verifySignature() is called.
     *
     * @return signature on the packet if the flag for signatures is set
     */
    public Signature getOptionalSignature() { return _optionSignature; }

    /** 
     * This also sets flag FLAG_SIGNATURE_INCLUDED
     */
    public void setOptionalSignature(Signature sig) { 
        setFlag(FLAG_SIGNATURE_INCLUDED, sig != null);
        _optionSignature = sig; 
    }

    /** the sender of the packet (only included if the flag for it is set)
     * @return the sending Destination
     */
    public Destination getOptionalFrom() { return _optionFrom; }

    /** 
     * This sets the from field in the packet to the Destination for the session
     * provided in the constructor.
     * This also sets flag FLAG_FROM_INCLUDED
     */
    public void setOptionalFrom() { 
        setFlag(FLAG_FROM_INCLUDED, true);
        _optionFrom = _session.getMyDestination();
    }
    
    /** 
     * How many milliseconds the sender of this packet wants the recipient
     * to wait before sending any more data (only valid if the flag for it is
     * set) 
     * @return How long the sender wants the recipient to wait before sending any more data in ms.
     */
    public int getOptionalDelay() { return _optionDelay; }

    /** 
     * Caller must also call setFlag(FLAG_DELAY_REQUESTED)
     */
    public void setOptionalDelay(int delayMs) {
        if (delayMs > MAX_DELAY_REQUEST)
            _optionDelay = MAX_DELAY_REQUEST;
        else if (delayMs < 0)
            _optionDelay = 0;
        else
            _optionDelay = delayMs; 
    }
    
    /**
     * What is the largest payload the sender of this packet wants to receive?
     *
     * @return Maximum payload size sender can receive (MRU)
     */
    public int getOptionalMaxSize() { return _optionMaxSize; }

    /** 
     * This also sets flag FLAG_MAX_PACKET_SIZE_INCLUDED
     */
    public void setOptionalMaxSize(int numBytes) { 
        setFlag(FLAG_MAX_PACKET_SIZE_INCLUDED, numBytes > 0);
        _optionMaxSize = numBytes; 
    }
    
    /**
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getLocalPort() {
        return _localPort;
    }

    /**
     *  Must be called to change the port, not set by readPacket()
     *  as the port is out-of-band in the I2CP header.
     *  @since 0.8.9
     */
    public void setLocalPort(int port) {
        _localPort = port;
    }
    
    /**
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getRemotePort() {
        return _remotePort;
    }

    /**
     *  Must be called to change the port, not set by readPacket()
     *  as the port is out-of-band in the I2CP header.
     *  @since 0.8.9
     */
    public void setRemotePort(int port) {
        _remotePort = port;
    }

    /**
     * Write the packet to the buffer (starting at the offset) and return
     * the number of bytes written.
     *
     * @param buffer bytes to write to a destination
     * @param offset starting point in the buffer to send
     * @return Count actually written
     * @throws IllegalStateException if there is data missing or otherwise b0rked
     */
    public int writePacket(byte buffer[], int offset) throws IllegalStateException {
        return writePacket(buffer, offset, 0);
    }

    /**
     * @param fakeSigLen if 0, include the real signature in _optionSignature;
     *                   if nonzero, leave space for that many bytes
     */
    protected int writePacket(byte buffer[], int offset, int fakeSigLen) throws IllegalStateException {
        int cur = offset;
        DataHelper.toLong(buffer, cur, 4, (_sendStreamId >= 0 ? _sendStreamId : STREAM_ID_UNKNOWN));
        cur += 4;
        DataHelper.toLong(buffer, cur, 4, (_receiveStreamId >= 0 ? _receiveStreamId : STREAM_ID_UNKNOWN));
        cur += 4;
        DataHelper.toLong(buffer, cur, 4, _sequenceNum > 0 ? _sequenceNum : 0);
        cur += 4;
        DataHelper.toLong(buffer, cur, 4, _ackThrough > 0 ? _ackThrough : 0);
        cur += 4;
        if (_nacks != null) {
            // if max win is ever > 255, limit to 255
            DataHelper.toLong(buffer, cur, 1, _nacks.length);
            cur++;
            for (int i = 0; i < _nacks.length; i++) {
                DataHelper.toLong(buffer, cur, 4, _nacks[i]);
                cur += 4;
            }
        } else {
            DataHelper.toLong(buffer, cur, 1, 0);
            cur++;
        }
        DataHelper.toLong(buffer, cur, 1, _resendDelay > 0 ? _resendDelay : 0);
        cur++;
        DataHelper.toLong(buffer, cur, 2, _flags);
        cur += 2;

        int optionSize = 0;
        if (isFlagSet(FLAG_DELAY_REQUESTED))
            optionSize += 2;
        if (isFlagSet(FLAG_FROM_INCLUDED))
            optionSize += _optionFrom.size();
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED))
            optionSize += 2;
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED)) {
            if (fakeSigLen > 0)
                optionSize += fakeSigLen;
            else if (_optionSignature != null)
                optionSize += _optionSignature.length();
            else
                throw new IllegalStateException();
        }
        
        DataHelper.toLong(buffer, cur, 2, optionSize);
        cur += 2;
        
        if (isFlagSet(FLAG_DELAY_REQUESTED)) {
            DataHelper.toLong(buffer, cur, 2, _optionDelay > 0 ? _optionDelay : 0);
            cur += 2;
        }
        if (isFlagSet(FLAG_FROM_INCLUDED)) {
            cur += _optionFrom.writeBytes(buffer, cur);
        }
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED)) {
            DataHelper.toLong(buffer, cur, 2, _optionMaxSize > 0 ? _optionMaxSize : DEFAULT_MAX_SIZE);
            cur += 2;
        }
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED)) {
            if (fakeSigLen == 0) {
                // we're signing (or validating)
                System.arraycopy(_optionSignature.getData(), 0, buffer, cur, _optionSignature.length());
                cur += _optionSignature.length();
            } else {
                Arrays.fill(buffer, cur, cur + fakeSigLen, (byte)0x0);
                cur += fakeSigLen;
            }
        }
        
        if (_payload != null) {
            try {
                System.arraycopy(_payload.getData(), _payload.getOffset(), buffer, cur, _payload.getValid());
            } catch (ArrayIndexOutOfBoundsException aioobe) {
                String error = "payload.length: " + _payload.getValid() + " buffer.length: " + buffer.length + " cur: " + cur;
                I2PAppContext context = I2PAppContext.getCurrentContext();
                if (context != null) {
                    Log l = context.logManager().getLog(Packet.class);
                    l.log(Log.ERROR,error,aioobe);
                } else {
                    System.err.println(error);
                    aioobe.printStackTrace(System.out);
                }
                throw aioobe;
            }
            cur += _payload.getValid();
        }
                
        return cur - offset;
    }
    
    /**
     * how large would this packet be if we wrote it
     * @return How large the current packet would be
     *
     * @throws IllegalStateException 
     */
    private int writtenSize() {
        //int size = 0;
        //size += 4; // _sendStreamId.length;
        //size += 4; // _receiveStreamId.length;
        //size += 4; // sequenceNum
        //size += 4; // ackThrough
        //    size++; // nacks length
        //size++; // resendDelay
        //size += 2; // flags
        //size += 2; // option size
        int size = 22;

        if (_nacks != null) {
            // if max win is ever > 255, limit to 255
            size += 4 * _nacks.length;
        }

        if (isFlagSet(FLAG_DELAY_REQUESTED))
            size += 2;
        if (isFlagSet(FLAG_FROM_INCLUDED))
            size += _optionFrom.size();
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED))
            size += 2;
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED))
            size += _optionSignature.length();
        
        if (_payload != null) {
            size += _payload.getValid();
        }
        
        return size;
    }
    

    /**
     * Read the packet from the buffer (starting at the offset) and return
     * the number of bytes read.
     *
     * @param buffer packet buffer containing the data
     * @param offset index into the buffer to start readign
     * @param length how many bytes within the buffer past the offset are 
     *               part of the packet?
     *
     * @throws IllegalArgumentException if the data is b0rked
     */
    public void readPacket(byte buffer[], int offset, int length) throws IllegalArgumentException {
        if (buffer.length - offset < length) 
            throw new IllegalArgumentException("len=" + buffer.length + " off=" + offset + " length=" + length);
        if (length < 22) // min header size
            throw new IllegalArgumentException("Too small: len=" + buffer.length);
        int cur = offset;
        setSendStreamId(DataHelper.fromLong(buffer, cur, 4));
        cur += 4;
        setReceiveStreamId(DataHelper.fromLong(buffer, cur, 4));
        cur += 4;
        setSequenceNum(DataHelper.fromLong(buffer, cur, 4));
        cur += 4;
        setAckThrough(DataHelper.fromLong(buffer, cur, 4));
        cur += 4;
        int numNacks = buffer[cur] & 0xff;
        cur++;
        if (length < 22 + numNacks*4)
            throw new IllegalArgumentException("Too small with " + numNacks + " nacks: " + length);
        if (numNacks > 0) {
            long nacks[] = new long[numNacks];
            for (int i = 0; i < numNacks; i++) {
                nacks[i] = DataHelper.fromLong(buffer, cur, 4);
                cur += 4;
            }
            setNacks(nacks);
        } else {
            setNacks(null);
        }
        setResendDelay(buffer[cur] & 0xff);
        cur++;
        setFlags((int)DataHelper.fromLong(buffer, cur, 2));
        cur += 2;
        
        int optionSize = (int)DataHelper.fromLong(buffer, cur, 2);
        cur += 2;
        
        if (length < 22 + numNacks*4 + optionSize)
            throw new IllegalArgumentException("Too small with " + numNacks + " nacks and "
                                               + optionSize + " options: " + length);
        
        int payloadBegin = cur + optionSize;
        int payloadSize = length - payloadBegin;
        if ( (payloadSize < 0) || (payloadSize > MAX_PAYLOAD_SIZE) ) 
            throw new IllegalArgumentException("length: " + length + " offset: " + offset + " begin: " + payloadBegin);
        
        // skip ahead to the payload
        //_payload = new ByteArray(new byte[payloadSize]);
        _payload = new ByteArray(buffer, payloadBegin, payloadSize);
        //System.arraycopy(buffer, payloadBegin, _payload.getData(), 0, payloadSize);
        //_payload.setValid(payloadSize);
        //_payload.setOffset(0);
        
        // ok now lets go back and deal with the options
        if (isFlagSet(FLAG_DELAY_REQUESTED)) {
            setOptionalDelay((int)DataHelper.fromLong(buffer, cur, 2));
            cur += 2;
        }
        if (isFlagSet(FLAG_FROM_INCLUDED)) {
            ByteArrayInputStream bais = new ByteArrayInputStream(buffer, cur, length - cur);
            try {
                Destination optionFrom = Destination.create(bais);
                cur += optionFrom.size();
                _optionFrom = optionFrom;
            } catch (IOException ioe) {
                throw new IllegalArgumentException("Bad from field", ioe);
            } catch (DataFormatException dfe) {
                throw new IllegalArgumentException("Bad from field", dfe);
            }
        }
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED)) {
            setOptionalMaxSize((int)DataHelper.fromLong(buffer, cur, 2));
            cur += 2;
        }
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED)) {
            Signature optionSignature;
            Destination from = getOptionalFrom();
            if (from != null) {
                optionSignature = new Signature(from.getSigningPublicKey().getType());
            } else {
                // super cheat for now, look for correct type,
                // assume no more options. If we add to the options
                // we will have to ask the manager.
                // We will get this wrong for Ed25519, same length as P256...
                // See verifySignature() below where we will recast the signature to
                // the correct type if necessary
                int siglen = payloadBegin - cur;
                SigType type = null;
                for (SigType t : SigType.values()) {
                    if (t.getSigLen() == siglen) {
                        type = t;
                        break;
                    }
                }
                if (type == null) {
                    if (siglen < Signature.SIGNATURE_BYTES)
                        throw new IllegalArgumentException("unknown sig type len=" + siglen);
                    // Hope it's the default type with some unknown options following;
                    // if not the sig will fail later
                    type = SigType.DSA_SHA1;
                    siglen = Signature.SIGNATURE_BYTES;
                }
                optionSignature = new Signature(type);
            }
            byte buf[] = new byte[optionSignature.length()];
            System.arraycopy(buffer, cur, buf, 0, buf.length);
            optionSignature.setData(buf);
            setOptionalSignature(optionSignature);
            cur += buf.length;
        }
    }
    
    /**
     * Determine whether the signature on the data is valid.  
     *
     * @param ctx Application context
     * @param from the Destination the data came from
     * @param buffer data to validate with signature
     * @return true if the signature exists and validates against the data,
     *         false otherwise.
     */
    public boolean verifySignature(I2PAppContext ctx, Destination from, byte buffer[]) {
        if (!isFlagSet(FLAG_SIGNATURE_INCLUDED)) return false;
        if (_optionSignature == null) return false;
        // prevent receiveNewSyn() ... !active ... sendReset() ... verifySignature ... NPE
        if (from == null) return false;
        
        int size = writtenSize();
        
        if (buffer == null)
            buffer = new byte[size];
        SigningPublicKey spk = from.getSigningPublicKey();
        SigType type = spk.getType();
        if (type == null) {
            Log l = ctx.logManager().getLog(Packet.class);
            if (l.shouldLog(Log.WARN))
                l.warn("Unknown sig type in " + from + " cannot verify " + toString());
            return false;
        }
        int written = writePacket(buffer, 0, type.getSigLen());
        if (written != size) {
            ctx.logManager().getLog(Packet.class).error("Written " + written + " size " + size + " for " + toString(), new Exception("moo"));
            return false;
        }

        // Fixup of signature if we guessed wrong on the type in readPacket(), which could happen
        // on a close or reset packet where we have a signature without a FROM
        if (type != _optionSignature.getType() &&
            type.getSigLen() == _optionSignature.length())
            _optionSignature = new Signature(type, _optionSignature.getData());

        boolean ok = ctx.dsa().verifySignature(_optionSignature, buffer, 0, size, spk);
        if (!ok) {
            Log l = ctx.logManager().getLog(Packet.class);
            if (l.shouldLog(Log.WARN))
                l.warn("Signature failed on " + toString(), new Exception("moo"));
            //if (false) {
            //    l.error(Base64.encode(buffer, 0, size));
            //    l.error("Signature: " + Base64.encode(_optionSignature.getData()));
            //}
        }
        return ok;
    }
    
    @Override
    public String toString() {
        StringBuilder str = formatAsString();
        return str.toString();
    }
    
    protected StringBuilder formatAsString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append(toId(_sendStreamId));
        buf.append('/');
        buf.append(toId(_receiveStreamId)).append(':');
        if (_sequenceNum != 0 || isFlagSet(FLAG_SYNCHRONIZE))
            buf.append(" #").append(_sequenceNum);
        // else an ack-only packet
        //if (_sequenceNum < 10) 
        //    buf.append(" \t"); // so the tab lines up right
        //else
        //    buf.append('\t');
        toFlagString(buf);
        if ( (_payload != null) && (_payload.getValid() > 0) )
            buf.append(" data: ").append(_payload.getValid());
        return buf;
    }
    
    static final String toId(long id) {
        return Base64.encode(DataHelper.toLong(4, id)).replace("==", "");
    }
    
    private final void toFlagString(StringBuilder buf) {
        if (isFlagSet(FLAG_NO_ACK))
            buf.append(" NO_ACK");
        else
            buf.append(" ACK ").append(getAckThrough());
        if (_nacks != null) {
            buf.append(" NACK");
            for (int i = 0; i < _nacks.length; i++) {
                buf.append(' ').append(_nacks[i]);
            }
        }
        if (isFlagSet(FLAG_CLOSE)) buf.append(" CLOSE");
        if (isFlagSet(FLAG_DELAY_REQUESTED)) buf.append(" DELAY ").append(_optionDelay);
        if (isFlagSet(FLAG_ECHO)) buf.append(" ECHO");
        if (isFlagSet(FLAG_FROM_INCLUDED)) buf.append(" FROM ").append(_optionFrom.size());
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED)) buf.append(" MS ").append(_optionMaxSize);
        if (isFlagSet(FLAG_PROFILE_INTERACTIVE)) buf.append(" INTERACTIVE");
        if (isFlagSet(FLAG_RESET)) buf.append(" RESET");
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED)) {
            if (_optionSignature != null)
                buf.append(" SIG ").append(_optionSignature.length());
            else
                buf.append(" (to be signed)");
        }
        if (isFlagSet(FLAG_SIGNATURE_REQUESTED)) buf.append(" SIGREQ");
        if (isFlagSet(FLAG_SYNCHRONIZE)) buf.append(" SYN");
    }

    /** Generate a pcap/tcpdump-compatible format,
     *  so we can use standard debugging tools.
     */
    public void logTCPDump(Connection con) {
            try {
                I2PSocketManagerFull.pcapWriter.write(this, con);
            } catch (IOException ioe) {
            }
    }
}
