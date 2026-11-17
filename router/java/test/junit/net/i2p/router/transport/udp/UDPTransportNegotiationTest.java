package net.i2p.router.transport.udp;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Base64;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.OrderedProperties;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Advertised handshake versions must be supported and fit the selected path. */
public class UDPTransportNegotiationTest {
    private UDPTransport transport;

    @Before
    public void setUp() {
        transport = mock(UDPTransport.class, CALLS_REAL_METHODS);
        doReturn(1500).when(transport).getSSU2MTU(anyBoolean());
        doReturn(TransportUtil.IPv6Config.IPV6_ENABLED).when(transport).getIPv6Config();
        doReturn(null).when(transport).getExternalIP();
    }

    @Test
    public void supportsBaseVersionAtAnyTokenPosition() {
        for (String version : new String[] { "2", "2,12", "12,2", "12,2,4" })
            assertEquals(2, transport.getSSUVersion(address("SSU2", version, null, null, "1500")));
    }

    @Test
    public void rejectsLookalikeBaseVersions() {
        for (String version : new String[] { "12", "20", "22", "3,12", "" })
            assertEquals(0, transport.getSSUVersion(address("SSU2", version, "4,3", null, "1500")));
    }

    @Test
    public void lookalikePQVersionsDoNotEnableUnadvertisedHybrids() {
        for (String pq : new String[] { "14", "13", "34", "43", "14,13" })
            assertEquals(2, transport.getSSUVersion(address("SSU2", "2", pq, null, "1500")));
        assertEquals(3, transport.getSSUVersion(address("SSU2", "2", "14,3", null, "1500")));
        assertEquals(4, transport.getSSUVersion(address("SSU2", "2", "13,4", null, "1500")));
    }

    @Test
    public void prefersSupported768HybridRegardlessOfListOrder() {
        for (String pq : new String[] { "4,3", "3,4", "4" })
            assertEquals(4, transport.getSSUVersion(address("SSU2", "2", pq, null, "1500")));
    }

    @Test
    public void lowRemoteMtuUsesAdvertised512Hybrid() {
        assertEquals(3, transport.getSSUVersion(address("SSU2", "2", "4,3", null, "1280")));
        assertEquals(2, transport.getSSUVersion(address("SSU2", "2", "4", null, "1280")));
    }

    @Test
    public void lowLocalMtuUsesAdvertised512Hybrid() {
        doReturn(1280).when(transport).getSSU2MTU(false);
        assertEquals(3, transport.getSSUVersion(address("SSU2", "2", "4,3", null, "1500")));
    }

    @Test
    public void ipv6IntroducerUsesIpv6MtuInsteadOfIpv4Mtu() {
        // Between the IPv4 and IPv6 minimum packet sizes for MLKEM768.
        assertEquals(3, transport.getSSUVersion(address("SSU2", "2", "4,3", "6", "1320")));
        assertEquals(4, transport.getSSUVersion(address("SSU2", "2", "4,3", "4", "1320")));
    }

    @Test
    public void ipv6IntroducerUsesLocalIpv6Mtu() {
        doReturn(1280).when(transport).getSSU2MTU(true);
        assertEquals(3, transport.getSSUVersion(address("SSU2", "2", "4,3", "6", "1500")));
    }

    @Test
    public void preservesUnknownMtuNegotiation() {
        doReturn(0).when(transport).getSSU2MTU(anyBoolean());
        assertEquals(4, transport.getSSUVersion(address("SSU2", "2", "4,3", null, null)));
    }

    @Test
    public void rejectsExplicitMtuBelowProtocolMinimum() {
        assertEquals(0, transport.getSSUVersion(address("SSU2", "2", "4,3", null, "1279")));
        assertEquals(0, transport.getSSUVersion(address("SSU2", "2", "4,3", null, "-1")));
        doReturn(1279).when(transport).getSSU2MTU(false);
        assertEquals(0, transport.getSSUVersion(address("SSU2", "2", "4,3", null, "1500")));
    }

    @Test
    public void malformedKeyEntryDoesNotHideValidAddress() {
        RouterAddress valid = address("SSU2", "2", "4,3", null, "1500");
        for (String option : new String[] { "i", "s" }) {
            for (String value : new String[] { "invalid!", Base64.encode(new byte[31]), Base64.encode(new byte[33]) }) {
                OrderedProperties props = properties("2", "4,3", null, "1500");
                props.setProperty(option, value);
                RouterInfo target = mock(RouterInfo.class);
                doReturn(Arrays.asList(new RouterAddress("SSU2", props, 10), valid))
                    .when(transport).getTargetAddresses(target);
                assertSame(valid, transport.getTargetAddress(target));
            }
        }
    }

    @Test
    public void unsupportedVersionEntryDoesNotHideValidLegacyStyleAddress() {
        RouterAddress invalid = address("SSU2", "12", "4,3", null, "1500");
        RouterAddress valid = address("SSU", "4,2", "4,3", null, "1500");
        RouterInfo target = mock(RouterInfo.class);
        doReturn(Arrays.asList(invalid, valid)).when(transport).getTargetAddresses(target);
        assertSame(valid, transport.getTargetAddress(target));
    }

    private static RouterAddress address(String style, String version, String pq, String caps, String mtu) {
        return new RouterAddress(style, properties(version, pq, caps, mtu), 10);
    }

    private static OrderedProperties properties(String version, String pq, String caps, String mtu) {
        OrderedProperties props = new OrderedProperties();
        props.setProperty("v", version);
        byte[] key = new byte[32];
        key[0] = 9;
        props.setProperty("s", Base64.encode(key));
        props.setProperty("i", Base64.encode(key));
        if (pq != null)
            props.setProperty("pq", pq);
        if (caps != null) {
            props.setProperty("caps", caps);
            props.setProperty("itag0", "123");
        } else {
            props.setProperty("host", "8.8.8.8");
            props.setProperty("port", "12345");
        }
        if (mtu != null)
            props.setProperty("mtu", mtu);
        return props;
    }
}
