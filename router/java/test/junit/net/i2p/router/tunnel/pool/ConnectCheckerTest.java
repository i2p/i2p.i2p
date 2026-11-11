package net.i2p.router.tunnel.pool;

import java.util.Arrays;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.util.OrderedProperties;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ConnectCheckerTest {
    private RouterContext context;
    private ConnectChecker checker;

    @Before
    public void setUp() {
        context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        when(context.commSystem().getStatus()).thenReturn(Status.OK);
        when(context.getBooleanPropertyDefaultTrue("i2np.ntcp.enable")).thenReturn(true);
        when(context.getBooleanPropertyDefaultTrue("i2np.udp.enable")).thenReturn(true);
        checker = new ConnectChecker(context);
    }

    @Test
    public void testOutboundOnlyNTCP2IsNotAnInboundRoute() {
        checkOutboundOnly("NTCP2", "4", "192.0.2.1");
        checkOutboundOnly("NTCP2", "6", "2001:db8::1");
    }

    @Test
    public void testOutboundOnlySSU2IsNotAnInboundRoute() {
        checkOutboundOnly("SSU2", "4", "192.0.2.1");
        checkOutboundOnly("SSU2", "6", "2001:db8::1");
    }

    @Test
    public void testReachabilityStatusCannotInventAnInboundRoute() {
        for (Status status : Status.values()) {
            when(context.commSystem().getStatus()).thenReturn(status);
            for (String style : new String[] { "NTCP2", "SSU2" }) {
                for (String family : new String[] { "4", "6" }) {
                    RouterInfo outboundOnly = info(address(style, "caps", family));
                    assertEquals(status + " must not make outbound-only " + style + " IPv" + family + " dialable",
                                 0, checker.getInboundMask(outboundOnly));
                }
            }
            assertEquals(status + " must not make an unpublished router dialable", 0, checker.getInboundMask(info()));
        }
    }

    @Test
    public void testPublishedRoutesRemainUsableWhileStatusIsUnknown() {
        when(context.commSystem().getStatus()).thenReturn(Status.UNKNOWN);
        for (String style : new String[] { "NTCP2", "SSU2" }) {
            for (String host : new String[] { "192.0.2.1", "2001:db8::1" }) {
                RouterInfo direct = info(address(style, "host", host, "port", "12345"));
                int mask = checker.getInboundMask(direct);
                assertTrue("Published " + style + " " + host + " must remain reachable", mask != 0);
                assertTrue(checker.canConnect(mask, direct));
                assertTrue(checker.canConnect(direct, mask));
            }
        }
        RouterInfo introduced = info(address("SSU2", "caps", "6", "itag0", "123456", "ih0",
                                              Hash.create(new byte[32]).toBase64()));
        assertTrue("Published IPv6 introducers remain usable during reachability tests",
                   checker.getInboundMask(introduced) != 0);
    }

    @Test
    public void testOutboundOnlyLegacySSUIsNotAnInboundRoute() {
        checkOutboundOnly("SSU", "4", "192.0.2.1");
        checkOutboundOnly("SSU", "6", "2001:db8::1");
    }

    private void checkOutboundOnly(String style, String family, String host) {
        RouterInfo outboundOnly = info(address(style, "caps", family));
        int mask = checker.getInboundMask(info(address(style, "host", host, "port", "12345")));
        assertTrue("Must retain outgoing " + style + " IPv" + family,
                   checker.canConnect(outboundOnly, mask));
        assertFalse("Cannot dial outbound-only " + style + " IPv" + family,
                    checker.canConnect(mask, outboundOnly));
        assertEquals(0, checker.getInboundMask(outboundOnly));
    }

    @Test
    public void testSSU2IntroducersProvideAnInboundRoute() {
        for (String family : new String[] { "4", "6" }) {
            RouterInfo introduced = info(address("SSU2", "caps", family, "itag0", "123456", "ih0",
                                                 Hash.create(new byte[32]).toBase64()));
            String host = family.equals("4") ? "192.0.2.1" : "2001:db8::1";
            int mask = checker.getInboundMask(info(address("SSU2", "host", host, "port", "12345")));
            assertTrue(checker.canConnect(mask, introduced));
            assertTrue(checker.canConnect(introduced, mask));
            assertEquals(mask, checker.getInboundMask(introduced));
        }
    }

    @Test
    public void testSymmetricNATIntroducersRequireAnEstablishedAdjacentPeer() {
        for (Status status : new Status[] { Status.DIFFERENT, Status.IPV4_SNAT_IPV6_UNKNOWN, Status.IPV4_SNAT_IPV6_OK }) {
            when(context.commSystem().getStatus()).thenReturn(status);
            for (String style : new String[] { "SSU", "SSU2" }) {
                RouterInfo introduced = info(address(style, "caps", "4", "itag0", "123456", "ih0",
                                                     Hash.create(new byte[32]).toBase64()));
                assertTrue(checker.getInboundMask(introduced) != 0);
                assertTrue(status + " " + style + " must use an established adjacent session",
                           checker.requiresConnectedInboundPeer(introduced));
            }
        }
    }

    @Test
    public void testSymmetricNATPreservesIndependentTCPAndIPv6Routes() {
        for (Status status : new Status[] { Status.DIFFERENT, Status.IPV4_SNAT_IPV6_UNKNOWN, Status.IPV4_SNAT_IPV6_OK }) {
            when(context.commSystem().getStatus()).thenReturn(status);
            RouterAddress introduced = address("SSU2", "caps", "4", "itag0", "123456", "ih0",
                                                Hash.create(new byte[32]).toBase64());
            for (RouterAddress independent : new RouterAddress[] {
                    address("NTCP2", "host", "192.0.2.1", "port", "12345"),
                    address("NTCP2", "host", "2001:db8::1", "port", "12345"),
                    address("SSU2", "host", "2001:db8::1", "port", "12345"),
                    address("SSU2", "caps", "6", "itag0", "123456", "ih0", Hash.create(new byte[32]).toBase64()) }) {
                assertFalse(status + " must retain its independent inbound route",
                            checker.requiresConnectedInboundPeer(info(introduced, independent)));
            }
            assertTrue("Outbound-only TCP and IPv6 capabilities do not supply an independent inbound route",
                       checker.requiresConnectedInboundPeer(info(introduced, address("NTCP2", "caps", "46"),
                                                                  address("SSU2", "caps", "6"))));
        }
    }

    @Test
    public void testOrdinaryFirewalledIntroducersDoNotRequireConnectedPeers() {
        RouterInfo introduced = info(address("SSU2", "caps", "4", "itag0", "123456", "ih0",
                                             Hash.create(new byte[32]).toBase64()));
        for (Status status : new Status[] { Status.REJECT_UNSOLICITED, Status.IPV4_FIREWALLED_IPV6_UNKNOWN,
                                           Status.IPV4_FIREWALLED_IPV6_OK, Status.UNKNOWN }) {
            when(context.commSystem().getStatus()).thenReturn(status);
            assertFalse(status + " must retain ordinary introduction selection",
                        checker.requiresConnectedInboundPeer(introduced));
        }
    }

    @Test
    public void testDirectAddressesRemainReachable() {
        for (String style : new String[] { "NTCP2", "SSU", "SSU2" }) {
            RouterInfo ipv4 = info(address(style, "host", "192.0.2.1", "port", "12345"));
            RouterInfo ipv6 = info(address(style, "host", "2001:db8::1", "port", "12345"));
            int mask4 = checker.getInboundMask(ipv4);
            int mask6 = checker.getInboundMask(ipv6);
            assertTrue(checker.canConnect(mask4, ipv4));
            assertTrue(checker.canConnect(mask6, ipv6));
            assertFalse(checker.canConnect(mask4, ipv6));
            assertFalse(checker.canConnect(mask6, ipv4));
        }
    }

    @Test
    public void testDirectAddressNeedsAUsablePort() {
        for (String style : new String[] { "NTCP2", "SSU2" }) {
            for (String port : new String[] { "0", "80", "65536", "invalid" }) {
                RouterInfo invalid = info(address(style, "host", "192.0.2.1", "port", port));
                assertFalse(checker.canConnect(ConnectChecker.ANY_V4, invalid));
                assertTrue(checker.canConnect(invalid, ConnectChecker.ANY_V4));
            }
            assertFalse(checker.canConnect(ConnectChecker.ANY_V4, info(address(style, "host", "192.0.2.1"))));
        }
    }

    @Test
    public void testHiddenSourceCanUseSSU2() {
        RouterInfo hidden = info();
        int ssu2Mask = checker.getInboundMask(info(address("SSU2", "host", "192.0.2.1", "port", "12345")));
        assertTrue(checker.canConnect(hidden, ssu2Mask));
        assertFalse(checker.canConnect(ssu2Mask, hidden));
    }

    @Test
    public void testHashChecksRespectDirection() {
        Hash us = hash(0);
        Hash from = hash(1);
        Hash to = hash(2);
        when(context.routerHash()).thenReturn(us);
        when(context.netDb().lookupLocallyWithoutValidation(from))
            .thenReturn(info(address("SSU2", "caps", "4")));
        when(context.netDb().lookupLocallyWithoutValidation(to))
            .thenReturn(info(address("SSU2", "host", "192.0.2.1", "port", "12345")));
        assertTrue(checker.canConnect(from, to));
        assertFalse(checker.canConnect(to, from));
    }

    @Test
    public void testHiddenSourceHashCanUseSSU2() {
        Hash us = hash(0);
        Hash from = hash(1);
        Hash to = hash(2);
        when(context.routerHash()).thenReturn(us);
        when(context.netDb().lookupLocallyWithoutValidation(from)).thenReturn(info());
        when(context.netDb().lookupLocallyWithoutValidation(to))
            .thenReturn(info(address("SSU2", "host", "192.0.2.1", "port", "12345")));
        assertTrue(checker.canConnect(from, to));
    }

    @Test
    public void testEstablishedConnectionDoesNotNeedAPublishedInboundRoute() {
        Hash us = hash(0);
        Hash peer = hash(1);
        when(context.routerHash()).thenReturn(us);
        when(context.netDb().lookupLocallyWithoutValidation(us)).thenReturn(info(address("SSU2", "caps", "4")));
        when(context.netDb().lookupLocallyWithoutValidation(peer)).thenReturn(info(address("SSU2", "caps", "4")));
        assertFalse(checker.canConnect(us, peer));
        assertFalse(checker.canConnect(peer, us));
        when(context.commSystem().isEstablished(peer)).thenReturn(true);
        assertTrue(checker.canConnect(us, peer));
        assertTrue(checker.canConnect(peer, us));
    }

    private static RouterAddress address(String style, String... options) {
        OrderedProperties properties = new OrderedProperties();
        for (int i = 0; i < options.length; i += 2)
            properties.setProperty(options[i], options[i + 1]);
        return new RouterAddress(style, properties, 5);
    }

    private static RouterInfo info(RouterAddress... addresses) {
        RouterInfo info = new RouterInfo();
        info.setAddresses(Arrays.asList(addresses));
        return info;
    }

    private static Hash hash(int value) {
        byte[] data = new byte[32];
        data[0] = (byte) value;
        return Hash.create(data);
    }
}
