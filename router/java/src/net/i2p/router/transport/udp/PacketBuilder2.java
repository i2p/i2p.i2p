package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.ChaCha20;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.udp.PacketBuilder.Fragment;
import net.i2p.router.transport.udp.SSU2Payload.Block;
import static net.i2p.router.transport.udp.SSU2Util.*;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 *  SSU2 only
 *
 *  @since 0.9.54
 */
class PacketBuilder2 {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    
    /**
     *  For debugging and stats only - does not go out on the wire.
     *  These are chosen to be higher than the highest I2NP message type,
     *  as a data packet is set to the underlying I2NP message type.
     */
    static final int TYPE_FIRST = 62;
    static final int TYPE_ACK = TYPE_FIRST;
    static final int TYPE_PUNCH = 63;
    static final int TYPE_TCB = 67;
    static final int TYPE_TBC = 68;
    static final int TYPE_TTA = 69;
    static final int TYPE_TFA = 70;
    static final int TYPE_CONF = 71;
    static final int TYPE_SREQ = 72;
    static final int TYPE_CREAT = 73;

    /** IPv4 only */
    public static final int IP_HEADER_SIZE = PacketBuilder.IP_HEADER_SIZE;
    /** Same for IPv4 and IPv6 */
    public static final int UDP_HEADER_SIZE = PacketBuilder.UDP_HEADER_SIZE;

    /** 74 */
    public static final int MIN_DATA_PACKET_OVERHEAD = IP_HEADER_SIZE + UDP_HEADER_SIZE + DATA_HEADER_SIZE + MAC_LEN;

    public static final int IPV6_HEADER_SIZE = PacketBuilder.IPV6_HEADER_SIZE;
    /** 94 */
    public static final int MIN_IPV6_DATA_PACKET_OVERHEAD = IPV6_HEADER_SIZE + UDP_HEADER_SIZE + DATA_HEADER_SIZE + MAC_LEN;

/// FIXME
    private static final int MAX_IDENTITY_FRAGMENT_SIZE = 1280 - (MIN_DATA_PACKET_OVERHEAD + KEY_LEN + MAC_LEN);

    private static final int ABSOLUTE_MAX_ACK_RANGES = 512;

    /* Higher than all other OutNetMessage priorities, but still droppable,
     * and will be shown in the codel.UDP-Sender.drop.500 stat.
     */
    static final int PRIORITY_HIGH = 550;
    private static final int PRIORITY_LOW = OutNetMessage.PRIORITY_LOWEST;
    
    /**
     *  No state, all methods are thread-safe.
     *
     *  @param transport may be null for unit testing only
     */
    public PacketBuilder2(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _transport = transport;
        _log = ctx.logManager().getLog(PacketBuilder2.class);
        // all createRateStat in UDPTransport
    }

    /**
     *  Will a packet to 'peer' that already has 'numFragments' fragments
     *  totalling 'curDataSize' bytes fit another fragment of size 'newFragSize' ??
     *
     *  This doesn't leave anything for acks.
     *
     *  @param numFragments &gt;= 1
     */
    public static int getMaxAdditionalFragmentSize(PeerState peer, int numFragments, int curDataSize) {
        int available = peer.getMTU() - curDataSize;
        if (peer.isIPv6())
            available -= MIN_IPV6_DATA_PACKET_OVERHEAD;
        else
            available -= MIN_DATA_PACKET_OVERHEAD;
        // OVERHEAD above includes 1 * FRAGMENT+HEADER_SIZE;
        // this adds for the others, plus the new one.
        available -= numFragments * FIRST_FRAGMENT_HEADER_SIZE;
        return available;
    }

    /**
     * This builds a data packet (PAYLOAD_TYPE_DATA).
     * See the methods below for the other message types.
     *
     * Note that while the UDP message spec allows for more than one fragment in a message,
     * this method writes exactly one fragment.
     * For no fragments use buildAck().
     *
     * @return null on error
     */
    public UDPPacket buildPacket(OutboundMessageState state, int fragment, PeerState2 peer) {
        List<Fragment> frags = Collections.singletonList(new Fragment(state, fragment));
        return buildPacket(frags, peer);
    }

    /*
     *  Multiple fragments
     *
     */
    public UDPPacket buildPacket(List<Fragment> fragments, PeerState2 peer) {
        // calculate data size
        int numFragments = fragments.size();
        int dataSize = 0;
        int priority = PRIORITY_LOW;
        for (int i = 0; i < numFragments; i++) {
            Fragment frag = fragments.get(i);
            OutboundMessageState state = frag.state;
            int pri = state.getPriority();
            if (pri > priority)
                priority = pri;
            int fragment = frag.num;
            int sz = state.fragmentSize(fragment);
            dataSize += sz;
            dataSize += SSU2Payload.BLOCK_HEADER_SIZE;
            if (fragment > 0)
                dataSize += 5; // frag + msg ID for follow-on blocks
        }

        // calculate size available for acks
        int currentMTU = peer.getMTU();
        int availableForAcks = currentMTU - dataSize;
        int ipHeaderSize;
        if (peer.isIPv6()) {
            availableForAcks -= MIN_IPV6_DATA_PACKET_OVERHEAD;
            ipHeaderSize = IPV6_HEADER_SIZE;
        } else {
            availableForAcks -= MIN_DATA_PACKET_OVERHEAD;
            ipHeaderSize = IP_HEADER_SIZE;
        }

        // make the packet
        long pktNum = peer.getNextPacketNumber();
        UDPPacket packet = buildShortPacketHeader(peer.getSendConnID(), pktNum, DATA_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = SHORT_HEADER_SIZE;

        // ok, now for the body...
        // +2 for acks and padding
        List<Block> blocks = new ArrayList<Block>(fragments.size() + 2);
        int sizeWritten = 0;

        // add the acks
        if (availableForAcks >= SSU2Payload.BLOCK_HEADER_SIZE + 5) {
            int maxRanges = Math.min((availableForAcks - (SSU2Payload.BLOCK_HEADER_SIZE + 5)) / 2, ABSOLUTE_MAX_ACK_RANGES);
            Block block = peer.getReceivedMessages().toAckBlock(maxRanges);
            if (block != null) {
                blocks.add(block);
                int sz = block.getTotalLength();
                off += sz;
                sizeWritten += sz;
            }
        }
        
        // now write each fragment
        for (int i = 0; i < numFragments; i++) {
            Fragment frag = fragments.get(i);
            OutboundMessageState state = frag.state;
            int fragment = frag.num;
            int count = state.getFragmentCount();
            Block block;
            if (fragment == 0) {
                if (count == 1)
                    block = new SSU2Payload.I2NPBlock(state);
                else
                    block = new SSU2Payload.FirstFragBlock(state);
            } else {
                block = new SSU2Payload.FollowFragBlock(state, fragment);
            }
            blocks.add(block);
            int sz = block.getTotalLength();
            off += sz;
            sizeWritten += sz;
        }
        Block block = getPadding(sizeWritten, peer.getMTU());
        if (block != null) {
            blocks.add(block);
            int sz = block.getTotalLength();
            off += sz;
            sizeWritten += sz;
        }
        SSU2Payload.writePayload(data, SHORT_HEADER_SIZE, blocks);
        pkt.setLength(off);

        encryptDataPacket(packet, peer.getSendCipher(), pktNum, peer.getSendHeaderEncryptKey1(), peer.getSendHeaderEncryptKey2());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        
        // FIXME ticket #2675
        // the packet could have been built before the current mtu got lowered, so
        // compare to LARGE_MTU
        // Also happens on switch between IPv4 and IPv6
        if (_log.shouldWarn()) {
            int maxMTU = peer.isIPv6() ? PeerState.MAX_IPV6_MTU : PeerState.LARGE_MTU;
            if (off + (ipHeaderSize + UDP_HEADER_SIZE) > maxMTU) {
                _log.warn("Size is " + off + " for " + packet +
                       " data size " + dataSize +
                       " pkt size " + (off + (ipHeaderSize + UDP_HEADER_SIZE)) +
                       " MTU " + currentMTU +
                       " Fragments: " + DataHelper.toString(fragments), new Exception());
            }
        }
        
        packet.setPriority(priority);
        if (fragments.isEmpty())
            peer.getAckedMessages().set(pktNum); // not ack-eliciting
        else
            peer.fragmentsSent(pktNum, fragments);
        return packet;
    }
    
    /**
     * A DATA packet with padding only.
     * We use this for keepalive purposes.
     */
    public UDPPacket buildPing(PeerState2 peer) {
        long pktNum = peer.getNextPacketNumber();
        UDPPacket packet = buildShortPacketHeader(peer.getSendConnID(), pktNum, DATA_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = SHORT_HEADER_SIZE;
        Block block = getPadding(0, 1280);
        List<Block> blocks = Collections.singletonList(block);
        off += block.getTotalLength();
        SSU2Payload.writePayload(data, SHORT_HEADER_SIZE, blocks);
        pkt.setLength(off);
        encryptDataPacket(packet, peer.getSendCipher(), pktNum, peer.getSendHeaderEncryptKey1(), peer.getSendHeaderEncryptKey2());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        packet.setPriority(PRIORITY_LOW);
        peer.getAckedMessages().set(pktNum); // not ack-eliciting
        return packet;
    }

    /**
     *  Build the ack packet.
     *  An ack packet is just a data packet with no data.
     *  See buildPacket() for format.
     *
     *
     */
    public UDPPacket buildACK(PeerState2 peer) {
        return buildPacket(Collections.emptyList(), peer);
    }
    
    /**
     * Build a new SessionRequest packet for the given peer, encrypting it 
     * as necessary.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildTokenRequestPacket(OutboundEstablishState2 state) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        UDPPacket packet = buildLongPacketHeader(state.getSendConnID(), n, SESSION_REQUEST_FLAG_BYTE,
                                                 state.getRcvConnID(), 0);
        DatagramPacket pkt = packet.getPacket();

        byte toIP[] = state.getSentIP();
        if (!_transport.isValid(toIP)) {
            packet.release();
            return null;
        }
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(toIP);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId());
            packet.release();
            return null;
        }
        
        pkt.setLength(LONG_HEADER_SIZE);
        byte[] introKey = state.getSendHeaderEncryptKey1();
        encryptTokenRequest(packet, introKey, n, introKey, introKey);
        state.requestSent();
        setTo(packet, to, state.getSentPort());
        packet.setMessageType(TYPE_SREQ);
        packet.setPriority(PRIORITY_HIGH);
        return packet;
    }
    
    /**
     * Build a new SessionRequest packet for the given peer, encrypting it 
     * as necessary.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildSessionRequestPacket(OutboundEstablishState2 state) {
        UDPPacket packet = buildLongPacketHeader(state.getSendConnID(), 0, SESSION_REQUEST_FLAG_BYTE,
                                                 state.getRcvConnID(), state.getToken());
        DatagramPacket pkt = packet.getPacket();

        byte toIP[] = state.getSentIP();
        if (!_transport.isValid(toIP)) {
            packet.release();
            return null;
        }
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(toIP);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId());
            packet.release();
            return null;
        }
        
        pkt.setLength(LONG_HEADER_SIZE);
        byte[] introKey = state.getSendHeaderEncryptKey1();
        encryptSessionRequest(packet, state.getHandshakeState(), introKey, introKey, state.needIntroduction());
        state.requestSent();
        setTo(packet, to, state.getSentPort());
        packet.setMessageType(TYPE_SREQ);
        packet.setPriority(PRIORITY_HIGH);
        return packet;
    }
    
    /**
     * Build a new SessionCreated packet for the given peer, encrypting it 
     * as necessary.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildSessionCreatedPacket(InboundEstablishState2 state) {
        UDPPacket packet = buildLongPacketHeader(state.getSendConnID(), 0, SESSION_CREATED_FLAG_BYTE,
                                                 state.getRcvConnID(), state.getToken());
        DatagramPacket pkt = packet.getPacket();
        
        byte sentIP[] = state.getSentIP();
        pkt.setLength(LONG_HEADER_SIZE);
        int port = state.getSentPort();
        encryptSessionCreated(packet, state.getHandshakeState(), state.getSendHeaderEncryptKey1(),
                              state.getSendHeaderEncryptKey2(), state.getSentRelayTag(), state.getNextToken(),
                              sentIP, port);
        state.createdPacketSent();
        pkt.setSocketAddress(state.getSentAddress());
        packet.setMessageType(TYPE_CREAT);
        packet.setPriority(PRIORITY_HIGH);
        return packet;
    }
    
    /**
     * Build a new Retry packet for the given peer, encrypting it 
     * as necessary.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildRetryPacket(InboundEstablishState2 state) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        UDPPacket packet = buildLongPacketHeader(state.getSendConnID(), n, RETRY_FLAG_BYTE,
                                                 state.getRcvConnID(), state.getToken());
        DatagramPacket pkt = packet.getPacket();
        
        byte sentIP[] = state.getSentIP();
        pkt.setLength(LONG_HEADER_SIZE);
        int port = state.getSentPort();
        encryptRetry(packet, state.getSendHeaderEncryptKey1(), n, state.getSendHeaderEncryptKey1(),
                     state.getSendHeaderEncryptKey2(),
                     sentIP, port);
        state.retryPacketSent();
        pkt.setSocketAddress(state.getSentAddress());
        packet.setMessageType(TYPE_CREAT);
        packet.setPriority(PRIORITY_HIGH);
        return packet;
    }
    
    /**
     * Build a new series of SessionConfirmed packets for the given peer, 
     * encrypting it as necessary.
     *
     * Note that while a SessionConfirmed could in theory be fragmented,
     * in practice a RouterIdentity is 387 bytes and a single fragment is 512 bytes max,
     * so it will never be fragmented.
     * 
     * @return ready to send packets, or null if there was a problem
     * 
     * TODO: doesn't really return null, and caller doesn't handle null return
     * (null SigningPrivateKey should cause this?)
     * Should probably return null if buildSessionConfirmedPacket() returns null for any fragment
     */
    public UDPPacket[] buildSessionConfirmedPackets(OutboundEstablishState2 state, RouterInfo ourInfo) {
        boolean gzip = false;
        byte info[] = ourInfo.toByteArray();
        int mtu = state.getMTU();
        byte toIP[] = state.getSentIP();
        // 20 + 8 + 16 + 32 + 16 + 16 + 3 + 2 = 113
        // 40 + 8 + 16 + 32 + 16 + 16 + 3 + 2 = 133
        int overhead = (toIP.length == 16 ? IPV6_HEADER_SIZE : IP_HEADER_SIZE) +
                       UDP_HEADER_SIZE + SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN + MAC_LEN +
                       SSU2Payload.BLOCK_HEADER_SIZE + 2;  // RIBlock flags
        int max = mtu - overhead;

        int numFragments = info.length / max;
        if (numFragments * max != info.length)
            numFragments++;

        if (numFragments > 1) {
            byte[] gzipped = DataHelper.compress(info, 0, info.length, DataHelper.MAX_COMPRESSION);
            if (gzipped.length < info.length) {
                if (_log.shouldWarn())
                    _log.warn("Gzipping RI, max is " + max + " size was " + info.length + " size now " + gzipped.length);
                gzip = true;
                info = gzipped;
                numFragments = info.length / max;
                if (numFragments * max != info.length)
                    numFragments++;
            }
        }

        int len;
        if (numFragments > 1) {
            if (_log.shouldWarn())
                _log.warn("RI size " + info.length + " requires " + numFragments + " packets");
            len = max;
        } else {
            len = info.length;
        }


        UDPPacket packets[] = new UDPPacket[numFragments];
        packets[0] = buildSessionConfirmedPacket(state, numFragments, info, len, gzip);
        if (numFragments > 1) {
            // get PeerState from OES
            for (int i = 1; i < numFragments; i++) {
                //packets[i] = buildSessionConfirmedPacket(state, i, numFragments, info, gzip);
            }
            // TODO numFragments > 1 requires shift to data phase
            throw new IllegalArgumentException("TODO");
        }
        state.confirmedPacketsSent();
        return packets;
    }

    /**
     * Build a new SessionConfirmed packet for the given peer
     * 
     * @return ready to send packet, or null if there was a problem
     */
    private UDPPacket buildSessionConfirmedPacket(OutboundEstablishState2 state, int numFragments, byte ourInfo[], int len, boolean gzip) {
        UDPPacket packet = buildShortPacketHeader(state.getSendConnID(), 1, SESSION_CONFIRMED_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();

        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(state.getSentIP());
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId());
            packet.release();
            return null;
        }
        
        pkt.setLength(SHORT_HEADER_SIZE);
        SSU2Payload.RIBlock block = new SSU2Payload.RIBlock(ourInfo,  0, len,
                                                            false, gzip, 0, numFragments);
        encryptSessionConfirmed(packet, state.getHandshakeState(), state.getMTU(),
                                state.getSendHeaderEncryptKey1(), state.getSendHeaderEncryptKey2(), block, state.getNextToken());
        setTo(packet, to, state.getSentPort());
        packet.setMessageType(TYPE_CONF);
        packet.setPriority(PRIORITY_HIGH);
        return packet;
    }

    /**
     * Build a packet as if we are Alice and we either want Bob to begin a 
     * peer test or Charlie to finish a peer test.
     * 
     * @return ready to send packet, or null if there was a problem
     */
/*
    public UDPPacket buildPeerTestFromAlice(InetAddress toIP, int toPort, SessionKey toIntroKey, long nonce, SessionKey aliceIntroKey) {
        return buildPeerTestFromAlice(toIP, toPort, toIntroKey, toIntroKey, nonce, aliceIntroKey);
    }
*/

    /**
     * Build a packet as if we are Alice and we either want Bob to begin a 
     * peer test or Charlie to finish a peer test.
     * 
     * @return ready to send packet, or null if there was a problem
     */
/*
    public UDPPacket buildPeerTestFromAlice(InetAddress toIP, int toPort, SessionKey toCipherKey, SessionKey toMACKey,
                                            long nonce, SessionKey aliceIntroKey) {
        UDPPacket packet = buildShortPacketHeader(PEER_TEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = SHORT_HEADER_SIZE;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Bob");
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        data[off++] = 0; // neither Bob nor Charlie need Alice's IP from her
        DataHelper.toLong(data, off, 2, 0); // neither Bob nor Charlie need Alice's port from her
        off += 2;
        System.arraycopy(aliceIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        pkt.setLength(off);
        authenticate(packet, toCipherKey, toMACKey);
        setTo(packet, toIP, toPort);
        packet.setMessageType(TYPE_TFA);
        packet.setPriority(PRIORITY_LOW);
        return packet;
    }
*/

    /**
     * Build a packet as if we are either Bob or Charlie and we are helping test Alice.
     * Not for use as Bob, as of 0.9.52; use in-session cipher/mac keys instead.
     *
     * @return ready to send packet, or null if there was a problem
     */
/*
    public UDPPacket buildPeerTestToAlice(InetAddress aliceIP, int alicePort,
                                          SessionKey aliceIntroKey, SessionKey charlieIntroKey, long nonce) {
        return buildPeerTestToAlice(aliceIP, alicePort, aliceIntroKey, aliceIntroKey, charlieIntroKey, nonce);
    }
*/

    /**
     * Build a packet as if we are either Bob or Charlie and we are helping test Alice.
     * 
     * @param aliceCipherKey the intro key if we are Charlie
     * @param aliceMACKey the intro key if we are Charlie
     * @return ready to send packet, or null if there was a problem
     */
/*
    public UDPPacket buildPeerTestToAlice(InetAddress aliceIP, int alicePort,
                                          SessionKey aliceCipherKey, SessionKey aliceMACKey,
                                          SessionKey charlieIntroKey, long nonce) {
        UDPPacket packet = buildShortPacketHeader(PEER_TEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = SHORT_HEADER_SIZE;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Alice");
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        byte ip[] = aliceIP.getAddress();
        data[off++] = (byte) ip.length;
        System.arraycopy(ip, 0, data, off, ip.length);
        off += ip.length;
        DataHelper.toLong(data, off, 2, alicePort);
        off += 2;
        System.arraycopy(charlieIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        pkt.setLength(off);
        authenticate(packet, aliceCipherKey, aliceMACKey);
        setTo(packet, aliceIP, alicePort);
        packet.setMessageType(TYPE_TTA);
        packet.setPriority(PRIORITY_LOW);
        return packet;
    }
*/

    /**
     * Build a packet as if we are Bob sending Charlie a packet to help test Alice.
     * 
     * @return ready to send packet, or null if there was a problem
     */
/*
    public UDPPacket buildPeerTestToCharlie(InetAddress aliceIP, int alicePort, SessionKey aliceIntroKey, long nonce, 
                                            InetAddress charlieIP, int charliePort, 
                                            SessionKey charlieCipherKey, SessionKey charlieMACKey) {
        UDPPacket packet = buildShortPacketHeader(PEER_TEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = SHORT_HEADER_SIZE;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Charlie");
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        byte ip[] = aliceIP.getAddress();
        data[off++] = (byte) ip.length;
        System.arraycopy(ip, 0, data, off, ip.length);
        off += ip.length;
        DataHelper.toLong(data, off, 2, alicePort);
        off += 2;
        System.arraycopy(aliceIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        pkt.setLength(off);
        authenticate(packet, charlieCipherKey, charlieMACKey);
        setTo(packet, charlieIP, charliePort);
        packet.setMessageType(TYPE_TBC);
        packet.setPriority(PRIORITY_LOW);
        return packet;
    }
*/
    
    /**
     * Build a packet as if we are Charlie sending Bob a packet verifying that we will help test Alice.
     * 
     * @return ready to send packet, or null if there was a problem
     */
/*
    public UDPPacket buildPeerTestToBob(InetAddress bobIP, int bobPort, InetAddress aliceIP, int alicePort,
                                        SessionKey aliceIntroKey, long nonce,
                                        SessionKey bobCipherKey, SessionKey bobMACKey) {
        UDPPacket packet = buildShortPacketHeader(PEER_TEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = SHORT_HEADER_SIZE;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Bob");
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        byte ip[] = aliceIP.getAddress();
        data[off++] = (byte) ip.length;
        System.arraycopy(ip, 0, data, off, ip.length);
        off += ip.length;
        DataHelper.toLong(data, off, 2, alicePort);
        off += 2;
        System.arraycopy(aliceIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        pkt.setLength(off);
        authenticate(packet, bobCipherKey, bobMACKey);
        setTo(packet, bobIP, bobPort);
        packet.setMessageType(TYPE_TCB);
        packet.setPriority(PRIORITY_LOW);
        return packet;
    }
*/

    /**
     *  Creates an empty unauthenticated packet for hole punching.
     *  Parameters must be validated previously.
     */
    public UDPPacket buildHolePunch(InetAddress to, int port) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending relay hole punch to " + to + ":" + port);

        // the packet is empty and does not need to be authenticated, since
        // its just for hole punching
        packet.getPacket().setLength(0);
        setTo(packet, to, port);
        
        packet.setMessageType(TYPE_PUNCH);
        packet.setPriority(PRIORITY_HIGH);
        return packet;
    }
    
    /**
     *  @param pktNum 0 - 0xFFFFFFFF
     *  @return a packet with the first 32 bytes filled in
     */
    private UDPPacket buildLongPacketHeader(long destID, long pktNum, byte type, long srcID, long token) {
        if (_log.shouldDebug())
            _log.debug("Building long header destID " + destID + " pkt num " + pktNum + " type " + type + " srcID " + srcID + " token " + token);
        UDPPacket packet = buildShortPacketHeader(destID, pktNum, type);
        byte data[] = packet.getPacket().getData();
        data[13] = PROTOCOL_VERSION;
        data[14] = (byte) _context.router().getNetworkID();
        DataHelper.toLong8(data, 16, srcID);
        DataHelper.toLong8(data, 24, token);
        return packet;
    }
    
    /**
     *  @param pktNum 0 - 0xFFFFFFFF
     *  @return a packet with the first 16 bytes filled in
     */
    private UDPPacket buildShortPacketHeader(long destID, long pktNum, byte type) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte) 0);
        DataHelper.toLong8(data, 0, destID);
        DataHelper.toLong(data, 8, 4, pktNum);
        data[12] = type;
        return packet;
    }

    private static void setTo(UDPPacket packet, InetAddress ip, int port) {
        DatagramPacket pkt = packet.getPacket();
        pkt.setAddress(ip);
        pkt.setPort(port);
    }

    /**
     *  @param packet containing only 32 byte header
     */
    private void encryptSessionRequest(UDPPacket packet, HandshakeState state,
                                       byte[] hdrKey1, byte[] hdrKey2, boolean needIntro) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        try {
            if (_log.shouldDebug())
                _log.debug("After start: " + state);
            List<Block> blocks = new ArrayList<Block>(3);
            Block block = new SSU2Payload.DateTimeBlock(_context);
            int len = block.getTotalLength();
            blocks.add(block);
            if (needIntro) {
                block = new SSU2Payload.RelayTagRequestBlock();
                len += block.getTotalLength();
                blocks.add(block);
            }
            // plenty of room
            block = getPadding(len, 1280);
            len += block.getTotalLength();
            blocks.add(block);

            // If we skip past where the ephemeral key will be, we can
            // use the packet for the plaintext and Noise will symmetric encrypt in-place
            SSU2Payload.writePayload(data, off + LONG_HEADER_SIZE + KEY_LEN, blocks);
            state.start();
            if (_log.shouldDebug())
                _log.debug("State after start: " + state);
            state.mixHash(data, off, LONG_HEADER_SIZE);
            if (_log.shouldDebug())
                _log.debug("State after mixHash 1: " + state);
            state.writeMessage(data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE + KEY_LEN, len);
            pkt.setLength(pkt.getLength() + KEY_LEN + len + MAC_LEN);
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 1 out", re);
            throw re;
        } catch (GeneralSecurityException gse) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 1 out", gse);
            throw new RuntimeException("Bad msg 1 out", gse);
        }
        if (_log.shouldDebug())
            _log.debug("After msg 1: " + state + '\n' + net.i2p.util.HexDump.dump(data, off, pkt.getLength()));
        SSU2Header.encryptHandshakeHeader(packet, hdrKey1, hdrKey2);
        if (_log.shouldDebug())
            _log.debug("Hdr key 1: " + Base64.encode(hdrKey1) + " Hdr key 2: " + Base64.encode(hdrKey2));
    }

    /**
     *  @param packet containing only 32 byte header
     */
    private void encryptSessionCreated(UDPPacket packet, HandshakeState state,
                                       byte[] hdrKey1, byte[] hdrKey2, long relayTag, long token, byte[] ip, int port) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        try {
            List<Block> blocks = new ArrayList<Block>(4);
            Block block = new SSU2Payload.DateTimeBlock(_context);
            int len = block.getTotalLength();
            blocks.add(block);
            block = new SSU2Payload.AddressBlock(ip, port);
            len += block.getTotalLength();
            blocks.add(block);
            if (relayTag > 0) {
                block = new SSU2Payload.RelayTagBlock(relayTag);
                len += block.getTotalLength();
                blocks.add(block);
            }
            if (token > 0) {
                block = new SSU2Payload.NewTokenBlock(token, _context.clock().now() + 6*24*60*60*1000L);
                len += block.getTotalLength();
                blocks.add(block);
            }
            // plenty of room
            block = getPadding(len, 1280);
            len += block.getTotalLength();
            blocks.add(block);

            // If we skip past where the ephemeral key will be, we can
            // use the packet for the plaintext and Noise will symmetric encrypt in-place
            SSU2Payload.writePayload(data, off + LONG_HEADER_SIZE + KEY_LEN, blocks);

            state.mixHash(data, off, LONG_HEADER_SIZE);
            if (_log.shouldDebug())
                _log.debug("State after mixHash 2: " + state);
            state.writeMessage(data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE + KEY_LEN, len);
            pkt.setLength(pkt.getLength() + KEY_LEN + len + MAC_LEN);
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 2 out", re);
            throw re;
        } catch (GeneralSecurityException gse) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 2 out", gse);
            throw new RuntimeException("Bad msg 2 out", gse);
        }
        if (_log.shouldDebug())
            _log.debug("After msg 2: " + state);
        SSU2Header.encryptHandshakeHeader(packet, hdrKey1, hdrKey2);
    }

    /**
     *  @param packet containing only 32 byte header
     */
    private void encryptRetry(UDPPacket packet, byte[] chachaKey, long n,
                              byte[] hdrKey1, byte[] hdrKey2, byte[] ip, int port) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        try {
            List<Block> blocks = new ArrayList<Block>(4);
            Block block = new SSU2Payload.DateTimeBlock(_context);
            int len = block.getTotalLength();
            blocks.add(block);
            block = new SSU2Payload.AddressBlock(ip, port);
            len += block.getTotalLength();
            blocks.add(block);
            // plenty of room
            block = getPadding(len, 1280);
            len += block.getTotalLength();
            blocks.add(block);
            byte[] payload = new byte[len];
            SSU2Payload.writePayload(payload, 0, blocks);

            ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
            chacha.initializeKey(chachaKey, 0);
            chacha.setNonce(n);
            chacha.encryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len);

            pkt.setLength(pkt.getLength() + len + MAC_LEN);
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad retry msg out", re);
            throw re;
        } catch (GeneralSecurityException gse) {
            if (!_log.shouldWarn())
                _log.error("Bad retry msg out", gse);
            throw new RuntimeException("Bad retry msg out", gse);
        }
        SSU2Header.encryptLongHeader(packet, hdrKey1, hdrKey2);
    }

    /**
     *  @param packet containing only 32 byte header
     */
    private void encryptTokenRequest(UDPPacket packet, byte[] chachaKey, long n,
                                     byte[] hdrKey1, byte[] hdrKey2) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        try {
            List<Block> blocks = new ArrayList<Block>(4);
            Block block = new SSU2Payload.DateTimeBlock(_context);
            int len = block.getTotalLength();
            blocks.add(block);
            // plenty of room
            block = getPadding(len, 1280);
            len += block.getTotalLength();
            blocks.add(block);
            byte[] payload = new byte[len];
            SSU2Payload.writePayload(payload, 0, blocks);

            ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
            chacha.initializeKey(chachaKey, 0);
            chacha.setNonce(n);
            chacha.encryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE);

            pkt.setLength(pkt.getLength() + len + MAC_LEN);
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad token req msg out", re);
            throw re;
        } catch (GeneralSecurityException gse) {
            if (!_log.shouldWarn())
                _log.error("Bad token req msg out", gse);
            throw new RuntimeException("Bad token req msg out", gse);
        }
        SSU2Header.encryptHandshakeHeader(packet, hdrKey1, hdrKey2);
    }

    /**
     *  @param packet containing only 16 byte header
     */
    private void encryptSessionConfirmed(UDPPacket packet, HandshakeState state, int mtu,
                                         byte[] hdrKey1, byte[] hdrKey2,
                                         SSU2Payload.RIBlock riblock, long token) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        try {
            List<Block> blocks = new ArrayList<Block>(3);
            int len = riblock.getTotalLength();
            blocks.add(riblock);
            if (token > 0) {
                // TODO only if room
                Block block = new SSU2Payload.NewTokenBlock(token, _context.clock().now() + 6*24*60*60*1000L);
                len += block.getTotalLength();
                blocks.add(block);
            }
            Block block = getPadding(len, mtu - 80);
            if (block != null) {
                len += block.getTotalLength();
                blocks.add(block);
            }

            // If we skip past where the static key and 1st MAC will be, we can
            // use the packet for the plaintext and Noise will symmetric encrypt in-place
            SSU2Payload.writePayload(data, off + SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN, blocks);
            state.mixHash(data, off, SHORT_HEADER_SIZE);
            if (_log.shouldDebug())
                _log.debug("State after mixHash 3: " + state);
            state.writeMessage(data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN, len);
            pkt.setLength(pkt.getLength() + KEY_LEN + MAC_LEN + len + MAC_LEN);
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 3 out", re);
            throw re;
        } catch (GeneralSecurityException gse) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 3 out", gse);
            throw new RuntimeException("Bad msg 1 out", gse);
        }
        if (_log.shouldDebug())
            _log.debug("After msg 3: " + state);
        SSU2Header.encryptShortHeader(packet, hdrKey1, hdrKey2);
    }

    /**
     *  @param packet containing 16 byte header and all data with
     *                length set to the end of the data.
     *                This will extend the length by 16 for the MAC.
     */
    private void encryptDataPacket(UDPPacket packet, CipherState chacha, long n,
                                    byte[] hdrKey1, byte[] hdrKey2) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        synchronized(chacha) {
            chacha.setNonce(n);
            try {
                chacha.encryptWithAd(data, off, SHORT_HEADER_SIZE,
                                     data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE, len - SHORT_HEADER_SIZE);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("Bad data msg", e);
            }
        }
        pkt.setLength(len + MAC_LEN);
        SSU2Header.encryptShortHeader(packet, hdrKey1, hdrKey2);
    }

    /**
     *  @param len current length of the packet
     *  @param max max length of the packet
     *  @return null if no room
     */
    private Block getPadding(int len, int max) {
        int maxpadlen = Math.min(max - len, PADDING_MAX) - SSU2Payload.BLOCK_HEADER_SIZE;
        if (maxpadlen < 0)
            return null;
        int padlen;
        if (maxpadlen == 0)
            padlen = 0;
        else
            padlen = _context.random().nextInt(maxpadlen + 1);
        return new SSU2Payload.PaddingBlock(padlen);
    }

    private void writePayload(List<Block> blocks, byte[] data, int off) {
        SSU2Payload.writePayload(data, off, blocks);
    }
}
