package net.i2p.router.transport.udp;

import net.i2p.router.OutNetMessage;

/**
 *
 */
class PacketBuilder {
    
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

    /* Higher than all other OutNetMessage priorities, but still droppable,
     * and will be shown in the codel.UDP-Sender.drop.500 stat.
     */
    static final int PRIORITY_HIGH = 550;
    
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
}
