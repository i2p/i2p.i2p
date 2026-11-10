package net.i2p.i2ptunnel.irc;

import junit.framework.TestCase;

public class IRCFilterInboundSplitTest extends TestCase {

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

    public void testUntaggedChatRewritesDestination() {
        assertEquals("PRIVMSG mynick :\u0001DCC CHAT chat " + OUR_IP + " 43211\u0001",
                     IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC CHAT chat mydest.b32.i2p 20000\u0001",
                                             new StringBuffer(128), helper));
    }

    public void testUntaggedSendRewritesDestination() {
        assertEquals("PRIVMSG mynick :\u0001DCC SEND myfile " + OUR_IP + " 43211 10240\u0001",
                     IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC SEND myfile 3232235777 20000 10240\u0001",
                                             new StringBuffer(128), helper));
    }

    public void testUntaggedActionSurvives() {
        String body = "PRIVMSG mynick :\u0001ACTION waves\u0001";
        assertEquals(body, IRCFilter.inboundFilter(body, new StringBuffer(128), helper));
    }

    public void testTaggedAndUntaggedChatAgree() {
        String body = "PRIVMSG mynick :\u0001DCC CHAT chat mydest.b32.i2p 20000\u0001";
        String tag = "@time=2026-11-10T00:00:00Z ";
        String plain = IRCFilter.inboundFilter(body, new StringBuffer(128), helper);
        assertNotNull(plain);
        assertEquals(tag + plain, IRCFilter.inboundFilter(tag + body, new StringBuffer(128), helper));
    }

    public void testPlainPrivmsgUnchanged() {
        String body = "PRIVMSG mynick :hello world";
        assertEquals(body, IRCFilter.inboundFilter(body, new StringBuffer(128), helper));
    }
}
