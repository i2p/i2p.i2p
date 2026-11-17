package net.i2p.i2ptunnel.irc;

import junit.framework.TestCase;

public class IRCFilterDCCResumeTest extends TestCase {

    private static final String OUR_IP = "2130706433";
    private static final String OUR_B32 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.b32.i2p";
    private final DCCHelper helper = new StubDCCHelper();

    private static class StubDCCHelper implements DCCHelper {
        public boolean isEnabled() { return true; }
        public String getB32Hostname() { return OUR_B32; }
        public byte[] getLocalAddress() { return new byte[] { 127, 0, 0, 1 }; }
        public int newOutgoing(byte[] ip, int port, String type) { return 43210; }
        public int newIncoming(String b32, int port, String type) { return 43211; }
        public int resumeOutgoing(int port) { return 43212; }
        public int resumeIncoming(int port) { return 43213; }
        public int acceptOutgoing(int port) { return 43214; }
        public int acceptIncoming(int port) { return 43215; }
        public boolean remove(int port) { return true; }
    }

    public void testTaggedInboundResume() {
        assertEquals("@time=now PRIVMSG mynick :\u0001DCC RESUME myfile 43213 1024\u0001",
                     IRCFilter.inboundFilter("@time=now PRIVMSG mynick :\u0001DCC RESUME myfile 20000 1024\u0001",
                                             new StringBuffer(128), helper));
    }

    public void testTaggedInboundAccept() {
        assertEquals("@time=now PRIVMSG mynick :\u0001DCC ACCEPT myfile 43215 1024\u0001",
                     IRCFilter.inboundFilter("@time=now PRIVMSG mynick :\u0001DCC ACCEPT myfile 20000 1024\u0001",
                                             new StringBuffer(128), helper));
    }

    public void testOutboundResume() {
        assertEquals("PRIVMSG buddy :\u0001DCC RESUME myfile 43212 1024\u0001",
                     IRCFilter.outboundFilter("PRIVMSG buddy :\u0001DCC RESUME myfile 20000 1024\u0001",
                                              new StringBuffer(128), helper));
    }

    public void testOutboundAccept() {
        assertEquals("PRIVMSG buddy :\u0001DCC ACCEPT myfile 43214 1024\u0001",
                     IRCFilter.outboundFilter("PRIVMSG buddy :\u0001DCC ACCEPT myfile 20000 1024\u0001",
                                              new StringBuffer(128), helper));
    }

    public void testUnknownOutgoingTypeIsDropped() {
        assertNull(IRCFilter.outboundFilter("PRIVMSG buddy :\u0001DCC BOGUS myfile 3232235777 20000\u0001",
                                            new StringBuffer(128), helper));
    }
}
