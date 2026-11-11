package net.i2p.router.transport.ntcp;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Base64;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.OrderedProperties;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** A malformed address must not hide a peer's working TCP route. */
public class NTCPTransportTest {
    private NTCPTransport transport;
    private RouterInfo target;
    private Method selectAddress;

    @Before
    public void setUp() throws Exception {
        // Avoid network threads/key generation; exercise the actual address
        // selection and all advertised address/handshake checks.
        transport = mock(NTCPTransport.class, CALLS_REAL_METHODS);
        doReturn(TransportUtil.IPv6Config.IPV6_ENABLED).when(transport).getIPv6Config();
        target = mock(RouterInfo.class);
        selectAddress = NTCPTransport.class.getDeclaredMethod("getTargetAddress", RouterInfo.class);
        selectAddress.setAccessible(true);
    }

    @Test
    public void validNtcp2AddressRemainsUsable() throws Exception {
        RouterAddress valid = address(validKey(), validIV(), "2");
        addresses(valid);
        assertSame(valid, selected());
    }

    @Test
    public void malformedFirstAddressDoesNotHideValidFallback() throws Exception {
        RouterAddress valid = address(validKey(), validIV(), "2");
        addresses(address(validKey(), "invalid!", "2"), valid);
        assertSame(valid, selected());
    }

    @Test
    public void invalidKeyEncodingsAreSkipped() throws Exception {
        RouterAddress valid = address(validKey(), validIV(), "2");
        byte[] highBit = new byte[32];
        highBit[31] = (byte) 0x80;
        for (String key : new String[] { "invalid!", Base64.encode(new byte[31]),
                                        Base64.encode(new byte[32]), Base64.encode(highBit) }) {
            addresses(address(key, validIV(), "2"), valid);
            assertSame("Skip handshake-invalid static key " + key, valid, selected());
        }
    }

    @Test
    public void invalidIVLengthsAndZeroIVAreSkipped() throws Exception {
        RouterAddress valid = address(validKey(), validIV(), "2");
        for (String iv : new String[] { Base64.encode(new byte[15]), Base64.encode(new byte[16]),
                                       Base64.encode(new byte[17]) }) {
            addresses(address(validKey(), iv, "2"), valid);
            assertSame(valid, selected());
        }
    }

    @Test
    public void allMalformedAddressesReturnNoRoute() throws Exception {
        addresses(address("invalid!", validIV(), "2"), address(validKey(), "invalid!", "2"));
        assertNull(selected());
    }

    @Test
    public void outboundOnlyAddressStillRequiresAnotherRoute() throws Exception {
        OrderedProperties properties = new OrderedProperties();
        properties.setProperty("v", "2");
        properties.setProperty("s", validKey());
        properties.setProperty("caps", "4");
        RouterAddress outboundOnly = new RouterAddress("NTCP2", properties, 10);
        addresses(outboundOnly);
        assertNull(selected());
    }

    @Test
    public void validVersionListAndPQRouteRemainUsable() throws Exception {
        OrderedProperties properties = options(validKey(), validIV(), "2,3");
        properties.setProperty("pq", "3");
        RouterAddress valid = new RouterAddress("NTCP2", properties, 10);
        addresses(valid);
        assertSame(valid, selected());
    }

    @Test
    public void supportedBaseVersionCanFollowFutureVersion() throws Exception {
        RouterAddress valid = address(validKey(), validIV(), "12,2");
        addresses(valid);
        assertSame(valid, selected());
    }

    @Test
    public void lookalikeBaseVersionDoesNotHideSupportedAlternative() throws Exception {
        RouterAddress valid = address(validKey(), validIV(), "2");
        addresses(address(validKey(), validIV(), "12,3"), valid);
        assertSame(valid, selected());
    }

    private void addresses(RouterAddress... addresses) {
        doReturn(Arrays.asList(addresses)).when(transport).getTargetAddresses(target);
    }

    private RouterAddress selected() throws Exception {
        return (RouterAddress) selectAddress.invoke(transport, target);
    }

    private static RouterAddress address(String key, String iv, String version) {
        return new RouterAddress("NTCP2", options(key, iv, version), 10);
    }

    private static OrderedProperties options(String key, String iv, String version) {
        OrderedProperties properties = new OrderedProperties();
        properties.setProperty("host", "8.8.8.8");
        properties.setProperty("port", "12345");
        properties.setProperty("v", version);
        properties.setProperty("s", key);
        properties.setProperty("i", iv);
        return properties;
    }

    private static String validKey() {
        byte[] key = new byte[32];
        key[0] = 9;
        return Base64.encode(key);
    }

    private static String validIV() {
        byte[] iv = new byte[16];
        iv[0] = 1;
        return Base64.encode(iv);
    }
}
