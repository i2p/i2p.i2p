package net.i2p.router.transport.udp;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Base64;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.RouterContext;
import net.i2p.util.OrderedProperties;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PeerTestManagerTest {
    private PeerTestManager manager;
    private UDPTransport transport;
    private PeerState2 fromPeer;
    private RemoteHostId from;

    @Before
    public void setUp() {
        RouterContext context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        transport = mock(UDPTransport.class);
        manager = new PeerTestManager(context, transport);
        fromPeer = mock(PeerState2.class);
        from = new RemoteHostId(new byte[] { 1, 2, 3, 4 }, 12345);
        clearInvocations(transport);
    }

    @Test
    public void validIntroductionKeyIsPreserved() {
        byte[] key = new byte[SessionKey.KEYSIZE_BYTES];
        Arrays.fill(key, (byte) 0xa5);
        SessionKey parsed = PeerTestManager.getIntroKey(address(Base64.encode(key)));
        assertNotNull(parsed);
        assertArrayEquals(key, parsed.getData());
    }

    @Test
    public void wrongLengthIntroductionKeysAreRejectedWithoutThrowing() {
        for (int length : new int[] { 0, 1, 16, 31, 33, 64 })
            assertNull("Decoded length " + length,
                       PeerTestManager.getIntroKey(address(Base64.encode(new byte[length]))));
    }

    @Test
    public void malformedOrMissingIntroductionKeysAreRejected() {
        assertNull(PeerTestManager.getIntroKey(null));
        assertNull(PeerTestManager.getIntroKey(address(null)));
        assertNull(PeerTestManager.getIntroKey(address("!not-base64!")));
    }

    @Test
    public void truncatedPeerTestHeadersAreIgnored() {
        manager.receiveTest(from, fromPeer, 1, 0, null, null);
        for (int length = 0; length < 10; length++) {
            byte[] data = new byte[length];
            if (length > 0)
                data[0] = 2;
            manager.receiveTest(from, fromPeer, 1, 0, null, data);
        }
        verifyNoInteractions(transport, fromPeer);
    }

    @Test
    public void truncatedPeerTestAddressesAreIgnored() {
        for (int addressLength : new int[] { 6, 18 }) {
            for (int length = 10; length < 10 + addressLength; length++) {
                byte[] data = new byte[length];
                data[0] = 2;
                data[9] = (byte) addressLength;
                manager.receiveTest(from, fromPeer, 1, 0, null, data);
            }
        }
        verifyNoInteractions(transport, fromPeer);
    }

    private static RouterAddress address(String key) {
        OrderedProperties options = new OrderedProperties();
        if (key != null)
            options.setProperty("i", key);
        return new RouterAddress("SSU2", options, 10);
    }
}
