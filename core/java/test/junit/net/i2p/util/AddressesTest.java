package net.i2p.util;

import org.junit.Test;

import java.net.Inet6Address;
import java.net.UnknownHostException;

import static org.junit.Assert.*;

/**
 * @since 0.9.49
 */
public class AddressesTest {

    @Test
    public void getIPNull() {
        assertNull(Addresses.getIP(null));
    }

    @Test
    public void getIPEmptyString() {
        assertNull(Addresses.getIP(""));
    }

    @Test
    public void getIPWithIPString() {
        byte[] address = {
            1, 2, 3, 4
        };
        assertArrayEquals(address, Addresses.getIP("1.2.3.4"));
    }

    @Test
    public void getPort() {
        assertEquals(80, Addresses.getPort("80"));
    }

    @Test
    public void getPort__invalidPort() {
        String[] strings = {
            "",
            " 80",
            "-100",
            "a",
            "99999",
            null
        };
        for (String string : strings) {
            assertEquals(0, Addresses.getPort(string));
        }
    }

    @Test
    public void isIPAddress() {
        assertTrue(Addresses.isIPAddress("127.0.0.1"));
        assertTrue(Addresses.isIPAddress("::1"));
    }

    @Test
    public void isIPv6Address() {
        assertTrue(Addresses.isIPv6Address("::1"));
        assertFalse(Addresses.isIPv6Address(""));
    }

    @Test
    public void isIPv4Address() {
        assertTrue(Addresses.isIPv4Address("127.0.0.1"));
        assertFalse(Addresses.isIPv4Address(""));
    }

    /**
     * Should always return false when the address isn't in the cache
     */
    @Test
    public void isDynamic() throws UnknownHostException {
        String host = "localhost";
        byte[] address = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertFalse(Addresses.isDynamic((Inet6Address) Inet6Address.getByAddress(host, address)));
    }

    /**
     * Should always return false when the address isn't in the cache
     */
    @Test
    public void isDeprecated() throws UnknownHostException {
        String host = "localhost";
        byte[] address = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertFalse(Addresses.isDeprecated((Inet6Address) Inet6Address.getByAddress(host, address)));
    }

    @Test
    public void testToString() {
        byte[] address = {127, 0, 0, 1};
        assertEquals("127.0.0.1", Addresses.toString(address));
    }

    @Test
    public void testToString__ipv4withPort() {
        byte[] address = {127, 0, 0, 1};
        assertEquals("127.0.0.1:80", Addresses.toString(address, 80));
    }

    @Test
    public void testToString__ipv6withPort() {
        byte[] address = {
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 1,
        };
        assertEquals("[0:0:0:0:0:0:0:1]:80", Addresses.toString(address, 80));
    }

    @Test
    public void testToString__null() {
        assertEquals("null", Addresses.toString(null));
    }

    @Test
    public void testToString__nullWithPort() {
        assertEquals("null:80", Addresses.toString(null, 80));
    }

    @Test
    public void testToString__badLength() {
        byte[] address = {1};
        assertTrue(Addresses.toString(address).startsWith("bad IP length"));
    }

    @Test
    public void testToString__badLengthWithPort() {
        byte[] address = {1};
        String string = Addresses.toString(address, 80);
        String expectedStartString = "(bad IP length";
        assertTrue(
            String.format("%s doesn't start with: %s", string, expectedStartString),
            string.startsWith(expectedStartString)
        );
        String expectedEndString = "80";
        assertTrue(
            String.format("%s doesn't end with: %s", string, expectedEndString),
            string.endsWith(expectedEndString)
        );
    }
}
