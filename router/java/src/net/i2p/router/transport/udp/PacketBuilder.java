package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Big ol' class to do all our packet formatting.  The UDPPackets generated are
 * fully authenticated, encrypted, and configured for delivery to the peer. 
 *
 * The following is from udp.html on the website:

<p>
All UDP datagrams begin with a 16 byte MAC (Message Authentication Code)
and a 16 byte IV (Initialization Vector
followed by a variable
size payload encrypted with the appropriate key.  The MAC used is 
HMAC-MD5, truncated to 16 bytes, while the key is a full 32 byte AES256 
key.  The specific construct of the MAC is the first 16 bytes from:</p>
<pre>
  HMAC-MD5(payload || IV || (payloadLength ^ protocolVersion), macKey)
</pre>

<p>The protocol version is currently 0.</p>

<p>The payload itself is AES256/CBC encrypted with the IV and the 
sessionKey, with replay prevention addressed within its body, 
explained below.  The payloadLength in the MAC is a 2 byte unsigned 
integer in 2s complement.</p>
  
<p>The protocolVersion is a 2 byte unsigned integer in 2s complement,
and currently set to 0.  Peers using a different protocol version will
not be able to communicate with this peer, though earlier versions not
using this flag are.</p>

<h2><a name="payload">Payload</a></h2>

<p>Within the AES encrypted payload, there is a minimal common structure
to the various messages - a one byte flag and a four byte sending 
timestamp (*seconds* since the unix epoch).  The flag byte contains 
the following bitfields:</p>
<pre>
Bit order: 76543210
  bits 7-4: payload type
     bit 3: rekey?
     bit 2: extended options included
  bits 1-0: reserved
</pre>

<p>If the rekey flag is set, 64 bytes of keying material follow the 
timestamp.  If the extended options flag is set, a one byte option 
size value is appended to, followed by that many extended option 
bytes, which are currently uninterpreted.</p>

<p>When rekeying, the first 32 bytes of the keying material is fed 
into a SHA256 to produce the new MAC key, and the next 32 bytes are
fed into a SHA256 to produce the new session key, though the keys are
not immediately used.  The other side should also reply with the 
rekey flag set and that same keying material.  Once both sides have 
sent and received those values, the new keys should be used and the 
previous keys discarded.  It may be useful to keep the old keys 
around briefly, to address packet loss and reordering.</p>

<p>NOTE: Rekeying is currently unimplemented.</p>

<pre>
 Header: 37+ bytes
 +----+----+----+----+----+----+----+----+
 |                  MAC                  |
 |                                       |
 +----+----+----+----+----+----+----+----+
 |                   IV                  |
 |                                       |
 +----+----+----+----+----+----+----+----+
 |flag|        time       | (optionally  |
 +----+----+----+----+----+              |
 | this may have 64 byte keying material |
 | and/or a one+N byte extended options) |
 +---------------------------------------|
</pre>

 *
 *
 */
class PacketBuilder {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    
    /**
     *  For debugging and stats only - does not go out on the wire.
     *  These are chosen to be higher than the highest I2NP message type,
     *  as a data packet is set to the underlying I2NP message type.
     */
    static final int TYPE_FIRST = 42;
    static final int TYPE_ACK = TYPE_FIRST;
    static final int TYPE_PUNCH = 43;
    static final int TYPE_RESP = 44;
    static final int TYPE_INTRO = 45;
    static final int TYPE_RREQ = 46;
    static final int TYPE_TCB = 47;
    static final int TYPE_TBC = 48;
    static final int TYPE_TTA = 49;
    static final int TYPE_TFA = 50;
    static final int TYPE_CONF = 51;
    static final int TYPE_SREQ = 52;
    static final int TYPE_CREAT = 53;

    /** we only talk to people of the right version
     *  Commented out to prevent findbugs noop complaint
     *  If we ever change this, uncomment below and in UDPPacket
    static final int PROTOCOL_VERSION = 0;
     */
    
    /** if no extended options or rekey data, which we don't support  = 37 */
    public static final int HEADER_SIZE = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE + 1 + 4;

    /** 4 byte msg ID + 3 byte fragment info */
    public static final int FRAGMENT_HEADER_SIZE = 7;
    /** not including acks. 46 */
    public static final int DATA_HEADER_SIZE = HEADER_SIZE + 2 + FRAGMENT_HEADER_SIZE;

    /** IPv4 only */
    public static final int IP_HEADER_SIZE = 20;
    /** Same for IPv4 and IPv6 */
    public static final int UDP_HEADER_SIZE = 8;

    /** 74 */
    public static final int MIN_DATA_PACKET_OVERHEAD = IP_HEADER_SIZE + UDP_HEADER_SIZE + DATA_HEADER_SIZE;

    public static final int IPV6_HEADER_SIZE = 40;
    /** 94 */
    public static final int MIN_IPV6_DATA_PACKET_OVERHEAD = IPV6_HEADER_SIZE + UDP_HEADER_SIZE + DATA_HEADER_SIZE;

    /** one byte field */
    public static final int ABSOLUTE_MAX_ACKS = 255;

    /**
     *  Only for data packets. No limit in ack-only packets.
     *  This directly affects data packet overhead.
     */
    private static final int MAX_RESEND_ACKS_LARGE = 9;

    /**
     *  Only for data packets. No limit in ack-only packets.
     *  This directly affects data packet overhead.
     */
    private static final int MAX_RESEND_ACKS_SMALL = 4;

    private static final String PROP_PADDING = "i2np.udp.padding";
    private static final boolean DEFAULT_ENABLE_PADDING = true;

    /**
     *  The nine message types, 0-8, shifted to bits 7-4 for convenience
     */
    private static final byte SESSION_REQUEST_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST << 4;
    private static final byte SESSION_CREATED_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_CREATED << 4;
    private static final byte SESSION_CONFIRMED_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED << 4;
    private static final byte PEER_RELAY_REQUEST_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_RELAY_REQUEST << 4;
    private static final byte PEER_RELAY_RESPONSE_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_RELAY_RESPONSE << 4;
    private static final byte PEER_RELAY_INTRO_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_RELAY_INTRO << 4;
    private static final byte DATA_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_DATA << 4;
    private static final byte PEER_TEST_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_TEST << 4;
    private static final byte SESSION_DESTROY_FLAG_BYTE = (byte) (UDPPacket.PAYLOAD_TYPE_SESSION_DESTROY << 4);
    
    /**
     *  @param transport may be null for unit testing only
     */
    public PacketBuilder(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _transport = transport;
        _log = ctx.logManager().getLog(PacketBuilder.class);
        // all createRateStat in UDPTransport
    }
    
/****
    public UDPPacket buildPacket(OutboundMessageState state, int fragment, PeerState peer) {
        return buildPacket(state, fragment, peer, null, null);
    }
****/

    /**
     *  Class for passing multiple fragments to buildPacket()
     *
     *  @since 0.9.16
     */
    public static class Fragment {
        public final OutboundMessageState state;
        public final int num;

        public Fragment(OutboundMessageState state, int num) {
            this.state = state;
            this.num = num;
        }

        @Override
        public String toString() {
            return "Fragment " + num + " (" + state.fragmentSize(num) + " bytes) of " + state;
        }
    }

    /**
     *  Will a packet to 'peer' that already has 'numFragments' fragments
     *  totalling 'curDataSize' bytes fit another fragment of size 'newFragSize' ??
     *
     *  This doesn't leave anything for acks.
     *
     *  @param numFragments &gt;= 1
     *  @since 0.9.16
     */
    public static int getMaxAdditionalFragmentSize(PeerState peer, int numFragments, int curDataSize) {
        int available = peer.getMTU() - curDataSize;
        if (peer.isIPv6())
            available -= MIN_IPV6_DATA_PACKET_OVERHEAD;
        else
            available -= MIN_DATA_PACKET_OVERHEAD;
        // OVERHEAD above includes 1 * FRAGMENT+HEADER_SIZE;
        // this adds for the others, plus the new one.
        available -= numFragments * FRAGMENT_HEADER_SIZE;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("now: " + numFragments + " / " + curDataSize + " avail: " + available);
        return available;
    }

    /**
     * This builds a data packet (PAYLOAD_TYPE_DATA).
     * See the methods below for the other message types.
     *
     * Note that while the UDP message spec allows for more than one fragment in a message,
     * this method writes exactly one fragment.
     * For no fragments use buildAck().
     * Multiple fragments in a single packet is not supported.
     * Rekeying and extended options are not supported.
     *
     * Packet format:
     *<pre>
     *    16 byte MAC
     *    16 byte IV
     *     1 byte flag
     *     4 byte date
     *     1 byte flag
     *     1 byte explicit ack count IF included
     *   4*n byte explict acks IF included
     *     1 byte ack bitfield count IF included
     *   4*n + ?? ack bitfields IF included
     *     1 byte fragment count (always 1)
     *     4 byte message ID
     *     3 byte fragment info
     *     n byte fragment
     *  0-15 bytes padding
     *</pre>
     *
     * So ignoring the ack bitfields, and assuming we have explicit acks,
     * it's (47 + 4*explict acks + padding) added to the
     * fragment length.
     *
     * @param ackIdsRemaining list of messageIds (Long) that should be acked by this packet.  
     *                        The list itself is passed by reference, and if a messageId is
     *                        transmitted it will be removed from the list.
     *                        Not all message IDs will necessarily be sent, there may not be room.
     *                        non-null.
     *
     * @param newAckCount the number of ackIdsRemaining entries that are new. These must be the first
     *                    ones in the list
     *
     * @param partialACKsRemaining list of messageIds (ACKBitfield) that should be acked by this packet.  
     *                        The list itself is passed by reference, and if a messageId is
     *                        included, it should be removed from the list.
     *                        Full acks in this list are skipped, they are NOT transmitted.
     *                        non-null.
     *                        Not all acks will necessarily be sent, there may not be room.
     *
     * @return null on error
     */
    public UDPPacket buildPacket(OutboundMessageState state, int fragment, PeerState peer,
                                 Collection<Long> ackIdsRemaining, int newAckCount,
                                 List<ACKBitfield> partialACKsRemaining) {
        List<Fragment> frags = Collections.singletonList(new Fragment(state, fragment));
        return buildPacket(frags, peer, ackIdsRemaining, newAckCount, partialACKsRemaining);
    }

    /*
     *  Multiple fragments
     *
     *  @since 0.9.16
     */
    public UDPPacket buildPacket(List<Fragment> fragments, PeerState peer,
                                 Collection<Long> ackIdsRemaining, int newAckCount,
                                 List<ACKBitfield> partialACKsRemaining) {
        StringBuilder msg = null;
        if (_log.shouldLog(Log.INFO)) {
            msg = new StringBuilder(256);
            msg.append("Data pkt to ").append(peer.getRemotePeer().toBase64());
        }

        // calculate data size
        int numFragments = fragments.size();
        int dataSize = 0;
        for (int i = 0; i < numFragments; i++) {
            Fragment frag = fragments.get(i);
            OutboundMessageState state = frag.state;
            int fragment = frag.num;
            int sz = state.fragmentSize(fragment);
            dataSize += sz;
            if (msg != null) {
                msg.append(" Fragment ").append(i);
                msg.append(": msg ").append(state.getMessageId()).append(' ').append(fragment);
                msg.append('/').append(state.getFragmentCount());
                msg.append(' ').append(sz);
            }
        }
        
        if (dataSize < 0)
            return null;

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
        if (numFragments > 1)
            availableForAcks -= (numFragments - 1) * FRAGMENT_HEADER_SIZE;
        int availableForExplicitAcks = availableForAcks;

        // make the packet
        UDPPacket packet = buildPacketHeader(DATA_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;

        // ok, now for the body...
        
        // just always ask for an ACK for now...
        data[off] |= UDPPacket.DATA_FLAG_WANT_REPLY;

        // partial acks have priority but they are after explicit acks in the packet
        // so we have to compute the space in advance
        int partialAcksToSend = 0;
        if (availableForExplicitAcks >= 6 && !partialACKsRemaining.isEmpty()) {
            for (ACKBitfield bf : partialACKsRemaining) {
                if (partialAcksToSend >= ABSOLUTE_MAX_ACKS)
                    break;  // ack count
                if (bf.receivedComplete())
                    continue;
                // only send what we have to
                //int acksz = 4 + (bf.fragmentCount() / 7) + 1;
                int bits = bf.highestReceived() + 1;
                if (bits <= 0)
                    continue;
                int acksz = bits / 7;
                if (bits % 7 > 0)
                    acksz++;
                acksz += 4;
                if (partialAcksToSend == 0)
                    acksz++;  // ack count
                if (availableForExplicitAcks >= acksz) {
                    availableForExplicitAcks -= acksz;
                    partialAcksToSend++;
                } else {
                    break;
                }
            }
            if (partialAcksToSend > 0)
                data[off] |= UDPPacket.DATA_FLAG_ACK_BITFIELDS;
        }


        // Only include acks if we have at least 5 bytes available and at least
        // one ack is requested.
        if (availableForExplicitAcks >= 5 && !ackIdsRemaining.isEmpty()) {
            data[off] |= UDPPacket.DATA_FLAG_EXPLICIT_ACK;
        }
        off++;

        if (msg != null) {
            msg.append(" Total data: ").append(dataSize).append(" bytes, mtu: ")
               .append(currentMTU).append(", ")
               .append(newAckCount).append(" new full acks requested, ")
               .append(ackIdsRemaining.size() - newAckCount).append(" resend acks requested, ")
               .append(partialACKsRemaining.size()).append(" partial acks requested, ")
               .append(availableForAcks).append(" avail. for all acks, ")
               .append(availableForExplicitAcks).append(" for full acks, ");
        }

        // always send all the new acks if we have room
        int explicitToSend = Math.min(ABSOLUTE_MAX_ACKS,
                                      Math.min(newAckCount + (currentMTU > PeerState.MIN_MTU ? MAX_RESEND_ACKS_LARGE : MAX_RESEND_ACKS_SMALL),
                                               Math.min((availableForExplicitAcks - 1) / 4, ackIdsRemaining.size())));
        if (explicitToSend > 0) {
            if (msg != null)
                msg.append(explicitToSend).append(" full acks included:");
            DataHelper.toLong(data, off, 1, explicitToSend);
            off++;
            Iterator<Long> iter = ackIdsRemaining.iterator();
            for (int i = 0; i < explicitToSend && iter.hasNext(); i++) {
                Long ackId = iter.next();
                iter.remove();
                // NPE here, how did a null get in the List?
                DataHelper.toLong(data, off, 4, ackId.longValue());
                off += 4;        
                if (msg != null) // logging it
                    msg.append(' ').append(ackId.longValue());
            }
            //acksIncluded = true;
        }

        if (partialAcksToSend > 0) {
            if (msg != null)
                msg.append(partialAcksToSend).append(" partial acks included:");
            int origNumRemaining = partialACKsRemaining.size();
            int numPartialOffset = off;
            // leave it blank for now, since we could skip some
            off++;
            Iterator<ACKBitfield> iter = partialACKsRemaining.iterator();
            for (int i = 0; i < partialAcksToSend && iter.hasNext(); i++) {
                ACKBitfield bitfield = iter.next();
                if (bitfield.receivedComplete()) continue;
                // only send what we have to
                //int bits = bitfield.fragmentCount();
                int bits = bitfield.highestReceived() + 1;
                if (bits <= 0)
                    continue;
                int size = bits / 7;
                if (bits % 7 > 0)
                    size++;
                DataHelper.toLong(data, off, 4, bitfield.getMessageId());
                off += 4;
                for (int curByte = 0; curByte < size; curByte++) {
                    if (curByte + 1 < size)
                        data[off] = (byte)(1 << 7);
                    else
                        data[off] = 0;
                    
                    for (int curBit = 0; curBit < 7; curBit++) {
                        if (bitfield.received(curBit + 7*curByte))
                            data[off] |= (byte)(1 << curBit);
                    }
                    off++;
                }
                iter.remove();
                if (msg != null) // logging it
                    msg.append(' ').append(bitfield).append(" with ack bytes: ").append(size);
            }
            //acksIncluded = true;
            // now jump back and fill in the number of bitfields *actually* included
            DataHelper.toLong(data, numPartialOffset, 1, origNumRemaining - partialACKsRemaining.size());
        }
        
        //if ( (msg != null) && (acksIncluded) )
        //  _log.debug(msg.toString());
        
        DataHelper.toLong(data, off, 1, numFragments);
        off++;
        
        // now write each fragment
        int sizeWritten = 0;
        for (int i = 0; i < numFragments; i++) {
            Fragment frag = fragments.get(i);
            OutboundMessageState state = frag.state;
            int fragment = frag.num;

            DataHelper.toLong(data, off, 4, state.getMessageId());
            off += 4;
        
            data[off] = (byte) (fragment << 1);
            if (fragment == state.getFragmentCount() - 1)
                data[off] |= 1; // isLast
            off++;
        
            int fragSize = state.fragmentSize(fragment);
            DataHelper.toLong(data, off, 2, fragSize);
            data[off] &= (byte)0x3F; // 2 highest bits are reserved
            off += 2;
        
            int sz = state.writeFragment(data, off, fragment);
            off += sz;
            sizeWritten += sz;
        }

        if (sizeWritten != dataSize) {
            if (sizeWritten < 0) {
                // probably already freed from OutboundMessageState
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Write failed for " + DataHelper.toString(fragments));
            } else {
                _log.error("Size written: " + sizeWritten + " but size: " + dataSize +
                           " for " + DataHelper.toString(fragments));
            }
            packet.release();
            return null;
        //} else if (_log.shouldLog(Log.DEBUG)) {
        //    _log.debug("Size written: " + sizeWritten + " for fragment " + fragment 
        //               + " of " + state.getMessageId());
        }

        // put this after writeFragment() since dataSize will be zero for use-after-free
        if (dataSize == 0) {
            // OK according to the protocol but if we send it, it's a bug
            _log.error("Sending zero-size fragment??? for " + DataHelper.toString(fragments));
        }
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off, currentMTU - (ipHeaderSize + UDP_HEADER_SIZE));
        pkt.setLength(off);

        if (msg != null) {
            // verify multi-fragment packet
            //if (numFragments > 1) {
            //    msg.append("\nDataReader dump\n:");
            //    UDPPacketReader reader = new UDPPacketReader(_context);
            //    reader.initialize(packet);
            //    UDPPacketReader.DataReader dreader = reader.getDataReader();
            //    try {
            //        msg.append(dreader.toString());
            //    } catch (Exception e) {
            //        _log.info("blowup, dump follows", e);
            //        msg.append('\n');
            //        msg.append(net.i2p.util.HexDump.dump(data, 0, off));
            //    }
            //}
            msg.append(" pkt size ").append(off + (ipHeaderSize + UDP_HEADER_SIZE));
            _log.info(msg.toString());
        }

        authenticate(packet, peer.getCurrentCipherKey(), peer.getCurrentMACKey());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        
        // the packet could have been built before the current mtu got lowered, so
        // compare to LARGE_MTU
        int maxMTU = peer.isIPv6() ? PeerState.MAX_IPV6_MTU : PeerState.LARGE_MTU;
        if (off + (ipHeaderSize + UDP_HEADER_SIZE) > maxMTU) {
            _log.error("Size is " + off + " for " + packet +
                       " data size " + dataSize +
                       " pkt size " + (off + (ipHeaderSize + UDP_HEADER_SIZE)) +
                       " MTU " + currentMTU +
                       ' ' + availableForAcks + " for all acks, " +
                       availableForExplicitAcks + " for full acks, " + 
                       explicitToSend + " full acks included, " +
                       partialAcksToSend + " partial acks included, " +
                       " Fragments: " + DataHelper.toString(fragments), new Exception());
        }
        
        return packet;
    }
    
    /**
     * An ACK packet with no acks.
     * We use this for keepalive purposes.
     * It doesn't generate a reply, but that's ok.
     */
    public UDPPacket buildPing(PeerState peer) {
        return buildACK(peer, Collections.<ACKBitfield> emptyList());
    }

    /**
     *  Build the ack packet. The list need not be sorted into full and partial;
     *  this method will put all fulls before the partials in the outgoing packet.
     *  An ack packet is just a data packet with no data.
     *  See buildPacket() for format.
     *
     *  TODO MTU not enforced.
     *  TODO handle huge number of acks better
     *
     * @param ackBitfields list of ACKBitfield instances to either fully or partially ACK
     */
    public UDPPacket buildACK(PeerState peer, List<ACKBitfield> ackBitfields) {
        UDPPacket packet = buildPacketHeader(DATA_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        
        StringBuilder msg = null;
        if (_log.shouldLog(Log.DEBUG)) {
            msg = new StringBuilder(128);
            msg.append("building ACK packet to ").append(peer.getRemotePeer().toBase64().substring(0,6));
        }

        int fullACKCount = 0;
        int partialACKCount = 0;
        for (int i = 0; i < ackBitfields.size(); i++) {
            if (ackBitfields.get(i).receivedComplete())
                fullACKCount++;
            else
                partialACKCount++;
        }
        // FIXME do better than this, we could still exceed MTU
        if (fullACKCount > ABSOLUTE_MAX_ACKS ||
            partialACKCount > ABSOLUTE_MAX_ACKS)
            throw new IllegalArgumentException("Too many acks full/partial " + fullACKCount +
                                               '/' + partialACKCount);

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
                ACKBitfield bf = ackBitfields.get(i);
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
                ACKBitfield bitfield = ackBitfields.get(i);
                if (bitfield.receivedComplete()) continue;
                DataHelper.toLong(data, off, 4, bitfield.getMessageId());
                off += 4;
                // only send what we have to
                //int bits = bitfield.fragmentCount();
                int bits = bitfield.highestReceived() + 1;
                int size = bits / 7;
                if (bits == 0 || bits % 7 > 0)
                    size++;
                for (int curByte = 0; curByte < size; curByte++) {
                    if (curByte + 1 < size)
                        data[off] = (byte)(1 << 7);
                    else
                        data[off] = 0;
                    
                    for (int curBit = 0; curBit < 7; curBit++) {
                        if (bitfield.received(curBit + 7*curByte))
                            data[off] |= (byte)(1 << curBit);
                    }
                    off++;
                }
                
                if (msg != null) // logging it
                    msg.append(" partial ack: ").append(bitfield).append(" with ack bytes: ").append(size);
            }
        }
        
        DataHelper.toLong(data, off, 1, 0); // no fragments in this message
        off++;
        
        if (msg != null)
            _log.debug(msg.toString());
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, peer.getCurrentCipherKey(), peer.getCurrentMACKey());
        setTo(packet, peer.getRemoteIPAddress(), peer.getRemotePort());
        return packet;
    }
    
    /**
     * Build a new SessionCreated packet for the given peer, encrypting it 
     * as necessary.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildSessionCreatedPacket(InboundEstablishState state, int externalPort, SessionKey ourIntroKey) {
        UDPPacket packet = buildPacketHeader(SESSION_CREATED_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        
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
        
        byte sentIP[] = state.getSentIP();
        if ( (sentIP == null) || (sentIP.length <= 0) || (!_transport.isValid(sentIP))) {
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

        // handle variable signature size
        Signature sig = state.getSentSignature();
        int siglen = sig.length();
        System.arraycopy(sig.getData(), 0, data, off, siglen);
        off += siglen;
        // ok, we need another few bytes of random padding
        int rem = siglen % 16;
        int padding;
        if (rem > 0) {
            padding = 16 - rem;
            _context.random().nextBytes(data, off, padding);
            off += padding;
        } else {
            padding = 0;
        }
        
        if (_log.shouldLog(Log.DEBUG)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Sending sessionCreated:");
            buf.append(" Alice: ").append(Addresses.toString(sentIP, state.getSentPort()));
            buf.append(" Bob: ").append(Addresses.toString(state.getReceivedOurIP(), externalPort));
            buf.append(" RelayTag: ").append(state.getSentRelayTag());
            buf.append(" SignedOn: ").append(state.getSentSignedOnTime());
            buf.append(" signature: ").append(Base64.encode(sig.getData()));
            buf.append("\nRawCreated: ").append(Base64.encode(data, 0, off)); 
            buf.append("\nsignedTime: ").append(Base64.encode(data, off - padding - siglen - 4, 4));
            _log.debug(buf.toString());
        }
        
        // ok, now the full data is in there, but we also need to encrypt
        // the signature, which means we need the IV
        byte[] iv = SimpleByteCache.acquire(UDPPacket.IV_SIZE);
        _context.random().nextBytes(iv);
        
        int encrWrite = siglen + padding;
        int sigBegin = off - encrWrite;
        _context.aes().encrypt(data, sigBegin, data, sigBegin, state.getCipherKey(), iv, encrWrite);
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, ourIntroKey, ourIntroKey, iv);
        setTo(packet, to, state.getSentPort());
        SimpleByteCache.release(iv);
        packet.setMessageType(TYPE_CREAT);
        return packet;
    }
    
    /**
     * Build a new SessionRequest packet for the given peer, encrypting it 
     * as necessary.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildSessionRequestPacket(OutboundEstablishState state) {
        int off = HEADER_SIZE;
        byte[] options;
        boolean ext = state.isExtendedOptionsAllowed();
        if (ext) {
            options = new byte[UDPPacket.SESS_REQ_MIN_EXT_OPTIONS_LENGTH];
            boolean intro = state.needIntroduction();
            if (intro)
                options[1] = (byte) UDPPacket.SESS_REQ_EXT_FLAG_REQUEST_RELAY_TAG;
            if (_log.shouldInfo())
                _log.info("send sess req. w/ ext. options, need intro? " + intro + ' ' + state);
            off += UDPPacket.SESS_REQ_MIN_EXT_OPTIONS_LENGTH + 1;
        } else {
            options = null;
        }
        UDPPacket packet = buildPacketHeader(SESSION_REQUEST_FLAG_BYTE, options);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();

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
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId().toString());
            packet.release();
            return null;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending request to " + Addresses.toString(toIP));
        
        // now for the body
        byte[] x = state.getSentX();
        System.arraycopy(x, 0, data, off, x.length);
        off += x.length;
        DataHelper.toLong(data, off, 1, toIP.length);
        off += 1;
        System.arraycopy(toIP, 0, data, off, toIP.length);
        off += toIP.length;
        int port = state.getSentPort();
        DataHelper.toLong(data, off, 2, port);
        off += 2;
        
        // we can pad here if we want, maybe randomized?
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, state.getIntroKey(), state.getIntroKey());
        setTo(packet, to, port);
        packet.setMessageType(TYPE_SREQ);
        return packet;
    }

    private static final int MAX_IDENTITY_FRAGMENT_SIZE = 512;
    
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
     * Build a new SessionConfirmed packet for the given peer
     * 
     * @return ready to send packets, or null if there was a problem
     */
    private UDPPacket buildSessionConfirmedPacket(OutboundEstablishState state, int fragmentNum, int numFragments, byte identity[]) {
        UDPPacket packet = buildPacketHeader(SESSION_CONFIRMED_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;

        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(state.getSentIP());
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + state.getRemoteHostId().toString());
            packet.release();
            return null;
        }
        
        // now for the body
        data[off] = (byte) (fragmentNum << 4);
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
            
            // handle variable signature size
            // we need to pad this so we're at the encryption boundary
            Signature sig = state.getSentSignature();
            int siglen = sig.length();
            int mod = (off + siglen) & 0x0f;
            if (mod != 0) {
                int paddingRequired = 16 - mod;
                // add an arbitrary number of 16byte pad blocks too ???
                _context.random().nextBytes(data, off, paddingRequired);
                off += paddingRequired;
            }
            // We cannot have non-mod16 (pad2) padding here, since the signature
            // is at the end. As of 0.9.7 we won't decrypt past the end of the packet
            // so trailing non-mod-16 data is ignored. That truncates the sig.
            
            // BUG: NPE here if null signature
            System.arraycopy(sig.getData(), 0, data, off, siglen);
            off += siglen;
        } else {
            // We never get here (see above)

            // nothing more to add beyond the identity fragment
            // pad up so we're on the encryption boundary
            off = pad1(data, off);
            // allowed but untested
            //off = pad2(data, off);
        } 
        pkt.setLength(off);
        authenticate(packet, state.getCipherKey(), state.getMACKey());
        setTo(packet, to, state.getSentPort());
        packet.setMessageType(TYPE_CONF);
        return packet;
    }

    
    /**
     *  Build a destroy packet, which contains a header but no body.
     *  Session must be established or this will NPE in authenticate().
     *  Unused until 0.8.9.
     *
     *  @since 0.8.1
     */
    public UDPPacket buildSessionDestroyPacket(PeerState peer) {
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("building session destroy packet to " + peer.getRemotePeer());
        }
        return buildSessionDestroyPacket(peer.getCurrentCipherKey(), peer.getCurrentMACKey(),
                                         peer.getRemoteIPAddress(), peer.getRemotePort());
    }

    /**
     *  Build a destroy packet, which contains a header but no body.
     *  If the keys and ip/port are not yet set, this will return null.
     *
     *  @return packet or null
     *  @since 0.9.2
     */
    public UDPPacket buildSessionDestroyPacket(OutboundEstablishState peer) {
        SessionKey cipherKey = peer.getCipherKey();
        SessionKey macKey = peer.getMACKey();
        byte[] ip = peer.getSentIP();
        int port = peer.getSentPort();
        if (cipherKey == null || macKey == null || ip == null || port <= 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Cannot send destroy, incomplete " + peer);
            return null;
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            return null;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("building session destroy packet to " + peer);
        return buildSessionDestroyPacket(cipherKey, macKey, addr, port);
    }


    /**
     *  Build a destroy packet, which contains a header but no body.
     *  If the keys and ip/port are not yet set, this will return null.
     *
     *  @return packet or null
     *  @since 0.9.2
     */
    public UDPPacket buildSessionDestroyPacket(InboundEstablishState peer) {
        SessionKey cipherKey = peer.getCipherKey();
        SessionKey macKey = peer.getMACKey();
        byte[] ip = peer.getSentIP();
        int port = peer.getSentPort();
        if (cipherKey == null || macKey == null || ip == null || port <= 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Cannot send destroy, incomplete " + peer);
            return null;
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            return null;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("building session destroy packet to " + peer);
        return buildSessionDestroyPacket(cipherKey, macKey, addr, port);
    }

    /**
     *  Build a destroy packet, which contains a header but no body.
     *  @param cipherKey non-null
     *  @param macKey non-null
     *  @since 0.9.2
     */
    private UDPPacket buildSessionDestroyPacket(SessionKey cipherKey, SessionKey macKey, InetAddress addr, int port) {
        UDPPacket packet = buildPacketHeader(SESSION_DESTROY_FLAG_BYTE);
        int off = HEADER_SIZE;
        
        // no body in this message
        
        // pad up so we're on the encryption boundary
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, cipherKey, macKey);
        setTo(packet, addr, port);
        return packet;
    }
    
    /**
     * Build a packet as if we are Alice and we either want Bob to begin a 
     * peer test or Charlie to finish a peer test.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildPeerTestFromAlice(InetAddress toIP, int toPort, SessionKey toIntroKey, long nonce, SessionKey aliceIntroKey) {
        return buildPeerTestFromAlice(toIP, toPort, toIntroKey, toIntroKey, nonce, aliceIntroKey);
    }

    /**
     * Build a packet as if we are Alice and we either want Bob to begin a 
     * peer test or Charlie to finish a peer test.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildPeerTestFromAlice(InetAddress toIP, int toPort, SessionKey toCipherKey, SessionKey toMACKey,
                                            long nonce, SessionKey aliceIntroKey) {
        UDPPacket packet = buildPacketHeader(PEER_TEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Bob");
        
        // now for the body
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        DataHelper.toLong(data, off, 1, 0); // neither Bob nor Charlie need Alice's IP from her
        off++;
        DataHelper.toLong(data, off, 2, 0); // neither Bob nor Charlie need Alice's port from her
        off += 2;
        System.arraycopy(aliceIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, toCipherKey, toMACKey);
        setTo(packet, toIP, toPort);
        packet.setMessageType(TYPE_TFA);
        return packet;
    }

    /**
     * Build a packet as if we are either Bob or Charlie and we are helping test Alice.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildPeerTestToAlice(InetAddress aliceIP, int alicePort,
                                          SessionKey aliceIntroKey, SessionKey charlieIntroKey, long nonce) {
        UDPPacket packet = buildPacketHeader(PEER_TEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Alice");
        
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
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, aliceIntroKey, aliceIntroKey);
        setTo(packet, aliceIP, alicePort);
        packet.setMessageType(TYPE_TTA);
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
        UDPPacket packet = buildPacketHeader(PEER_TEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Charlie");
        
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
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, charlieCipherKey, charlieMACKey);
        setTo(packet, charlieIP, charliePort);
        packet.setMessageType(TYPE_TBC);
        return packet;
    }
    
    /**
     * Build a packet as if we are Charlie sending Bob a packet verifying that we will help test Alice.
     * 
     * @return ready to send packet, or null if there was a problem
     */
    public UDPPacket buildPeerTestToBob(InetAddress bobIP, int bobPort, InetAddress aliceIP, int alicePort,
                                        SessionKey aliceIntroKey, long nonce,
                                        SessionKey bobCipherKey, SessionKey bobMACKey) {
        UDPPacket packet = buildPacketHeader(PEER_TEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending peer test " + nonce + " to Bob");
        
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
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, bobCipherKey, bobMACKey);
        setTo(packet, bobIP, bobPort);
        packet.setMessageType(TYPE_TCB);
        return packet;
    }

    // specify these if we know what our external receive ip/port is and if its different
    // from what bob is going to think
    // FIXME IPv4 addr must be specified when sent over IPv6
    private byte[] getOurExplicitIP() { return null; }
    private int getOurExplicitPort() { return 0; }
    
    /**
     *  build intro packets for each of the published introducers
     *  @return empty list on failure
     */
    public List<UDPPacket> buildRelayRequest(UDPTransport transport, OutboundEstablishState state, SessionKey ourIntroKey) {
        UDPAddress addr = state.getRemoteAddress();
        int count = addr.getIntroducerCount();
        List<UDPPacket> rv = new ArrayList<UDPPacket>(count);
        long cutoff = _context.clock().now() + 5*60*1000L;
        for (int i = 0; i < count; i++) {
            InetAddress iaddr = addr.getIntroducerHost(i);
            int iport = addr.getIntroducerPort(i);
            byte ikey[] = addr.getIntroducerKey(i);
            long tag = addr.getIntroducerTag(i);
            long exp = addr.getIntroducerExpiration(i);
            // let's not use an introducer on a privileged port, sounds like trouble
            if (ikey == null || !TransportUtil.isValidPort(iport) ||
                iaddr == null || tag <= 0 ||
                // must be IPv4 for now as we don't send Alice IP/port, see below
                iaddr.getAddress().length != 4 ||
                (!_transport.isValid(iaddr.getAddress())) ||
                (exp > 0 && exp < cutoff) ||
                (Arrays.equals(iaddr.getAddress(), _transport.getExternalIP()) && !_transport.allowLocal())) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Cannot build a relay request to " + state.getRemoteIdentity().calculateHash()
                               + ", as their UDP address is invalid: addr=" + addr + " index=" + i);
                // TODO implement some sort of introducer banlist
                continue;
            }

            // lookup session so we can use session key if available
            SessionKey cipherKey = null;
            SessionKey macKey = null;
            // first look up by ikey, it is equal to router hash for now
            PeerState bobState = null;
            if (ikey.length == Hash.HASH_LENGTH) {
                bobState = transport.getPeerState(new Hash(ikey));
            }
            if (bobState == null) {
                RemoteHostId rhid = new RemoteHostId(iaddr.getAddress(), iport);
                bobState = transport.getPeerState(rhid);
            }
            if (bobState != null) {
                // established session (since 0.9.12)
                cipherKey = bobState.getCurrentCipherKey();
                macKey = bobState.getCurrentMACKey();
            }
            if (cipherKey == null || macKey == null) {
                // no session, use intro key (was only way before 0.9.12)
                cipherKey = new SessionKey(ikey);
                macKey = cipherKey;
                if (_log.shouldLog(Log.INFO))
                    _log.info("Sending relay request (w/ intro key) to " + iaddr + ":" + iport);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Sending relay request (in-session) to " + iaddr + ":" + iport);
            }

            rv.add(buildRelayRequest(iaddr, iport, cipherKey, macKey, tag,
                                     ourIntroKey, state.getIntroNonce()));
        }
        return rv;
    }
    
    /**
     *  TODO Alice IP/port in packet will always be null/0, must be fixed to
     *  send a RelayRequest over IPv6
     *
     */
    private UDPPacket buildRelayRequest(InetAddress introHost, int introPort,
                                        SessionKey cipherKey, SessionKey macKey,
                                        long introTag, SessionKey ourIntroKey, long introNonce) {
        UDPPacket packet = buildPacketHeader(PEER_RELAY_REQUEST_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        
        // FIXME must specify these if request is going over IPv6
        byte ourIP[] = getOurExplicitIP();
        int ourPort = getOurExplicitPort();
        
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
        //off += 0; // *cough*
        
        System.arraycopy(ourIntroKey.getData(), 0, data, off, SessionKey.KEYSIZE_BYTES);
        off += SessionKey.KEYSIZE_BYTES;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("wrote alice intro key: " + Base64.encode(data, off-SessionKey.KEYSIZE_BYTES, SessionKey.KEYSIZE_BYTES) 
                      + " with nonce " + introNonce + " size=" + (off+4 + (16 - (off+4)%16))
                      + " and data: " + Base64.encode(data, 0, off));
        
        DataHelper.toLong(data, off, 4, introNonce);
        off += 4;
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, cipherKey, macKey);
        setTo(packet, introHost, introPort);
        packet.setMessageType(TYPE_RREQ);
        return packet;
    }

    UDPPacket buildRelayIntro(RemoteHostId alice, PeerState charlie, UDPPacketReader.RelayRequestReader request) {
        UDPPacket packet = buildPacketHeader(PEER_RELAY_INTRO_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending intro to " + charlie + " for " + alice);
        
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
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, charlie.getCurrentCipherKey(), charlie.getCurrentMACKey());
        setTo(packet, charlie.getRemoteIPAddress(), charlie.getRemotePort());
        packet.setMessageType(TYPE_INTRO);
        return packet;
    }
    
    UDPPacket buildRelayResponse(RemoteHostId alice, PeerState charlie, long nonce,
                                 SessionKey cipherKey, SessionKey macKey) {
        InetAddress aliceAddr = null;
        try {
            aliceAddr = InetAddress.getByAddress(alice.getIP());
        } catch (UnknownHostException uhe) {
            return null;
        }
        
        UDPPacket packet = buildPacketHeader(PEER_RELAY_RESPONSE_FLAG_BYTE);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = HEADER_SIZE;
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending relay response to " + alice + " for " + charlie + " with key " + cipherKey);

        // now for the body
        byte charlieIP[] = charlie.getRemoteIP();
        DataHelper.toLong(data, off, 1, charlieIP.length);
        off++;
        System.arraycopy(charlieIP, 0, data, off, charlieIP.length);
        off += charlieIP.length;
        DataHelper.toLong(data, off, 2, charlie.getRemotePort());
        off += 2;
        
        // Alice IP/Port currently ignored on receive - see UDPPacketReader
        byte aliceIP[] = alice.getIP();
        DataHelper.toLong(data, off, 1, aliceIP.length);
        off++;
        System.arraycopy(aliceIP, 0, data, off, aliceIP.length);
        off += aliceIP.length;
        DataHelper.toLong(data, off, 2, alice.getPort());
        off += 2;
        
        DataHelper.toLong(data, off, 4, nonce);
        off += 4;
        
        // pad up so we're on the encryption boundary
        off = pad1(data, off);
        off = pad2(data, off);
        pkt.setLength(off);
        authenticate(packet, cipherKey, macKey);
        setTo(packet, aliceAddr, alice.getPort());
        packet.setMessageType(TYPE_RESP);
        return packet;
    }
    
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
        return packet;
    }
    
    /**
     *  TESTING ONLY.
     *  Creates an arbitrary packet for unit testing.
     *  Null transport in constructor OK.
     *
     *  @since IPv6
     */
    public UDPPacket buildPacket(byte[] data, InetAddress to, int port) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte d[] = packet.getPacket().getData();
        System.arraycopy(data, 0, d, 0, data.length);
        packet.getPacket().setLength(data.length);
        setTo(packet, to, port);
        return packet;
    }
    
    /**
     *  Create a new packet and add the flag byte and the time stamp.
     *  Caller should add data starting at HEADER_SIZE.
     *  Does not include extended options or rekeying.
     *
     *  @param flagByte contains type and flags
     *  @since 0.8.1
     */
    private UDPPacket buildPacketHeader(byte flagByte) {
        return buildPacketHeader(flagByte, null);
    }

    /**
     *  Create a new packet and add the flag byte and the time stamp.
     *  Caller should add data starting at HEADER_SIZE.
     *  (if extendedOptions != null, at HEADER_SIZE + 1 + extendedOptions.length)
     *  Does not include rekeying.
     *
     *  @param flagByte contains type and flags
     *  @param extendedOptions May be null. If non-null, we will add the associated flag here.
     *                         255 bytes max.
     *  @since 0.9.24
     */
    private UDPPacket buildPacketHeader(byte flagByte, byte[] extendedOptions) {
        UDPPacket packet = UDPPacket.acquire(_context, false);
        byte data[] = packet.getPacket().getData();
        Arrays.fill(data, 0, data.length, (byte)0x0);
        int off = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        
        // header
        if (extendedOptions != null)
            flagByte |= UDPPacket.HEADER_FLAG_EXTENDED_OPTIONS;
        data[off] = flagByte;
        off++;
        // Note, this is unsigned, so we're good until February 2106
        long now = (_context.clock().now() + 500) / 1000;
        DataHelper.toLong(data, off, 4, now);
        // todo: add support for rekeying
        // extended options
        if (extendedOptions != null) {
            off+= 4;
            int len = extendedOptions.length;
            if (len > 255)
                throw new IllegalArgumentException();
            data[off++] = (byte) len;
            System.arraycopy(extendedOptions, 0, data, off, len);
        }
        return packet;
    }

    private static void setTo(UDPPacket packet, InetAddress ip, int port) {
        DatagramPacket pkt = packet.getPacket();
        pkt.setAddress(ip);
        pkt.setPort(port);
    }

    /**
     * Pad up to next 16 byte boundary and return new offset.
     * These bytes will be encrypted.
     * This must be called before encryption.
     *
     * @return new offset
     * @since 0.9.7
     */
    private int pad1(byte[] data, int off) {
        int mod = off & 0x0f;
        if (mod == 0)
            return off;
        int padSize = 16 - mod;
        _context.random().nextBytes(data, off, padSize);
        return off + padSize;
    }

    /** max is one less */
    private static final int MAX_PAD2 = 16;

    /**
     * Pad a random amount (not mod 16) and return new offset.
     * Packet must be well under the max length (MTU).
     * These bytes will be included in the MAC calculation but not encrypted.
     * This must be called before encryption.
     *
     * @return new offset
     * @since 0.9.7
     */
    private int pad2(byte[] data, int off) {
        if (!_context.getProperty(PROP_PADDING, DEFAULT_ENABLE_PADDING))
            return off;
        int padSize = _context.random().nextInt(MAX_PAD2);
        if (padSize == 0)
            return off;
        _context.random().nextBytes(data, off, padSize);
        return off + padSize;
    }

    /**
     * Pad a random amount (not mod 16) and return new offset,
     * while honoring the max length.
     * These bytes will be included in the MAC calculation but not encrypted.
     * This must be called before encryption.
     *
     * @return new offset
     * @since 0.9.7
     */
    private int pad2(byte[] data, int off, int maxLen) {
        if (!_context.getProperty(PROP_PADDING, DEFAULT_ENABLE_PADDING))
            return off;
        if (off >= maxLen)
            return off;
        int padSize = _context.random().nextInt(Math.min(MAX_PAD2, 1 + maxLen - off));
        if (padSize == 0)
            return off;
        _context.random().nextBytes(data, off, padSize);
        return off + padSize;
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
        byte[] iv = SimpleByteCache.acquire(UDPPacket.IV_SIZE);
        _context.random().nextBytes(iv);
        authenticate(packet, cipherKey, macKey, iv);
        SimpleByteCache.release(iv);
    }
    
    /**
     * Encrypt the packet with the cipher key and the given IV, generate a 
     * MAC for that encrypted data and IV, and store the result in the packet.
     * The MAC used is: 
     *     HMAC-SHA256(payload || IV || (payloadLength ^ protocolVersion), macKey)[0:15]
     *
     * @param packet prepared packet with the first 32 bytes empty and a length
     *               whose size is mod 16.
     *               As of 0.9.7, length non-mod-16 is allowed; the
     *               last 1-15 bytes are included in the MAC calculation but are not encrypted.
     * @param cipherKey key to encrypt the payload 
     * @param macKey key to generate the, er, MAC
     * @param iv IV to deliver
     */
    private void authenticate(UDPPacket packet, SessionKey cipherKey, SessionKey macKey, byte[] iv) {
        //long before = System.currentTimeMillis();
        DatagramPacket pkt = packet.getPacket();
        int off = pkt.getOffset();
        int hmacOff = off;
        int encryptOffset = off + UDPPacket.IV_SIZE + UDPPacket.MAC_SIZE;
        // including 1-15 pad
        int totalSize = pkt.getLength() - UDPPacket.IV_SIZE - UDPPacket.MAC_SIZE - off;
        int mod = totalSize & 0x0f;
        // not including 1-15 pad
        int encryptSize = totalSize - mod;
        byte data[] = pkt.getData();
        _context.aes().encrypt(data, encryptOffset, data, encryptOffset, cipherKey, iv, encryptSize);
        
        // ok, now we need to prepare things for the MAC, which requires reordering
        // Payload + IV + payloadLength
        System.arraycopy(data, encryptOffset, data, off, totalSize);
        off += totalSize;
        System.arraycopy(iv, 0, data, off, UDPPacket.IV_SIZE);
        off += UDPPacket.IV_SIZE;
        DataHelper.toLong(data, off, 2, totalSize /* ^ PROTOCOL_VERSION */ );
        
        int hmacLen = totalSize + UDPPacket.IV_SIZE + 2;
        //Hash hmac = _context.hmac().calculate(macKey, data, hmacOff, hmacLen);
        byte[] ba = SimpleByteCache.acquire(Hash.HASH_LENGTH);
        _context.hmac().calculate(macKey, data, hmacOff, hmacLen, ba, 0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Authenticating " + pkt.getLength() +
                       "\nIV: " + Base64.encode(iv) +
                       "\nraw mac: " + Base64.encode(ba) +
                       "\nMAC key: " + macKey);
        // ok, now lets put it back where it belongs...
        // MAC + IV + payload
        System.arraycopy(data, hmacOff, data, encryptOffset, totalSize);
        //System.arraycopy(hmac.getData(), 0, data, hmacOff, UDPPacket.MAC_SIZE);
        System.arraycopy(ba, 0, data, hmacOff, UDPPacket.MAC_SIZE);
        SimpleByteCache.release(ba);
        System.arraycopy(iv, 0, data, hmacOff + UDPPacket.MAC_SIZE, UDPPacket.IV_SIZE);
        // avg. 0.06 ms on a 2005-era PC
        //long timeToAuth = System.currentTimeMillis() - before;
        //_context.statManager().addRateData("udp.packetAuthTime", timeToAuth, timeToAuth);
        //if (timeToAuth > 100)
        //    _context.statManager().addRateData("udp.packetAuthTimeSlow", timeToAuth, timeToAuth);
    }
}
