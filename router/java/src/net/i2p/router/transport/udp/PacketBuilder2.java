package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketAddress;
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
import net.i2p.util.HexDump;
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
    static final int TYPE_RESP = 64;
    static final int TYPE_INTRO = 65;
    static final int TYPE_RREQ = 66;
    static final int TYPE_TCB = 67;
    static final int TYPE_TBC = 68;
    static final int TYPE_TTA = 69;
    static final int TYPE_TFA = 70;
    static final int TYPE_CONF = 71;
    static final int TYPE_SREQ = 72;
    static final int TYPE_CREAT = 73;
    static final int TYPE_DESTROY = 74;

    /** IPv4 only */
    public static final int IP_HEADER_SIZE = PacketBuilder.IP_HEADER_SIZE;
    /** Same for IPv4 and IPv6 */
    public static final int UDP_HEADER_SIZE = PacketBuilder.UDP_HEADER_SIZE;

    /** 60 */
    public static final int MIN_DATA_PACKET_OVERHEAD = IP_HEADER_SIZE + UDP_HEADER_SIZE + DATA_HEADER_SIZE + MAC_LEN;

    public static final int IPV6_HEADER_SIZE = PacketBuilder.IPV6_HEADER_SIZE;
    /** 80 */
    public static final int MIN_IPV6_DATA_PACKET_OVERHEAD = IPV6_HEADER_SIZE + UDP_HEADER_SIZE + DATA_HEADER_SIZE + MAC_LEN;

    private static final int ABSOLUTE_MAX_ACK_RANGES = 512;

    /* Higher than all other OutNetMessage priorities, but still droppable,
     * and will be shown in the codel.UDP-Sender.drop.500 stat.
     */
    static final int PRIORITY_HIGH = 550;
    private static final int PRIORITY_LOW = OutNetMessage.PRIORITY_LOWEST;
    
    // every this many packets
    private static final int DATETIME_SEND_FREQUENCY = 256;


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
     *  totalling 'curDataSize' bytes fit another fragment?
     *  This includes the 3 byte block overhead, but NOT the 5 byte followon fragment overhead.
     *
     *  This doesn't leave anything for acks or anything else.
     *
     *  @param numFragments &gt;= 1
     *  @return max additional fragment size
     */
    public static int getMaxAdditionalFragmentSize(PeerState peer, int numFragments, int curDataSize) {
        int available = peer.getMTU() - curDataSize;
        if (peer.isIPv6())
            available -= MIN_IPV6_DATA_PACKET_OVERHEAD;
        else
            available -= MIN_DATA_PACKET_OVERHEAD;
        // OVERHEAD above includes 1 * FRAGMENT_HEADER_SIZE;
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
        return buildPacket(fragments, null, peer);
    }

    /*
     *  Multiple fragments and optional other blocks.
     *
     *  @param otherBlocks may be null or empty
     */
    public UDPPacket buildPacket(List<Fragment> fragments, List<Block> otherBlocks, SSU2Sender peer) {
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
        // includes UDP header
        int ipHeaderSize;
        if (peer.isIPv6()) {
            availableForAcks -= MIN_IPV6_DATA_PACKET_OVERHEAD;
            ipHeaderSize = IPV6_HEADER_SIZE + UDP_HEADER_SIZE;
        } else {
            availableForAcks -= MIN_DATA_PACKET_OVERHEAD;
            ipHeaderSize = IP_HEADER_SIZE + UDP_HEADER_SIZE;
        }
        if (otherBlocks != null) {
            for (Block block : otherBlocks) {
                availableForAcks -= block.getTotalLength();
            }
        }

        // make the packet
        long pktNum = peer.getNextPacketNumber();
        UDPPacket packet = buildShortPacketHeader(peer.getSendConnID(), pktNum, DATA_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = SHORT_HEADER_SIZE;

        // ok, now for the body...
        // +2 for acks and padding
        int bcnt = fragments.size() + 2;
        if (otherBlocks != null)
            bcnt += otherBlocks.size();
        List<Block> blocks = new ArrayList<Block>(bcnt);
        // payload only
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
                if (_log.shouldDebug())
                    _log.debug("Sending acks " + block + " to " + peer);
            }
        } else if (_log.shouldDebug()) {
            _log.debug("No room for acks, MTU: " + currentMTU + " data: " + dataSize + " available: " + availableForAcks);
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

        // now the other blocks, if any
        boolean hasTermination = false;
        if (otherBlocks != null) {
            for (Block block : otherBlocks) {
                blocks.add(block);
                int sz = block.getTotalLength();
                off += sz;
                sizeWritten += sz;
                if (block.getType() == SSU2Payload.BLOCK_TERMINATION)
                    hasTermination = true;
            }
        }

        // DateTime block every so often, if room
        // not allowed after termination
        if (!hasTermination &&
            (pktNum & (DATETIME_SEND_FREQUENCY - 1)) == DATETIME_SEND_FREQUENCY - 1 &&
            ipHeaderSize + SHORT_HEADER_SIZE + sizeWritten + 7 + MAC_LEN <= currentMTU) {
            Block block = new SSU2Payload.DateTimeBlock(_context);
            blocks.add(block);
            off += 7;
            sizeWritten += 7;
        }

        Block block = getPadding(ipHeaderSize + SHORT_HEADER_SIZE + sizeWritten + MAC_LEN, currentMTU);
        if (block != null) {
            blocks.add(block);
            int sz = block.getTotalLength();
            off += sz;
            sizeWritten += sz;
        }
        SSU2Payload.writePayload(data, SHORT_HEADER_SIZE, blocks);
        pkt.setLength(off);
        int length = off + ipHeaderSize;
        //if (_log.shouldDebug())
        //    _log.debug("Packet " + pktNum + " before encryption:\n" + HexDump.dump(data, 0, off));

        // ack immediate flag
        if (numFragments > 0) {
            data[SHORT_HEADER_FLAGS_OFFSET] = peer.getFlags();
        }

        encryptDataPacket(packet, peer.getSendCipher(), pktNum, peer.getSendHeaderEncryptKey1(), peer.getSendHeaderEncryptKey2());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        //if (_log.shouldDebug())
        //    _log.debug("Packet " + pktNum + " after encryption:\n" + HexDump.dump(data, 0, pkt.getLength()));
        
        // FIXME ticket #2675
        // the packet could have been built before the current mtu got lowered, so
        // compare to LARGE_MTU
        // Also happens on switch between IPv4 and IPv6
        if (_log.shouldWarn()) {
            int maxMTU = PeerState2.MAX_MTU;
            off += MAC_LEN;
            if (off + ipHeaderSize > currentMTU) {
                _log.warn("Size is " + off + " for " + packet +
                       " data size " + dataSize +
                       " pkt size " + (off + ipHeaderSize) +
                       " MTU " + currentMTU +
                       " Fragments: " + DataHelper.toString(fragments) /* , new Exception() */ );
            }
        }
        
        packet.setPriority(priority);
        if (fragments.isEmpty()) {
            SSU2Bitfield acked = peer.getAckedMessages();
            if (acked != null) {     // null for PeerStateDestroyed
                try {
                    acked.set(pktNum); // not ack-eliciting
                } catch (IndexOutOfBoundsException e) {
                    // shift too big, ignore, we're dead or about to be
                }
            }
            packet.markType(1);
            packet.setFragmentCount(-1);
            packet.setMessageType(TYPE_ACK);
        } else {
            // OMF reuses/clears the fragments list, so we must copy it
            if (fragments.size() == 1)
                fragments = Collections.singletonList(fragments.get(0));
            else
                fragments = new ArrayList<Fragment>(fragments);
            peer.fragmentsSent(pktNum, length, fragments);
        }
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
        // plenty of room
        Block block = getPadding(0, 1280);
        List<Block> blocks = Collections.singletonList(block);
        off += block.getTotalLength();
        SSU2Payload.writePayload(data, SHORT_HEADER_SIZE, blocks);
        pkt.setLength(off);
        encryptDataPacket(packet, peer.getSendCipher(), pktNum, peer.getSendHeaderEncryptKey1(), peer.getSendHeaderEncryptKey2());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        packet.setPriority(PRIORITY_LOW);
        try {
            peer.getAckedMessages().set(pktNum); // not ack-eliciting
        } catch (IndexOutOfBoundsException e) {
            // shift too big, ignore, we're dead or about to be
        }
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
        return buildPacket(Collections.<Fragment>emptyList(), peer);
    }

    /**
     *  Build a data packet with a termination block.
     *  This will also include acks, a new token block, and padding.
     */
    public UDPPacket buildSessionDestroyPacket(int reason, SSU2Sender peer) {
        if (_log.shouldDebug())
            _log.debug("Sending termination " + reason + " to : " + peer);
        peer.setDestroyReason(reason);
        List<Block> blocks = new ArrayList<Block>(2);
        if (peer.isIPv6() || !_transport.isSnatted()) {
            // update token
            EstablishmentManager.Token token = _transport.getEstablisher().getInboundToken(peer.getRemoteHostId());
            Block block = new SSU2Payload.NewTokenBlock(token);
            blocks.add(block);
        }
        Block block = new SSU2Payload.TerminationBlock(reason, peer.getReceivedMessages().getHighestSet());
        blocks.add(block);
        UDPPacket packet = buildPacket(Collections.<Fragment>emptyList(), blocks, peer);
        packet.setMessageType(TYPE_DESTROY);
        return packet;
    }
    
    /**
     * Build a new SessionRequest packet for the given peer, encrypting it 
     * as necessary.
     *
     * @return ready to send packet, non-null
     */
    public UDPPacket buildTokenRequestPacket(OutboundEstablishState2 state) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        UDPPacket packet = buildLongPacketHeader(state.getSendConnID(), n, TOKEN_REQUEST_FLAG_BYTE,
                                                 state.getRcvConnID(), 0);
        DatagramPacket pkt = packet.getPacket();
        pkt.setLength(LONG_HEADER_SIZE);
        byte[] introKey = state.getSendHeaderEncryptKey1();
        encryptTokenRequest(packet, introKey, n, introKey, introKey);
        pkt.setSocketAddress(state.getSentAddress());
        packet.setMessageType(TYPE_SREQ);
        packet.setPriority(PRIORITY_HIGH);
        state.tokenRequestSent(pkt);
        return packet;
    }
    
    /**
     * Build a new SessionRequest packet for the given peer, encrypting it 
     * as necessary.
     *
     * @return ready to send packet, non-null
     */
    public UDPPacket buildSessionRequestPacket(OutboundEstablishState2 state) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        UDPPacket packet = buildLongPacketHeader(state.getSendConnID(), n, SESSION_REQUEST_FLAG_BYTE,
                                                 state.getRcvConnID(), state.getToken());
        DatagramPacket pkt = packet.getPacket();
        pkt.setLength(LONG_HEADER_SIZE);
        byte[] introKey = state.getSendHeaderEncryptKey1();
        encryptSessionRequest(packet, state.getHandshakeState(), introKey, introKey, state.needIntroduction());
        pkt.setSocketAddress(state.getSentAddress());
        packet.setMessageType(TYPE_SREQ);
        packet.setPriority(PRIORITY_HIGH);
        state.requestSent(pkt);
        return packet;
    }
    
    /**
     * Build a new SessionCreated packet for the given peer, encrypting it 
     * as necessary.
     *
     * @return ready to send packet, non-null
     */
    public UDPPacket buildSessionCreatedPacket(InboundEstablishState2 state) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        UDPPacket packet = buildLongPacketHeader(state.getSendConnID(), n, SESSION_CREATED_FLAG_BYTE,
                                                 state.getRcvConnID(), 0);
        DatagramPacket pkt = packet.getPacket();
        
        byte sentIP[] = state.getSentIP();
        pkt.setLength(LONG_HEADER_SIZE);
        int port = state.getSentPort();
        encryptSessionCreated(packet, state.getHandshakeState(), state.getSendHeaderEncryptKey1(),
                              state.getSendHeaderEncryptKey2(), state.getSentRelayTag(),
                              null, // state.getNextToken(), // send with termination only
                              sentIP, port);
        pkt.setSocketAddress(state.getSentAddress());
        packet.setMessageType(TYPE_CREAT);
        packet.setPriority(PRIORITY_HIGH);
        state.createdPacketSent(pkt);
        return packet;
    }

    /**
     * Build a new Retry packet for the given peer, encrypting it 
     * as necessary.
     *
     * @param terminationCode 0 normally, nonzero to send termination block
     * @return ready to send packet, non-null
     */
    public UDPPacket buildRetryPacket(InboundEstablishState2 state, int terminationCode) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        long token = terminationCode == 0 ? state.getToken() : 0;
        UDPPacket packet = buildLongPacketHeader(state.getSendConnID(), n, RETRY_FLAG_BYTE,
                                                 state.getRcvConnID(), token);
        DatagramPacket pkt = packet.getPacket();
        
        byte sentIP[] = state.getSentIP();
        pkt.setLength(LONG_HEADER_SIZE);
        int port = state.getSentPort();
        byte[] introKey = state.getSendHeaderEncryptKey1();
        encryptRetry(packet, introKey, n, introKey, introKey,
                     sentIP, port, terminationCode);
        pkt.setSocketAddress(state.getSentAddress());
        packet.setMessageType(TYPE_CREAT);
        packet.setPriority(PRIORITY_HIGH);
        state.retryPacketSent();
        return packet;
    }

    /**
     * Build a new Retry packet with a termination code, for a rejection
     * direct from the EstablishmentManager. No InboundEstablishState2 required.
     *
     * @param terminationCode must be greater than zero
     * @return ready to send packet, non-null
     * @since 0.9.57
     */
    public UDPPacket buildRetryPacket(RemoteHostId to, SocketAddress toAddr, long destID, long srcID, int terminationCode) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        UDPPacket packet = buildLongPacketHeader(destID, n, RETRY_FLAG_BYTE, srcID, 0);
        DatagramPacket pkt = packet.getPacket();
        pkt.setLength(LONG_HEADER_SIZE);
        byte[] introKey = _transport.getSSU2StaticIntroKey();
        encryptRetry(packet, introKey, n, introKey, introKey,
                     to.getIP(), to.getPort(), terminationCode);
        pkt.setSocketAddress(toAddr);
        packet.setMessageType(TYPE_CREAT);
        packet.setPriority(PRIORITY_LOW);
        return packet;
    }
    
    /**
     * Build a new series of SessionConfirmed packets for the given peer, 
     * encrypting it as necessary.
     *
     * If the RI is large enough that it is fragmented, this will still only return
     * a single Session Confirmed message. The remaining RI blocks will be passed to
     * the establish state via confirmedPacketsSent(), and the state will
     * transmit them via the new PeerState2.
     *
     * @return ready to send packets, non-null
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

        // try to reduce bandwidth and leave room for other blocks by gzipping
        // if it is large, even if it would strictly fit
        if (numFragments > 1 || info.length > 1000) {
            byte[] gzipped = DataHelper.compress(info, 0, info.length, DataHelper.MAX_COMPRESSION);
            if (gzipped.length < info.length) {
                if (_log.shouldInfo())
                    _log.info("Gzipping RI, max is " + max + " size was " + info.length + " size now " + gzipped.length);
                gzip = true;
                info = gzipped;
                numFragments = info.length / max;
                if (numFragments * max != info.length)
                    numFragments++;
            }
        }

        int len;
        if (numFragments > 1) {
            if (numFragments > 15)
                throw new IllegalArgumentException();
            if (_log.shouldInfo())
                _log.info("RI size " + info.length + " requires " + numFragments + " packets");
            len = max;
        } else {
            len = info.length;
        }

        // one big block
        SSU2Payload.RIBlock block = new SSU2Payload.RIBlock(info,  0, info.length,
                                                            false, gzip, 0, 1);
        UDPPacket packets[];
        if (numFragments > 1) {
            packets = buildSessionConfirmedPackets(state, block);
        } else {
            packets = new UDPPacket[1];
            packets[0] = buildSessionConfirmedPacket(state, block);
        }
        state.confirmedPacketsSent(packets);
        return packets;
    }

    /**
     * Build a single new SessionConfirmed packet for the given peer, unfragmented.
     *
     * @return ready to send packet, non-null
     */
    private UDPPacket buildSessionConfirmedPacket(OutboundEstablishState2 state, SSU2Payload.RIBlock block) {
        UDPPacket packet = buildShortPacketHeader(state.getSendConnID(), 0, SESSION_CONFIRMED_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        pkt.setLength(SHORT_HEADER_SIZE);
        boolean isIPv6 = state.getSentIP().length == 16;
        encryptSessionConfirmed(packet, state.getHandshakeState(), state.getMTU(), 1, 0, isIPv6,
                                state.getSendHeaderEncryptKey1(), state.getSendHeaderEncryptKey2(), block,
                                null); // state.getNextToken());  // send with termination only
        pkt.setSocketAddress(state.getSentAddress());
        packet.setMessageType(TYPE_CONF);
        packet.setPriority(PRIORITY_HIGH);
        return packet;
    }

    /**
     * Build all the fragmented SessionConfirmed packets
     * 
     */
    private UDPPacket[] buildSessionConfirmedPackets(OutboundEstablishState2 state, SSU2Payload.RIBlock block) {
        UDPPacket packet0 = buildShortPacketHeader(state.getSendConnID(), 0, SESSION_CONFIRMED_FLAG_BYTE);
        DatagramPacket pkt = packet0.getPacket();
        byte[] data0 = pkt.getData();
        int off = pkt.getOffset();
        boolean isIPv6 = state.getSentIP().length == 16;
        // actually IP and UDP overhead
        int ipOverhead = (isIPv6 ? IPV6_HEADER_SIZE : IP_HEADER_SIZE) + UDP_HEADER_SIZE;
        // first packet, no new token block or padding
        int overhead = ipOverhead +
                       SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN + MAC_LEN;
        int mtu = state.getMTU();
        int blockSize = block.getTotalLength();
        // how much of the ri block we can fit in the first packet
        int first = mtu - overhead;
        // how much of the ri block we can fit in additional packets
        int maxAddl = mtu - (ipOverhead + SHORT_HEADER_SIZE);
        // how much data fits in a packet
        int max = mtu - ipOverhead;
        int remaining = blockSize - first;
        // ensure last packet isn't too small which would corrupt the header decryption
        int lastPktSize = remaining % max;
        int addPadding;
        if (lastPktSize < 24) {
            addPadding = 3 + 24 - lastPktSize;
            remaining += addPadding;
        } else {
            addPadding = 0;
        }
        int count = 1 + ((remaining + maxAddl - 1) / maxAddl);

        // put jumbo into the first packet, we will put data0 back below
        byte[] jumbo = new byte[overhead + addPadding + block.getTotalLength()];
        System.arraycopy(data0, off, jumbo, 0, SHORT_HEADER_SIZE);
        pkt.setData(jumbo);
        pkt.setLength(SHORT_HEADER_SIZE);
        byte[] hdrKey1 = state.getSendHeaderEncryptKey1();
        byte[] hdrKey2 = state.getSendHeaderEncryptKey2();
        encryptSessionConfirmed(packet0, state.getHandshakeState(), state.getMTU(), count, addPadding, isIPv6,
                                hdrKey1, hdrKey2, block, state.getNextToken());
        int total = pkt.getLength();
        if (_log.shouldInfo())
            _log.info("Building " + count + " fragmented session confirmed packets" +
                      " max data: " + max +
                      " RI block size: " + blockSize +
                      " total data size: " + total);

        // fix up packet0 by putting the byte array back
        // and encrypting the header
        System.arraycopy(jumbo, 0, data0, off, max);
        pkt.setData(data0);
        pkt.setLength(max);
        pkt.setSocketAddress(state.getSentAddress());
        SSU2Header.encryptShortHeader(packet0, hdrKey1, hdrKey2);
        packet0.setMessageType(TYPE_CONF);
        packet0.setPriority(PRIORITY_HIGH);
        List<UDPPacket> rv = new ArrayList<UDPPacket>(4);
        rv.add(packet0);

        // build all the remaining packets
        // set frag field in header and encrypt headers
        // these headers are not bound to anything with mixHash(),
        // but if anything changes the header will not decrypt correctly
        int pktnum = 0;
        for (int i = max; i < total; i += max - SHORT_HEADER_SIZE) {
            // all packets have packet number 0
            UDPPacket packet = buildShortPacketHeader(state.getSendConnID(), 0, SESSION_CONFIRMED_FLAG_BYTE);
            pkt = packet.getPacket();
            byte[] data = pkt.getData();
            off = pkt.getOffset();
            int len = Math.min(max - SHORT_HEADER_SIZE, total - i);
            System.arraycopy(jumbo, i, data, off + SHORT_HEADER_SIZE, len);
            data[off + SHORT_HEADER_FLAGS_OFFSET] = (byte) (((++pktnum) << 4) | count);  // fragment n of numFragments
            if (len < 24)
                _log.error("FIXME " + len);
            pkt.setLength(len + SHORT_HEADER_SIZE);
            SSU2Header.encryptShortHeader(packet, hdrKey1, hdrKey2);
            pkt.setSocketAddress(state.getSentAddress());
            packet0.setMessageType(TYPE_CONF);
            packet0.setPriority(PRIORITY_HIGH);
            rv.add(packet);
        }
        if (_log.shouldInfo()) {
            for (int i = 0; i < rv.size(); i++) {
                _log.info("pkt " + i + " size " + rv.get(i).getPacket().getLength());
            }
        }
        if (rv.size() != count)
            throw new IllegalStateException("Count " + count + " != size " + rv.size());
        return rv.toArray(new UDPPacket[count]);
    }

    /**
     * Build a packet as Alice, to Bob to begin a  peer test.
     * In-session, message 1.
     *
     * @return ready to send packet, non-null
     */
    public UDPPacket buildPeerTestFromAlice(byte[] signedData, PeerState2 bob) {
        Block block = new SSU2Payload.PeerTestBlock(1, 0, null, signedData);
        UDPPacket rv = buildPacket(Collections.<Fragment>emptyList(), Collections.singletonList(block), bob);
        rv.setMessageType(TYPE_TFA);
        return rv;
    }

    /**
     * Build a packet as Alice to Charlie.
     * Out-of-session, message 6.
     *
     * @return ready to send packet, non-null
     */
    public UDPPacket buildPeerTestFromAlice(InetAddress toIP, int toPort, SessionKey introKey,
                                            long sendID, long rcvID, byte[] signedData) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        long token = _context.random().nextLong();
        UDPPacket packet = buildLongPacketHeader(sendID, n, PEER_TEST_FLAG_BYTE, rcvID, token);
        Block block = new SSU2Payload.PeerTestBlock(6, 0, null, signedData);
        byte[] ik = introKey.getData();
        packet.getPacket().setLength(LONG_HEADER_SIZE);
        encryptPeerTest(packet, ik, n, ik, ik, toIP.getAddress(), toPort, block);
        setTo(packet, toIP, toPort);
        packet.setMessageType(TYPE_TFA);
        packet.setPriority(PRIORITY_LOW);
        return packet;
    }

    /**
     * Build a packet as Bob to Alice, with the response from Charlie,
     * or a rejection by Bob.
     * In-session, message 4.
     *
     * @param charlieHash fake hash (all zeros) if rejected by bob
     * @return ready to send packet, non-null
     */
    public UDPPacket buildPeerTestToAlice(int code, Hash charlieHash, byte[] signedData, PeerState2 alice) {
        Block block = new SSU2Payload.PeerTestBlock(4, code, charlieHash, signedData);
        UDPPacket rv = buildPacket(Collections.<Fragment>emptyList(), Collections.singletonList(block), alice);
        rv.setMessageType(TYPE_TTA);
        return rv;
    }

    /**
     * Build a packet as Charlie to Alice.
     * Out-of-session, messages 5 and 7.
     *
     * @return ready to send packet, non-null
     */
    public UDPPacket buildPeerTestToAlice(InetAddress aliceIP, int alicePort, SessionKey introKey,
                                          boolean firstSend,
                                          long sendID, long rcvID, byte[] signedData) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        long token = _context.random().nextLong();
        UDPPacket packet = buildLongPacketHeader(sendID, n, PEER_TEST_FLAG_BYTE, rcvID, token);
        int msgNum = firstSend ? 5 : 7;
        Block block = new SSU2Payload.PeerTestBlock(msgNum, 0, null, signedData);
        byte[] ik = introKey.getData();
        packet.getPacket().setLength(LONG_HEADER_SIZE);
        encryptPeerTest(packet, ik, n, ik, ik, aliceIP.getAddress(), alicePort, block);
        setTo(packet, aliceIP, alicePort);
        packet.setMessageType(TYPE_TTA);
        packet.setPriority(PRIORITY_LOW);
        return packet;
    }

    /**
     * Build a packet as Bob to Charlie to help test Alice.
     * In-session, message 2.
     *
     * @return ready to send packet, non-null
     */
    public UDPPacket buildPeerTestToCharlie(Hash aliceHash, byte[] signedData, PeerState2 charlie) {
        Block block = new SSU2Payload.PeerTestBlock(2, 0, aliceHash, signedData);
        UDPPacket rv = buildPacket(Collections.<Fragment>emptyList(), Collections.singletonList(block), charlie);
        rv.setMessageType(TYPE_TBC);
        return rv;
    }
    
    /**
     * Build a packet as Charlie to Bob verifying that we will help test Alice.
     * In-session, message 3.
     *
     * @return ready to send packet, non-null
     */
    public UDPPacket buildPeerTestToBob(int code, byte[] signedData, PeerState2 bob) {
        Block block = new SSU2Payload.PeerTestBlock(3, code, null, signedData);
        UDPPacket rv = buildPacket(Collections.<Fragment>emptyList(), Collections.singletonList(block), bob);
        rv.setMessageType(TYPE_TCB);
        return rv;
    }

    /**
     *  From Alice to Bob.
     *  In-session.
     *
     *  @param signedData flag + signed data
     *  @return non-null
     */
    UDPPacket buildRelayRequest(byte[] signedData, PeerState2 bob) {
        Block block = new SSU2Payload.RelayRequestBlock(signedData);
        UDPPacket rv = buildPacket(Collections.<Fragment>emptyList(), Collections.singletonList(block), bob);
        rv.setMessageType(TYPE_RREQ);
        rv.setPriority(PRIORITY_HIGH);
        return rv;
    }

    /**
     *  From Bob to Charlie.
     *  In-session.
     *
     *  @param signedData flag + alice hash + signed data
     *  @param riBlock to include, may be null
     *  @return non-null
     */
    UDPPacket buildRelayIntro(byte[] signedData, Block riBlock, PeerState2 charlie) {
        Block block = new SSU2Payload.RelayIntroBlock(signedData);
        List<Block> blocks;
        if (riBlock != null) {
            // RouterInfo must be first
            blocks = new ArrayList<Block>(2);
            blocks.add(riBlock);
            blocks.add(block);
        } else {
            blocks = Collections.singletonList(block);
        }
        UDPPacket rv = buildPacket(Collections.<Fragment>emptyList(), blocks, charlie);
        rv.setMessageType(TYPE_INTRO);
        return rv;
    }

    /**
     *  From Charlie to Bob or Bob to Alice.
     *  In-session.
     *
     *  @param signedData flag + response code + signed data + optional token
     *  @param state Alice or Bob
     *  @return non-null
     */
    UDPPacket buildRelayResponse(byte[] signedData, PeerState2 state) {
        Block block = new SSU2Payload.RelayResponseBlock(signedData);
        UDPPacket rv = buildPacket(Collections.<Fragment>emptyList(), Collections.singletonList(block), state);
        rv.setMessageType(TYPE_RESP);
        return rv;
    }

    /**
     *  Out-of-session, containing a RelayResponse block.
     *
     */
    public UDPPacket buildHolePunch(InetAddress to, int port, SessionKey introKey,
                                    long sendID, long rcvID, byte[] signedData) {
        long n = _context.random().signedNextInt() & 0xFFFFFFFFL;
        long token = _context.random().nextLong();
        UDPPacket packet = buildLongPacketHeader(sendID, n, HOLE_PUNCH_FLAG_BYTE, rcvID, token);
        Block block = new SSU2Payload.RelayResponseBlock(signedData);
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending relay hole punch to " + to + ":" + port);

        byte[] ik = introKey.getData();
        packet.getPacket().setLength(LONG_HEADER_SIZE);
        encryptPeerTest(packet, ik, n, ik, ik, to.getAddress(), port, block);
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
        //if (_log.shouldDebug())
        //    _log.debug("Building long header destID " + destID + " pkt num " + pktNum + " type " + type + " srcID " + srcID + " token " + token);
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
            block = getPadding(len, 1280, PADDING_MAX_SESSION_REQUEST);
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
     *  @param token may be null
     */
    private void encryptSessionCreated(UDPPacket packet, HandshakeState state,
                                       byte[] hdrKey1, byte[] hdrKey2, long relayTag,
                                       EstablishmentManager.Token token, byte[] ip, int port) {
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
            if (token != null) {
                block = new SSU2Payload.NewTokenBlock(token);
                len += block.getTotalLength();
                blocks.add(block);
            }
            // plenty of room
            block = getPadding(len, 1280, PADDING_MAX_SESSION_CREATED);
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
     *  @param terminationCode 0 normally, nonzero to send termination block
     */
    private void encryptRetry(UDPPacket packet, byte[] chachaKey, long n,
                              byte[] hdrKey1, byte[] hdrKey2, byte[] ip, int port,
                              int terminationCode) {
        Block block;
        if (terminationCode != 0) {
            block = new SSU2Payload.TerminationBlock(terminationCode, 0);
        } else {
            block = null;
        }
        encryptPeerTest(packet, chachaKey, n, hdrKey1, hdrKey2, ip, port, block);
    }

    /**
     *  Also used for hole punch with a relay request block.
     *  Also used for retry with (usually) ptBlock = null
     *
     *  @param packet containing only 32 byte header
     *  @param ptBlock Peer Test or Relay Request block. Null for retry.
     */
    private void encryptPeerTest(UDPPacket packet, byte[] chachaKey, long n,
                                 byte[] hdrKey1, byte[] hdrKey2, byte[] ip, int port,
                                 Block ptBlock) {
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
            if (ptBlock != null) {
                len += ptBlock.getTotalLength();
                blocks.add(ptBlock);
            }
            // plenty of room
            block = getPadding(len, 1280);
            len += block.getTotalLength();
            blocks.add(block);
            SSU2Payload.writePayload(data, off + LONG_HEADER_SIZE, blocks);

            ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
            chacha.initializeKey(chachaKey, 0);
            chacha.setNonce(n);
            chacha.encryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len);

            pkt.setLength(pkt.getLength() + len + MAC_LEN);
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad retry/test/holepunch msg out", re);
            throw re;
        } catch (GeneralSecurityException gse) {
            if (!_log.shouldWarn())
                _log.error("Bad retry/test/holepunch msg out", gse);
            throw new RuntimeException("Bad retry/test/holepunch msg out", gse);
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
            List<Block> blocks = new ArrayList<Block>(2);
            Block block = new SSU2Payload.DateTimeBlock(_context);
            int len = block.getTotalLength();
            blocks.add(block);
            // plenty of room
            block = getPadding(len, 1280);
            len += block.getTotalLength();
            blocks.add(block);
            SSU2Payload.writePayload(data, off + LONG_HEADER_SIZE, blocks);

            ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
            chacha.initializeKey(chachaKey, 0);
            chacha.setNonce(n);
            chacha.encryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len);

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
        SSU2Header.encryptLongHeader(packet, hdrKey1, hdrKey2);
    }

    /**
     *  If numFragments larger than 1, we do NOT encrypt the header here,
     *  that's caller's responsibility.
     *
     *  @param packet containing only 16 byte header
     *  @param addPadding force-add exactly this size a padding block, for jumbo only
     *  @param token may be null
     */
    private void encryptSessionConfirmed(UDPPacket packet, HandshakeState state, int mtu, int numFragments, int addPadding,
                                         boolean isIPv6, byte[] hdrKey1, byte[] hdrKey2,
                                         SSU2Payload.RIBlock riblock, EstablishmentManager.Token token) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        data[off + SHORT_HEADER_FLAGS_OFFSET] = (byte) numFragments;  // fragment 0 of numFragments
        mtu -= UDP_HEADER_SIZE;
        mtu -= isIPv6 ? IPV6_HEADER_SIZE : IP_HEADER_SIZE;
        try {
            List<Block> blocks = new ArrayList<Block>(3);
            int len = riblock.getTotalLength();
            blocks.add(riblock);
            // only if room
            if (token != null && mtu - (SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN + len + MAC_LEN) >= 15) {
                Block block = new SSU2Payload.NewTokenBlock(token);
                len += block.getTotalLength();
                blocks.add(block);
            }
            if (addPadding > 0) {
                // forced padding so last packet isn't too small
                Block block = new SSU2Payload.PaddingBlock(addPadding - 3);
                len += addPadding;
                blocks.add(block);
            } else {
                Block block = getPadding(len, mtu - (SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN + MAC_LEN)); // 80
                if (block != null) {
                    len += block.getTotalLength();
                    blocks.add(block);
                }
            }

            // If we skip past where the static key and 1st MAC will be, we can
            // use the packet for the plaintext and Noise will symmetric encrypt in-place
            SSU2Payload.writePayload(data, off + SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN, blocks);
            state.mixHash(data, off, SHORT_HEADER_SIZE);
            if (_log.shouldDebug())
                _log.debug("State after mixHash 3: " + state);
            state.writeMessage(data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN, len);
            pkt.setLength(pkt.getLength() + KEY_LEN + MAC_LEN + len + MAC_LEN);
            if (_log.shouldDebug())
                _log.debug("Session confirmed packet length is: " + pkt.getLength());
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
        if (numFragments <= 1)
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
        len += MAC_LEN;
        pkt.setLength(len);
        if (len < MIN_DATA_LEN)
            _log.error("Packet too short " + len, new Exception());
        SSU2Header.encryptShortHeader(packet, hdrKey1, hdrKey2);
    }

    /**
     *  @param len current length of the packet including IP/UDP header
     *             (unless header subtracted from max)
     *             If len == 0 ensure 8 byte block minimum
     *  @param max max length of the packet
     *  @return null if no room
     */
    private Block getPadding(int len, int max) {
        return getPadding(len, max, PADDING_MAX);
    }

    /**
     *  @param len current length of the packet including IP/UDP header
     *             (unless header subtracted from max)
     *             If len == 0 ensure 8 byte block minimum
     *  @param max max length of the packet
     *  @param maxPadding max length of the padding (not including block header)
     *  @return null if no room
     *  @since 0.9.56
     */
    private Block getPadding(int len, int max, int maxPadding) {
        int maxpadlen = Math.min(max - len, maxPadding) - SSU2Payload.BLOCK_HEADER_SIZE;
        if (maxpadlen < 0)
            return null;
        int padlen;
        if (maxpadlen == 0) {
            padlen = 0;
        } else {
            padlen = _context.random().nextInt(maxpadlen + 1);
            // ping packets, ensure 40 byte minimum packet size
            if (len == 0)
                padlen += 5;
        }
        return new SSU2Payload.PaddingBlock(padlen);
    }
}
