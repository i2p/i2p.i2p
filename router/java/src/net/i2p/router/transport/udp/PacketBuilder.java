package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Big ol' class to do all our packet formatting.  The UDPPackets generated are
 * fully authenticated, encrypted, and configured for delivery to the peer. 
 *
 */
public class PacketBuilder {
    private I2PAppContext _context;
    private Log _log;
    
    private static final ByteCache _ivCache = ByteCache.getInstance(64, UDPPacket.IV_SIZE);
    
    public PacketBuilder(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(PacketBuilder.class);
    }
    
    public UDPPacket buildPacket(OutboundMessageState state, int fragment, PeerState peer) {
        UDPPacket packet = UDPPacket.acquire(_context);
        
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] |= (UDPPacket.PAYLOAD_TYPE_DATA << 4);
        // todo: add support for rekeying and extended options
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        off += 4;
        
        // ok, now for the body...
        
        // just always ask for an ACK for now...
        data[off] |= UDPPacket.DATA_FLAG_WANT_REPLY;
        off++;
        
        DataHelper.toLong(data, off, 1, 1); // only one fragment in this message
        off++;
        
        DataHelper.toLong(data, off, 4, state.getMessageId());
        off += 4;
        
        data[off] |= fragment << 1;
        if (fragment == state.getFragmentCount() - 1)
            data[off] |= 1; // isLast
        off++;
        
        int size = state.fragmentSize(fragment);
        if (size < 0)
            return null;
        DataHelper.toLong(data, off, 2, size);
        data[off] &= (byte)3F; // 2 highest bits are reserved
        off += 2;
        
        size = state.writeFragment(data, off, fragment);
        if (size < 0) return null;
        off += size;

        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        packet.setPacketDataLength(off);
        authenticate(packet, peer.getCurrentCipherKey(), peer.getCurrentMACKey());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        return packet;
    }
    
    private static final int ACK_PRIORITY = 1;
    
    /**
     * @param ackBitfields list of ACKBitfield instances to either fully or partially ACK
     */
    public UDPPacket buildACK(PeerState peer, List ackBitfields) {
        UDPPacket packet = UDPPacket.acquire(_context);
        
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] |= (UDPPacket.PAYLOAD_TYPE_DATA << 4);
        // todo: add support for rekeying and extended options
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        off += 4;
        
        int fullACKCount = 0;
        int partialACKCount = 0;
        for (int i = 0; i < ackBitfields.size(); i++) {
            if (((ACKBitfield)ackBitfields.get(i)).receivedComplete())
                fullACKCount++;
            else
                partialACKCount++;
        }
        // ok, now for the body...
        if (fullACKCount > 0)
            data[off] |= UDPPacket.DATA_FLAG_EXPLICIT_ACK;
        if (partialACKCount > 0)
            data[off] |= UDPPacket.DATA_FLAG_ACK_BITFIELDS;
        // add ECN if (peer.getSomethingOrOther())
        off++;
        
        if (fullACKCount > 0) {
            DataHelper.toLong(data, off, 1, fullACKCount);
            off++;
            for (int i = 0; i < ackBitfields.size(); i++) {
                ACKBitfield bf = (ACKBitfield)ackBitfields.get(i);
                if (bf.receivedComplete()) {
                    DataHelper.toLong(data, off, 4, bf.getMessageId());
                    off += 4;
                }
            }
        }
        
        if (partialACKCount > 0) {
            DataHelper.toLong(data, off, 1, partialACKCount);
            off++;
            for (int i = 0; i < ackBitfields.size(); i++) {
                ACKBitfield bitfield = (ACKBitfield)ackBitfields.get(i);
                if (bitfield.receivedComplete()) continue;
                DataHelper.toLong(data, off, 4, bitfield.getMessageId());
                off += 4;
                int bits = bitfield.fragmentCount();
                int size = (bits / 7) + 1;
                for (int curByte = 0; curByte < size; curByte++) {
                    if (curByte + 1 < size)
                        data[off] |= (byte)(1 << 7);
                    
                    for (int curBit = 0; curBit < 7; curBit++) {
                        if (bitfield.received(curBit + 7*curByte))
                            data[off] |= (byte)(1 << curBit);
                    }
                    off++;
                }
            }
        }
        
        DataHelper.toLong(data, off, 1, 0); // no fragments in this message
        off++;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        packet.setPacketDataLength(off);
        authenticate(packet, peer.getCurrentCipherKey(), peer.getCurrentMACKey());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        return packet;
    }
    
    /** 
     * full flag info for a sessionCreated message.  this can be fixed, 
     * since we never rekey on startup, and don't need any extended options
     */
    private static final byte SESSION_CREATED_FLAG_BYTE = (UDPPacket.PAYLOAD_TYPE_SESSION_CREATED << 4);
    
    /**
     * Build a new SessionCreated packet for the given peer, encrypting it 
     * as necessary.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildSessionCreatedPacket(InboundEstablishState state, int externalPort, SessionKey ourIntroKey) {
        UDPPacket packet = UDPPacket.acquire(_context);
        
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(state.getSentIP());
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId().toString());
            return null;
        }

        state.prepareSessionCreated();
        
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = SESSION_CREATED_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        off += 4;
        
        // now for the body
        System.arraycopy(state.getSentY(), 0, data, off, state.getSentY().length);
        off += state.getSentY().length;
        DataHelper.toLong(data, off, 1, state.getSentIP().length);
        off += 1;
        System.arraycopy(state.getSentIP(), 0, data, off, state.getSentIP().length);
        off += state.getSentIP().length;
        DataHelper.toLong(data, off, 2, state.getSentPort());
        off += 2;
        DataHelper.toLong(data, off, 4, state.getSentRelayTag());
        off += 4;
        DataHelper.toLong(data, off, 4, state.getSentSignedOnTime());
        off += 4;
        System.arraycopy(state.getSentSignature().getData(), 0, data, off, Signature.SIGNATURE_BYTES);
        off += Signature.SIGNATURE_BYTES;
        // ok, we need another 8 bytes of random padding
        // (ok, this only gives us 63 bits, not 64)
        long l = _context.random().nextLong();
        if (l < 0) l = 0 - l;
        DataHelper.toLong(data, off, 8, l);
        off += 8;
        
        if (_log.shouldLog(Log.DEBUG)) {
            StringBuffer buf = new StringBuffer(128);
            buf.append("Sending sessionCreated:");
            buf.append(" AliceIP: ").append(Base64.encode(state.getSentIP()));
            buf.append(" AlicePort: ").append(state.getSentPort());
            buf.append(" BobIP: ").append(Base64.encode(state.getReceivedOurIP()));
            buf.append(" BobPort: ").append(externalPort);
            buf.append(" RelayTag: ").append(state.getSentRelayTag());
            buf.append(" SignedOn: ").append(state.getSentSignedOnTime());
            buf.append(" signature: ").append(Base64.encode(state.getSentSignature().getData()));
            buf.append("\nRawCreated: ").append(Base64.encode(data, 0, off)); 
            buf.append("\nsignedTime: ").append(Base64.encode(data, off-8-Signature.SIGNATURE_BYTES-4, 4));
            _log.debug(buf.toString());
        }
        
        // ok, now the full data is in there, but we also need to encrypt
        // the signature, which means we need the IV
        ByteArray iv = _ivCache.acquire();
        _context.random().nextBytes(iv.getData());
        
        int encrWrite = Signature.SIGNATURE_BYTES + 8;
        int sigBegin = off - encrWrite;
        _context.aes().encrypt(data, sigBegin, data, sigBegin, state.getCipherKey(), iv.getData(), encrWrite);
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        packet.setPacketDataLength(off);
        authenticate(packet, ourIntroKey, ourIntroKey, iv);
        setTo(packet, to, state.getSentPort());
        _ivCache.release(iv);
        return packet;
    }
    
    /** 
     * full flag info for a sessionRequest message.  this can be fixed, 
     * since we never rekey on startup, and don't need any extended options
     */
    private static final byte SESSION_REQUEST_FLAG_BYTE = (UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST << 4);
    
    /**
     * Build a new SessionRequest packet for the given peer, encrypting it 
     * as necessary.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildSessionRequestPacket(OutboundEstablishState state) {
        UDPPacket packet = UDPPacket.acquire(_context);
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(state.getSentIP());
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId().toString());
            return null;
        }
        
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = SESSION_REQUEST_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending request with time = " + new Date(now*1000));
        off += 4;
        
        // now for the body
        System.arraycopy(state.getSentX(), 0, data, off, state.getSentX().length);
        off += state.getSentX().length;
        DataHelper.toLong(data, off, 1, state.getSentIP().length);
        off += 1;
        System.arraycopy(state.getSentIP(), 0, data, off, state.getSentIP().length);
        off += state.getSentIP().length;
        DataHelper.toLong(data, off, 2, state.getSentPort());
        off += 2;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        packet.setPacketDataLength(off);
        authenticate(packet, state.getIntroKey(), state.getIntroKey());
        setTo(packet, to, state.getSentPort());
        return packet;
    }

    private static final int MAX_IDENTITY_FRAGMENT_SIZE = 512;
    
    /**
     * Build a new series of SessionConfirmed packets for the given peer, 
     * encrypting it as necessary.
     * 
     * @return ready to send packets, or null if there was a problem
     */
    public UDPPacket[] buildSessionConfirmedPackets(OutboundEstablishState state, RouterIdentity ourIdentity) {
        byte identity[] = ourIdentity.toByteArray();
        int numFragments = identity.length / MAX_IDENTITY_FRAGMENT_SIZE;
        if (numFragments * MAX_IDENTITY_FRAGMENT_SIZE != identity.length)
            numFragments++;
        UDPPacket packets[] = new UDPPacket[numFragments];
        for (int i = 0; i < numFragments; i++)
            packets[i] = buildSessionConfirmedPacket(state, i, numFragments, identity);
        return packets;
    }

    
    /** 
     * full flag info for a sessionConfirmed message.  this can be fixed, 
     * since we never rekey on startup, and don't need any extended options
     */
    private static final byte SESSION_CONFIRMED_FLAG_BYTE = (UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED << 4);
    
    /**
     * Build a new SessionConfirmed packet for the given peer
     * 
     * @return ready to send packets, or null if there was a problem
     */
    public UDPPacket buildSessionConfirmedPacket(OutboundEstablishState state, int fragmentNum, int numFragments, byte identity[]) {
        UDPPacket packet = UDPPacket.acquire(_context);
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(state.getSentIP());
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId().toString());
            return null;
        }
        
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = SESSION_CONFIRMED_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        off += 4;
        
        // now for the body
        data[off] |= fragmentNum << 4;
        data[off] |= (numFragments & 0xF);
        off++;
        
        int curFragSize = MAX_IDENTITY_FRAGMENT_SIZE;
        if (fragmentNum == numFragments-1) {
            if (identity.length % MAX_IDENTITY_FRAGMENT_SIZE != 0)
                curFragSize = identity.length % MAX_IDENTITY_FRAGMENT_SIZE;
        }
        
        DataHelper.toLong(data, off, 2, curFragSize);
        off += 2;
        
        int curFragOffset = fragmentNum * MAX_IDENTITY_FRAGMENT_SIZE;
        System.arraycopy(identity, curFragOffset, data, off, curFragSize);
        off += curFragSize;
        
        if (fragmentNum == numFragments - 1) {
            DataHelper.toLong(data, off, 4, state.getSentSignedOnTime());
            off += 4;
            
            int paddingRequired = 0;
            // we need to pad this so we're at the encryption boundary
            if ( (off + Signature.SIGNATURE_BYTES) % 16 != 0)
                paddingRequired += 16 - ((off + Signature.SIGNATURE_BYTES) % 16);
            
            // add an arbitrary number of 16byte pad blocks too...
            
            for (int i = 0; i < paddingRequired; i++) {
                data[off] = (byte)_context.random().nextInt(255);
                off++;
            }
            
            System.arraycopy(state.getSentSignature().getData(), 0, data, off, Signature.SIGNATURE_BYTES);
            packet.getPacket().setLength(off + Signature.SIGNATURE_BYTES);
            packet.setPacketDataLength(off + Signature.SIGNATURE_BYTES);
            authenticate(packet, state.getCipherKey(), state.getMACKey());
        } else {
            // nothing more to add beyond the identity fragment, though we can
            // pad here if we want.  maybe randomized?

            // pad up so we're on the encryption boundary
            if ( (off % 16) != 0)
                off += 16 - (off % 16);
            packet.getPacket().setLength(off);
            packet.setPacketDataLength(off);
            authenticate(packet, state.getIntroKey(), state.getIntroKey());
        } 
        
        setTo(packet, to, state.getSentPort());
        return packet;
    }

    private void setTo(UDPPacket packet, InetAddress ip, int port) {
        packet.getPacket().setAddress(ip);
        packet.getPacket().setPort(port);
    }
    
    /**
     * Encrypt the packet with the cipher key and a new random IV, generate a 
     * MAC for that encrypted data and IV, and store the result in the packet.
     *
     * @param packet prepared packet with the first 32 bytes empty and a length
     *               whose size is mod 16
     * @param cipherKey key to encrypt the payload 
     * @param macKey key to generate the, er, MAC
     */
    private void authenticate(UDPPacket packet, SessionKey cipherKey, SessionKey macKey) {
        ByteArray iv = _ivCache.acquire();
        _context.random().nextBytes(iv.getData());
        authenticate(packet, cipherKey, macKey, iv);
        _ivCache.release(iv);
    }
    
    /**
     * Encrypt the packet with the cipher key and the given IV, generate a 
     * MAC for that encrypted data and IV, and store the result in the packet.
     * The MAC used is: 
     *     HMAC-SHA256(payload || IV || payloadLength, macKey)[0:15]
     *
     * @param packet prepared packet with the first 32 bytes empty and a length
     *               whose size is mod 16
     * @param cipherKey key to encrypt the payload 
     * @param macKey key to generate the, er, MAC
     * @param iv IV to deliver
     */
    private void authenticate(UDPPacket packet, SessionKey cipherKey, SessionKey macKey, ByteArray iv) {
        int encryptOffset = packet.getPacket().getOffset() + UDPPacket.IV_SIZE + UDPPacket.MAC_SIZE;
        int encryptSize = packet.getPacketDataLength()/*packet.getPacket().getLength()*/ - UDPPacket.IV_SIZE - UDPPacket.MAC_SIZE - packet.getPacket().getOffset();
        byte data[] = packet.getPacket().getData();
        _context.aes().encrypt(data, encryptOffset, data, encryptOffset, cipherKey, iv.getData(), encryptSize);
        
        // ok, now we need to prepare things for the MAC, which requires reordering
        int off = packet.getPacket().getOffset();
        System.arraycopy(data, encryptOffset, data, off, encryptSize);
        off += encryptSize;
        System.arraycopy(iv.getData(), 0, data, off, UDPPacket.IV_SIZE);
        off += UDPPacket.IV_SIZE;
        DataHelper.toLong(data, off, 2, encryptSize);
        
        int hmacOff = packet.getPacket().getOffset();
        int hmacLen = encryptSize + UDPPacket.IV_SIZE + 2;
        Hash hmac = _context.hmac().calculate(macKey, data, hmacOff, hmacLen);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Authenticating " + packet.getPacketDataLength() + // packet.getPacket().getLength() +
                       "\nIV: " + Base64.encode(iv.getData()) +
                       "\nraw mac: " + hmac.toBase64() +
                       "\nMAC key: " + macKey.toBase64());
        // ok, now lets put it back where it belongs...
        System.arraycopy(data, hmacOff, data, encryptOffset, encryptSize);
        System.arraycopy(hmac.getData(), 0, data, hmacOff, UDPPacket.MAC_SIZE);
        System.arraycopy(iv.getData(), 0, data, hmacOff + UDPPacket.MAC_SIZE, UDPPacket.IV_SIZE);
    }
}
