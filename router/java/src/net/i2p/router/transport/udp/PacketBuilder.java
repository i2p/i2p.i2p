package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
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
    private UDPTransport _transport;
    
    private static final ByteCache _ivCache = ByteCache.getInstance(64, UDPPacket.IV_SIZE);
    private static final ByteCache _hmacCache = ByteCache.getInstance(64, Hash.HASH_LENGTH);
    private static final ByteCache _blockCache = ByteCache.getInstance(64, 16);

    /** we only talk to people of the right version */
    static final int PROTOCOL_VERSION = 0;
    
    public PacketBuilder(I2PAppContext ctx, UDPTransport transport) {
        _context = ctx;
        _transport = transport;
        _log = ctx.logManager().getLog(PacketBuilder.class);
        _context.statManager().createRateStat("udp.packetAuthTime", "How long it takes to encrypt and MAC a packet for sending", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.packetAuthTimeSlow", "How long it takes to encrypt and MAC a packet for sending (when its slow)", "udp", UDPTransport.RATES);
    }
    
    public UDPPacket buildPacket(OutboundMessageState state, int fragment, PeerState peer) {
        return buildPacket(state, fragment, peer, null, null);
    }
    /**
     * @param ackIdsRemaining list of messageIds (Long) that should be acked by this packet.  
     *                        The list itself is passed by reference, and if a messageId is
     *                        transmitted and the sender does not want the ID to be included
     *                        in subsequent acks, it should be removed from the list.  NOTE:
     *                        right now this does NOT remove the IDs, which means it assumes
     *                        that the IDs will be transmitted potentially multiple times,
     *                        and should otherwise be removed from the list.
     * @param partialACKsRemaining list of messageIds (ACKBitfield) that should be acked by this packet.  
     *                        The list itself is passed by reference, and if a messageId is
     *                        included, it should be removed from the list.
     */
    public UDPPacket buildPacket(OutboundMessageState state, int fragment, PeerState peer, List ackIdsRemaining, List partialACKsRemaining) {
        UDPPacket packet = UDPPacket.acquire(_context, false);

        StringBuilder msg = null;
        boolean acksIncluded = false;
        if (_log.shouldLog(Log.INFO)) {
            msg = new StringBuilder(128);
            msg.append("Send to ").append(peer.getRemotePeer().toBase64());
            msg.append(" msg ").append(state.getMessageId()).append(":").append(fragment);
            if (fragment == state.getFragmentCount() - 1)
                msg.append("*");
        }
        
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int start = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        int off = start;
        
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
        // we should in theory only include explicit ACKs if the expected packet size
        // is under the MTU, but for now, since the # of packets acked is so few (usually
        // just one or two), and since the packets are so small anyway, an additional five
        // or ten bytes doesn't hurt.
        if ( (ackIdsRemaining != null) && (ackIdsRemaining.size() > 0) )
            data[off] |= UDPPacket.DATA_FLAG_EXPLICIT_ACK;
        if ( (partialACKsRemaining != null) && (partialACKsRemaining.size() > 0) )
            data[off] |= UDPPacket.DATA_FLAG_ACK_BITFIELDS;
        off++;

        if ( (ackIdsRemaining != null) && (ackIdsRemaining.size() > 0) ) {
            DataHelper.toLong(data, off, 1, ackIdsRemaining.size());
            off++;
            for (int i = 0; i < ackIdsRemaining.size(); i++) {
            //while (ackIdsRemaining.size() > 0) {
                Long ackId = (Long)ackIdsRemaining.get(i);//(Long)ackIdsRemaining.remove(0);
                DataHelper.toLong(data, off, 4, ackId.longValue());
                off += 4;        
                if (msg != null) // logging it
                    msg.append(" full ack: ").append(ackId.longValue());
                acksIncluded = true;
            }
        }

        if ( (partialACKsRemaining != null) && (partialACKsRemaining.size() > 0) ) {
            int origNumRemaining = partialACKsRemaining.size();
            int numPartialOffset = off;
            // leave it blank for now, since we could skip some
            off++;
            for (int i = 0; i < partialACKsRemaining.size(); i++) {
                ACKBitfield bitfield = (ACKBitfield)partialACKsRemaining.get(i);
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
                partialACKsRemaining.remove(i);
                if (msg != null) // logging it
                    msg.append(" partial ack: ").append(bitfield);
                acksIncluded = true;
                i--;
            }
            // now jump back and fill in the number of bitfields *actually* included
            DataHelper.toLong(data, numPartialOffset, 1, origNumRemaining - partialACKsRemaining.size());
        }
        
        if ( (msg != null) && (acksIncluded) )
            _log.debug(msg.toString());
        
        DataHelper.toLong(data, off, 1, 1); // only one fragment in this message
        off++;
        
        DataHelper.toLong(data, off, 4, state.getMessageId());
        off += 4;
        
        data[off] |= fragment << 1;
        if (fragment == state.getFragmentCount() - 1)
            data[off] |= 1; // isLast
        off++;
        
        int size = state.fragmentSize(fragment);
        if (size < 0) {
            packet.release();
            return null;
        }
        DataHelper.toLong(data, off, 2, size);
        data[off] &= (byte)0x3F; // 2 highest bits are reserved
        off += 2;
        
        int sizeWritten = state.writeFragment(data, off, fragment);
        if (sizeWritten != size) {
            _log.error("Size written: " + sizeWritten + " but size: " + size 
                       + " for fragment " + fragment + " of " + state.getMessageId());
            packet.release();
            return null;
        } else if (_log.shouldLog(Log.DEBUG))
            _log.debug("Size written: " + sizeWritten + " for fragment " + fragment 
                       + " of " + state.getMessageId());
        size = sizeWritten;
        if (size < 0) {
            packet.release();
            return null;
        }
        off += size;

        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        int padSize = 16 - (off % 16);
        if (padSize > 0) {
            ByteArray block = _blockCache.acquire();
            _context.random().nextBytes(block.getData());
            System.arraycopy(block.getData(), 0, data, off, padSize);
            _blockCache.release(block);
            off += padSize;
        }
        packet.getPacket().setLength(off);
        authenticate(packet, peer.getCurrentCipherKey(), peer.getCurrentMACKey());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        
        if (_log.shouldLog(Log.INFO)) {
            _log.info(msg.toString());
        }
        
        return packet;
    }
    
    // We use this for keepalive purposes.
    // It doesn't generate a reply, but that's ok.
    public UDPPacket buildPing(PeerState peer) {
        return buildACK(peer, new ArrayList(0));
    }

    private static final int ACK_PRIORITY = 1;
    
    /**
     * @param ackBitfields list of ACKBitfield instances to either fully or partially ACK
     */
    public UDPPacket buildACK(PeerState peer, List ackBitfields) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        
        StringBuilder msg = null;
        if (_log.shouldLog(Log.DEBUG)) {
            msg = new StringBuilder(128);
            msg.append("building ACK packet to ").append(peer.getRemotePeer().toBase64().substring(0,6));
        }

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
                    if (msg != null) // logging it
                        msg.append(" full ack: ").append(bf.getMessageId());
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
                
                if (msg != null) // logging it
                    msg.append(" partial ack: ").append(bitfield);
            }
        }
        
        DataHelper.toLong(data, off, 1, 0); // no fragments in this message
        off++;
        
        if (msg != null)
            _log.debug(msg.toString());
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
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
        UDPPacket packet = UDPPacket.acquire(_context, false);
        
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(state.getSentIP());
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId().toString());
            packet.release();
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
        
        byte sentIP[] = state.getSentIP();
        if ( (sentIP == null) || (sentIP.length <= 0) || ( (_transport != null) && (!_transport.isValid(sentIP)) ) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did our sent IP become invalid? " + state);
            state.fail();
            packet.release();
            return null;
        }
        // now for the body
        System.arraycopy(state.getSentY(), 0, data, off, state.getSentY().length);
        off += state.getSentY().length;
        DataHelper.toLong(data, off, 1, sentIP.length);
        off += 1;
        System.arraycopy(sentIP, 0, data, off, sentIP.length);
        off += sentIP.length;
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
            StringBuilder buf = new StringBuilder(128);
            buf.append("Sending sessionCreated:");
            buf.append(" AliceIP: ").append(Base64.encode(sentIP));
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
        authenticate(packet, ourIntroKey, ourIntroKey, iv);
        setTo(packet, to, state.getSentPort());
        _ivCache.release(iv);
        packet.setMessageType(53);
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
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte toIP[] = state.getSentIP();
        if ( (_transport !=null) && (!_transport.isValid(toIP)) ) {
            packet.release();
            return null;
        }
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(toIP);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId().toString());
            packet.release();
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
        System.arraycopy(toIP, 0, data, off, state.getSentIP().length);
        off += toIP.length;
        DataHelper.toLong(data, off, 2, state.getSentPort());
        off += 2;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        authenticate(packet, state.getIntroKey(), state.getIntroKey());
        setTo(packet, to, state.getSentPort());
        packet.setMessageType(52);
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
        UDPPacket packet = UDPPacket.acquire(_context, false);
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(state.getSentIP());
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId().toString());
            packet.release();
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
            authenticate(packet, state.getCipherKey(), state.getMACKey());
        } else {
            // nothing more to add beyond the identity fragment, though we can
            // pad here if we want.  maybe randomized?

            // pad up so we're on the encryption boundary
            if ( (off % 16) != 0)
                off += 16 - (off % 16);
            packet.getPacket().setLength(off);
            authenticate(packet, state.getCipherKey(), state.getMACKey());
        } 
        
        setTo(packet, to, state.getSentPort());
        packet.setMessageType(51);
        return packet;
    }

    
    /** 
     * full flag info for a peerTest message.  this can be fixed, 
     * since we never rekey on test, and don't need any extended options
     */
    private static final byte PEER_TEST_FLAG_BYTE = (UDPPacket.PAYLOAD_TYPE_TEST << 4);

    /**
     * Build a packet as if we are Alice and we either want Bob to begin a 
     * peer test or Charlie to finish a peer test.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildPeerTestFromAlice(InetAddress toIP, int toPort, SessionKey toIntroKey, long nonce, SessionKey aliceIntroKey) {
        return buildPeerTestFromAlice(toIP, toPort, toIntroKey, toIntroKey, nonce, aliceIntroKey);
    }
    public UDPPacket buildPeerTestFromAlice(InetAddress toIP, int toPort, SessionKey toCipherKey, SessionKey toMACKey, long nonce, SessionKey aliceIntroKey) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = PEER_TEST_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Bob with time = " + new Date(now*1000));
        off += 4;
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        DataHelper.toLong(data, off, 1, 0); // neither Bob nor Charlie need Alice's IP from her
        off++;
        DataHelper.toLong(data, off, 2, 0); // neither Bob nor Charlie need Alice's port from her
        off += 2;
        System.arraycopy(aliceIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        authenticate(packet, toCipherKey, toMACKey);
        setTo(packet, toIP, toPort);
        packet.setMessageType(50);
        return packet;
    }

    /**
     * Build a packet as if we are either Bob or Charlie and we are helping test Alice.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildPeerTestToAlice(InetAddress aliceIP, int alicePort, SessionKey aliceIntroKey, SessionKey charlieIntroKey, long nonce) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = PEER_TEST_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Alice with time = " + new Date(now*1000));
        off += 4;
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        byte ip[] = aliceIP.getAddress();
        DataHelper.toLong(data, off, 1, ip.length);
        off++;
        System.arraycopy(ip, 0, data, off, ip.length);
        off += ip.length;
        DataHelper.toLong(data, off, 2, alicePort);
        off += 2;
        System.arraycopy(charlieIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        authenticate(packet, aliceIntroKey, aliceIntroKey);
        setTo(packet, aliceIP, alicePort);
        packet.setMessageType(49);
        return packet;
    }

    /**
     * Build a packet as if we are Bob sending Charlie a packet to help test Alice.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildPeerTestToCharlie(InetAddress aliceIP, int alicePort, SessionKey aliceIntroKey, long nonce, 
                                            InetAddress charlieIP, int charliePort, 
                                            SessionKey charlieCipherKey, SessionKey charlieMACKey) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = PEER_TEST_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Charlie with time = " + new Date(now*1000));
        off += 4;
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        byte ip[] = aliceIP.getAddress();
        DataHelper.toLong(data, off, 1, ip.length);
        off++;
        System.arraycopy(ip, 0, data, off, ip.length);
        off += ip.length;
        DataHelper.toLong(data, off, 2, alicePort);
        off += 2;
        System.arraycopy(aliceIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        authenticate(packet, charlieCipherKey, charlieMACKey);
        setTo(packet, charlieIP, charliePort);
        packet.setMessageType(48);
        return packet;
    }
    
    /**
     * Build a packet as if we are Charlie sending Bob a packet verifying that we will help test Alice.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildPeerTestToBob(InetAddress bobIP, int bobPort, InetAddress aliceIP, int alicePort, SessionKey aliceIntroKey, long nonce, SessionKey bobCipherKey, SessionKey bobMACKey) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = PEER_TEST_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Bob with time = " + new Date(now*1000));
        off += 4;
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        byte ip[] = aliceIP.getAddress();
        DataHelper.toLong(data, off, 1, ip.length);
        off++;
        System.arraycopy(ip, 0, data, off, ip.length);
        off += ip.length;
        DataHelper.toLong(data, off, 2, alicePort);
        off += 2;
        System.arraycopy(aliceIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        authenticate(packet, bobCipherKey, bobMACKey);
        setTo(packet, bobIP, bobPort);
        packet.setMessageType(47);
        return packet;
    }
    
    /** 
     * full flag info for a relay request message.  this can be fixed, 
     * since we never rekey on relay request, and don't need any extended options
     */
    private static final byte PEER_RELAY_REQUEST_FLAG_BYTE = (UDPPacket.PAYLOAD_TYPE_RELAY_REQUEST << 4);

    // specify these if we know what our external receive ip/port is and if its different
    // from what bob is going to think
    private byte[] getOurExplicitIP() { return null; }
    private int getOurExplicitPort() { return 0; }
    
    /** build intro packets for each of the published introducers */
    @SuppressWarnings("static-access")
    public UDPPacket[] buildRelayRequest(UDPTransport transport, OutboundEstablishState state, SessionKey ourIntroKey) {
        UDPAddress addr = state.getRemoteAddress();
        int count = addr.getIntroducerCount();
        if (count <= 0)
            return new UDPPacket[0];
        UDPPacket rv[] = new UDPPacket[count];
        for (int i = 0; i < count; i++) {
            InetAddress iaddr = addr.getIntroducerHost(i);
            int iport = addr.getIntroducerPort(i);
            byte ikey[] = addr.getIntroducerKey(i);
            long tag = addr.getIntroducerTag(i);
            if ( (ikey == null) || (iport <= 0) || (iaddr == null) || (tag <= 0) ) {
                if (_log.shouldLog(_log.WARN))
                    _log.warn("Cannot build a relay request to " + state.getRemoteIdentity().calculateHash().toBase64() 
                               + ", as their UDP address is invalid: addr=" + addr + " index=" + i);
                continue;
            }
            if (transport.isValid(iaddr.getAddress()))
                rv[i] = buildRelayRequest(iaddr, iport, ikey, tag, ourIntroKey, state.getIntroNonce(), true);
        }
        return rv;
    }
    
    public UDPPacket buildRelayRequest(InetAddress introHost, int introPort, byte introKey[], long introTag, SessionKey ourIntroKey, long introNonce, boolean encrypt) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        byte ourIP[] = getOurExplicitIP();
        int ourPort = getOurExplicitPort();
        
        // header
        data[off] = PEER_RELAY_REQUEST_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending intro relay request to " + introHost + ":" + introPort); // + " regarding " + state.getRemoteIdentity().calculateHash().toBase64());
        off += 4;
        
        // now for the body
        DataHelper.toLong(data, off, 4, introTag);
        off += 4;
        if (ourIP != null) {
            DataHelper.toLong(data, off, 1, ourIP.length);
            off++;
            System.arraycopy(ourIP, 0, data, off, ourIP.length);
            off += ourIP.length;
        } else {
            DataHelper.toLong(data, off, 1, 0);
            off++;
        }
        
        DataHelper.toLong(data, off, 2, ourPort);
        off += 2;
        
        // challenge...
        DataHelper.toLong(data, off, 1, 0);
        off++;
        off += 0; // *cough*
        
        System.arraycopy(ourIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("wrote alice intro key: " + Base64.encode(data, off-SessionKey.KEYSIZE_BYTES, SessionKey.KEYSIZE_BYTES) 
                      + " with nonce " + introNonce + " size=" + (off+4 + (16 - (off+4)%16))
                      + " and data: " + Base64.encode(data, 0, off));
        
        DataHelper.toLong(data, off, 4, introNonce);
        off += 4;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        if (encrypt)
            authenticate(packet, new SessionKey(introKey), new SessionKey(introKey));
        setTo(packet, introHost, introPort);
        packet.setMessageType(46);
        return packet;
    }

    /** 
     * full flag info for a relay intro message.  this can be fixed, 
     * since we never rekey on relay request, and don't need any extended options
     */
    private static final byte PEER_RELAY_INTRO_FLAG_BYTE = (UDPPacket.PAYLOAD_TYPE_RELAY_INTRO << 4);
    
    public UDPPacket buildRelayIntro(RemoteHostId alice, PeerState charlie, UDPPacketReader.RelayRequestReader request) {// LINT -- Exporting non-public type through public API
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = PEER_RELAY_INTRO_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending intro to " + charlie + " for " + alice);
        off += 4;
        
        // now for the body
        byte ip[] = alice.getIP();
        DataHelper.toLong(data, off, 1, ip.length);
        off++;
        System.arraycopy(ip, 0, data, off, ip.length);
        off += ip.length;
        DataHelper.toLong(data, off, 2, alice.getPort());
        off += 2;
        
        int sz = request.readChallengeSize();
        DataHelper.toLong(data, off, 1, sz);
        off++;
        if (sz > 0) {
            request.readChallengeSize(data, off);
            off += sz;
        }
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        authenticate(packet, charlie.getCurrentCipherKey(), charlie.getCurrentMACKey());
        setTo(packet, charlie.getRemoteIPAddress(), charlie.getRemotePort());
        packet.setMessageType(45);
        return packet;
    }

    /** 
     * full flag info for a relay response message.  this can be fixed, 
     * since we never rekey on relay response, and don't need any extended options
     */
    private static final byte PEER_RELAY_RESPONSE_FLAG_BYTE = (UDPPacket.PAYLOAD_TYPE_RELAY_RESPONSE << 4);
    
    public UDPPacket buildRelayResponse(RemoteHostId alice, PeerState charlie, long nonce, SessionKey aliceIntroKey) {// LINT -- Exporting non-public type through public API
        InetAddress aliceAddr = null;
        try {
            aliceAddr = InetAddress.getByAddress(alice.getIP());
        } catch (UnknownHostException uhe) {
            return null;
        }
        
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        data[off] = PEER_RELAY_RESPONSE_FLAG_BYTE;
        off++;
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(data, off, 4, now);
        off += 4;
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending relay response to " + alice + " for " + charlie + " with alice's intro key " + aliceIntroKey.toBase64());

        // now for the body
        byte charlieIP[] = charlie.getRemoteIP();
        DataHelper.toLong(data, off, 1, charlieIP.length);
        off++;
        System.arraycopy(charlieIP, 0, data, off, charlieIP.length);
        off += charlieIP.length;
        DataHelper.toLong(data, off, 2, charlie.getRemotePort());
        off += 2;
        
        byte aliceIP[] = alice.getIP();
        DataHelper.toLong(data, off, 1, aliceIP.length);
        off++;
        System.arraycopy(aliceIP, 0, data, off, aliceIP.length);
        off += aliceIP.length;
        DataHelper.toLong(data, off, 2, alice.getPort());
        off += 2;
        
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        if ( (off % 16) != 0)
            off += 16 - (off % 16);
        packet.getPacket().setLength(off);
        authenticate(packet, aliceIntroKey, aliceIntroKey);
        setTo(packet, aliceAddr, alice.getPort());
        packet.setMessageType(44);
        return packet;
    }
    
    public UDPPacket buildHolePunch(UDPPacketReader reader) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        int ipSize = reader.getRelayIntroReader().readIPSize();
        byte ip[] = new byte[ipSize];
        reader.getRelayIntroReader().readIP(ip, 0);
        int port = reader.getRelayIntroReader().readPort();
        
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("IP for alice to hole punch to is invalid", uhe);
            packet.release();
            return null;
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending relay hole punch to " + to + ":" + port);

        // the packet is empty and does not need to be authenticated, since
        // its just for hole punching
        packet.getPacket().setLength(0);
        setTo(packet, to, port);
        
        packet.setMessageType(43);
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
     *     HMAC-SHA256(payload || IV || (payloadLength ^ protocolVersion), macKey)[0:15]
     *
     * @param packet prepared packet with the first 32 bytes empty and a length
     *               whose size is mod 16
     * @param cipherKey key to encrypt the payload 
     * @param macKey key to generate the, er, MAC
     * @param iv IV to deliver
     */
    private void authenticate(UDPPacket packet, SessionKey cipherKey, SessionKey macKey, ByteArray iv) {
        long before = System.currentTimeMillis();
        int encryptOffset = packet.getPacket().getOffset() + UDPPacket.IV_SIZE + UDPPacket.MAC_SIZE;
        int encryptSize = packet.getPacket().getLength() - UDPPacket.IV_SIZE - UDPPacket.MAC_SIZE - packet.getPacket().getOffset();
        byte data[] = packet.getPacket().getData();
        _context.aes().encrypt(data, encryptOffset, data, encryptOffset, cipherKey, iv.getData(), encryptSize);
        
        // ok, now we need to prepare things for the MAC, which requires reordering
        int off = packet.getPacket().getOffset();
        System.arraycopy(data, encryptOffset, data, off, encryptSize);
        off += encryptSize;
        System.arraycopy(iv.getData(), 0, data, off, UDPPacket.IV_SIZE);
        off += UDPPacket.IV_SIZE;
        DataHelper.toLong(data, off, 2, encryptSize ^ PROTOCOL_VERSION);
        
        int hmacOff = packet.getPacket().getOffset();
        int hmacLen = encryptSize + UDPPacket.IV_SIZE + 2;
        //Hash hmac = _context.hmac().calculate(macKey, data, hmacOff, hmacLen);
        ByteArray ba = _hmacCache.acquire();
        _context.hmac().calculate(macKey, data, hmacOff, hmacLen, ba.getData(), 0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Authenticating " + packet.getPacket().getLength() +
                       "\nIV: " + Base64.encode(iv.getData()) +
                       "\nraw mac: " + Base64.encode(ba.getData()) +
                       "\nMAC key: " + macKey.toBase64());
        // ok, now lets put it back where it belongs...
        System.arraycopy(data, hmacOff, data, encryptOffset, encryptSize);
        //System.arraycopy(hmac.getData(), 0, data, hmacOff, UDPPacket.MAC_SIZE);
        System.arraycopy(ba.getData(), 0, data, hmacOff, UDPPacket.MAC_SIZE);
        System.arraycopy(iv.getData(), 0, data, hmacOff + UDPPacket.MAC_SIZE, UDPPacket.IV_SIZE);
        _hmacCache.release(ba);
        long timeToAuth = System.currentTimeMillis() - before;
        _context.statManager().addRateData("udp.packetAuthTime", timeToAuth, timeToAuth);
        if (timeToAuth > 100)
            _context.statManager().addRateData("udp.packetAuthTimeSlow", timeToAuth, timeToAuth);
    }
}
