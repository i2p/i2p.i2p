package net.i2p.i2ptunnel.irc;

import junit.framework.TestCase;

/**
 *  Regression tests for the DCC filtering in the IRC client tunnel.
 *
 *  DCC RESUME / ACCEPT messages carry no IP address, only the port
 *  (and for RESUME, the offset). The type string comes out of
 *  DataHelper.split(), which returns new String objects, so it has to be
 *  compared with equals() - with an identity comparison the check always
 *  failed and those messages were dropped instead of being translated.
 *
 *  @since 2.14.0
 */
public class IRCFilterTest extends TestCase {

    /** 127.0.0.1 as I2P writes it (DataHelper.fromLong semantics) */
    private static final String OUR_IP = "2130706433";
    private static final String OUR_B32 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.b32.i2p";
    private static final int OUT_PORT = 43210;
    private static final int IN_PORT = 43211;
    private static final int RESUME_OUT_PORT = 43212;
    private static final int RESUME_IN_PORT = 43213;
    private static final int ACCEPT_OUT_PORT = 43214;
    private static final int ACCEPT_IN_PORT = 43215;

    private final DCCHelper _helper = new StubDCCHelper();

    /*********************** inbound (server -> irc client) ***************/

    public void testInboundDCCResume() {
        String rv = IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC RESUME myfile 20000 1024\u0001",
                                            new StringBuffer(128), _helper);
        assertNotNull(rv);
        assertEquals("PRIVMSG mynick :\u0001DCC RESUME myfile " +
                     RESUME_IN_PORT + " 1024\u0001", rv);
    }

    public void testInboundDCCAccept() {
        String rv = IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC ACCEPT myfile 20000 1024\u0001",
                                            new StringBuffer(128), _helper);
        assertNotNull(rv);
        assertEquals("PRIVMSG mynick :\u0001DCC ACCEPT myfile " +
                     ACCEPT_IN_PORT + " 1024\u0001", rv);
    }

    public void testInboundDCCSend() {
        String rv = IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC SEND myfile 3232235777 20000 10240\u0001",
                                            new StringBuffer(128), _helper);
        assertNotNull(rv);
        assertEquals("PRIVMSG mynick :\u0001DCC SEND myfile " + OUR_IP +
                     " " + IN_PORT + " 10240\u0001", rv);
    }

    /*********************** outbound (irc client -> server) **************/

    public void testOutboundDCCResume() {
        String rv = IRCFilter.outboundFilter("PRIVMSG buddy :\u0001DCC RESUME myfile 20000 1024\u0001",
                                             new StringBuffer(128), _helper);
        assertNotNull(rv);
        assertEquals("PRIVMSG buddy :\u0001DCC RESUME myfile " +
                     RESUME_OUT_PORT + " 1024\u0001", rv);
    }

    public void testOutboundDCCAccept() {
        String rv = IRCFilter.outboundFilter("PRIVMSG buddy :\u0001DCC ACCEPT myfile 20000 1024\u0001",
                                             new StringBuffer(128), _helper);
        assertNotNull(rv);
        assertEquals("PRIVMSG buddy :\u0001DCC ACCEPT myfile " +
                     ACCEPT_OUT_PORT + " 1024\u0001", rv);
    }

    public void testOutboundDCCSend() {
        String rv = IRCFilter.outboundFilter("PRIVMSG buddy :\u0001DCC SEND myfile 3232235777 20000 10240\u0001",
                                             new StringBuffer(128), _helper);
        assertNotNull(rv);
        assertEquals("PRIVMSG buddy :\u0001DCC SEND myfile " +
                     OUR_B32 + " " + OUT_PORT + " 10240\u0001", rv);
    }

    /*********************** everything else, unchanged *******************/

    public void testInboundDCCUnknownDropped() {
        assertNull(IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC BOGUS whatever 1 2\u0001",
                                           new StringBuffer(128), _helper));
    }

    public void testOutboundDCCUnknownDropped() {
        assertNull(IRCFilter.outboundFilter("PRIVMSG buddy :\u0001DCC BOGUS whatever 1 2\u0001",
                                            new StringBuffer(128), _helper));
    }

    public void testInboundDCCNoHelper() {
        assertNull(IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC CHAT chat mydestination.b32.i2p 20000\u0001",
                                           new StringBuffer(128), null));
    }

    public void testOutboundDCCNoHelper() {
        assertNull(IRCFilter.outboundFilter("PRIVMSG buddy :\u0001DCC CHAT chat 3232235777 20000\u0001",
                                            new StringBuffer(128), null));
    }

    /** a plain message without CTCP must pass through untouched */
    public void testInboundPrivmsgPassthrough() {
        String s = "PRIVMSG mynick :hello world";
        assertEquals(s, IRCFilter.inboundFilter(s, new StringBuffer(128), _helper));
    }

    /**
     *  IRCv3 message tags shift the fields by one. The filter used to always
     *  split into 5 fields, which chopped the message body apart when there was
     *  no tag, dropping the trailing CTCP byte and therefore every untagged
     *  inbound DCC message.
     */
    public void testInboundTaggedAndUntaggedAgree() {
        String body = "PRIVMSG mynick :\u0001DCC RESUME myfile 20000 1024\u0001";
        String tagged = "@time=2026-01-01T00:00:00.123Z= " + body;
        String untaggedResult = IRCFilter.inboundFilter(body, new StringBuffer(128), _helper);
        String taggedResult = IRCFilter.inboundFilter(tagged, new StringBuffer(128), _helper);
        assertNotNull(untaggedResult);
        assertNotNull(taggedResult);
        // the tag must be preserved, the DCC payload translated the same way
        assertEquals("@time=2026-01-01T00:00:00.123Z= PRIVMSG mynick :\u0001" +
                     untaggedResult.substring(untaggedResult.indexOf('\u0001') + 1), taggedResult);
    }

    /** untagged inbound DCC of every type must survive (they used to be dropped) */
    public void testInboundUntaggedDCCNotDropped() {
        assertNotNull(IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC CHAT chat mydest.b32.i2p 20000\u0001",
                                              new StringBuffer(128), _helper));
        assertNotNull(IRCFilter.inboundFilter("PRIVMSG mynick :\u0001DCC SEND myfile 3232235777 20000 10240\u0001",
                                              new StringBuffer(128), _helper));
        assertNotNull(IRCFilter.inboundFilter("PRIVMSG mynick :\u0001ACTION waves\u0001",
                                              new StringBuffer(128), _helper));
    }

    private static class StubDCCHelper implements DCCHelper {
        public boolean isEnabled() { return true; }
        public String getB32Hostname() { return OUR_B32; }
        public byte[] getLocalAddress() { return new byte[] {127, 0, 0, 1}; }
        public int newOutgoing(byte[] ip, int port, String type) { return OUT_PORT; }
        public int newIncoming(String b32, int port, String type) { return IN_PORT; }
        public int resumeOutgoing(int port) { return RESUME_OUT_PORT; }
        public int resumeIncoming(int port) { return RESUME_IN_PORT; }
        public int acceptOutgoing(int port) { return ACCEPT_OUT_PORT; }
        public int acceptIncoming(int port) { return ACCEPT_IN_PORT; }
        public boolean remove(int port) { return true; }
    }
}
