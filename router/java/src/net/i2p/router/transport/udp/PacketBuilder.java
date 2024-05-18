package net.i2p.router.transport.udp;

import net.i2p.router.OutNetMessage;

/**
 *
 */
class PacketBuilder {
    
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

    /* Higher than all other OutNetMessage priorities, but still droppable,
     * and will be shown in the codel.UDP-Sender.drop.500 stat.
     */
    static final int PRIORITY_HIGH = 550;
    private static final int PRIORITY_LOW = OutNetMessage.PRIORITY_LOWEST;
    
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
}
