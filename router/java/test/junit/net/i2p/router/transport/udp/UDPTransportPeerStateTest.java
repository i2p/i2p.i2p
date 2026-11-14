package net.i2p.router.transport.udp;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Connection-ID dispatch must work for every negotiated SSU2 handshake version. */
public class UDPTransportPeerStateTest {
    private UDPTransport transport;
    private IntroductionManager introductions;
    private OutboundMessageFragments fragments;

    @Before
    public void setUp() throws Exception {
        RouterContext context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        transport = mock(UDPTransport.class, CALLS_REAL_METHODS);
        introductions = mock(IntroductionManager.class);
        fragments = mock(OutboundMessageFragments.class);
        // Avoid sockets, key generation and timer threads from the transport
        // constructor while exercising its real add/drop/indexing methods.
        setField("_context", context);
        setField("_log", mock(Log.class));
        setField("_addDropLock", new Object());
        setField("_peersByIdent", new ConcurrentHashMap<Hash, PeerState>());
        setField("_peersByRemoteHost", new ConcurrentHashMap<RemoteHostId, PeerState>());
        setField("_peersByConnID", new ConcurrentHashMap<Long, PeerState2>());
        setField("_recentlyClosedConnIDs", new ConcurrentHashMap<Long, PeerStateDestroyed>());
        setField("_introManager", introductions);
        setField("_fragments", fragments);
        doNothing().when(transport).markReachable(any(Hash.class), anyBoolean());
        doNothing().when(transport).sendDestroy(any(PeerState.class), anyInt());
    }

    @Test
    public void allSSU2VersionsAreIndexedForIncomingPacketDispatch() {
        for (int version : new int[] { 2, 3, 4 }) {
            PeerState2 peer = peer(version, version, 10000 + version, 20000 + version);
            assertTrue(transport.addRemotePeerState(peer));
            assertSame(peer, transport.getPeerState(peer.getRemotePeer()));
            assertSame(peer, transport.getPeerState(peer.getRemoteHostId()));
            assertSame("Receive connection ID must dispatch negotiated version " + version,
                       peer, transport.getPeerState(peer.getRcvConnID()));
        }
    }

    @Test
    public void droppingAnySSU2VersionRemovesItsActiveConnectionID() {
        try (MockedConstruction<PeerStateDestroyed> closing = mockConstruction(PeerStateDestroyed.class)) {
            for (int version : new int[] { 2, 3, 4 }) {
                PeerState2 peer = peer(version, version, 10000 + version, 20000 + version);
                transport.addRemotePeerState(peer);
                transport.dropPeer(peer, false, "test close");
                assertNull(transport.getPeerState(peer.getRemotePeer()));
                assertNull(transport.getPeerState(peer.getRemoteHostId()));
                assertNull(transport.getPeerState(peer.getRcvConnID()));
                assertSame("Closed connection must remain available for termination handling",
                           closing.constructed().get(version - 2), transport.getRecentlyClosed(peer.getRcvConnID()));
                verify(peer).dropOutbound();
                verify(fragments).dropPeer(peer);
                verify(introductions).remove(peer);
            }
            assertEquals(3, closing.constructed().size());
        }
    }

    @Test
    public void replacingAnySSU2VersionRetiresItsPreviousConnectionID() {
        try (MockedConstruction<PeerStateDestroyed> closing = mockConstruction(PeerStateDestroyed.class)) {
            for (int version : new int[] { 2, 3, 4 }) {
                PeerState2 previous = peer(version, version, 10000 + version, 20000 + version);
                int newVersion = version == 4 ? 2 : version + 1;
                PeerState2 replacement = peer(newVersion, version, 11000 + version, 21000 + version);
                transport.addRemotePeerState(previous);
                transport.addRemotePeerState(replacement);
                assertNull(transport.getPeerState(previous.getRcvConnID()));
                assertNull(transport.getPeerState(previous.getRemoteHostId()));
                assertSame(replacement, transport.getPeerState(replacement.getRemotePeer()));
                assertSame(replacement, transport.getPeerState(replacement.getRemoteHostId()));
                assertSame(replacement, transport.getPeerState(replacement.getRcvConnID()));
                assertSame(closing.constructed().get(version - 2), transport.getRecentlyClosed(previous.getRcvConnID()));
                verify(previous).dropOutbound();
                verify(replacement).loadFrom(previous);
                verify(transport).sendDestroy(previous, SSU2Util.REASON_REPLACED);
            }
            assertEquals(3, closing.constructed().size());
        }
    }

    @Test
    public void legacyPeerDoesNotEnterTheSSU2ConnectionIDIndex() {
        PeerState legacy = mock(PeerState.class);
        when(legacy.getVersion()).thenReturn(1);
        when(legacy.getRemotePeer()).thenReturn(hash(10));
        when(legacy.getRemoteHostId()).thenReturn(new RemoteHostId(new byte[] { 1, 2, 3, 4 }, 12345));
        try (MockedConstruction<PeerStateDestroyed> closing = mockConstruction(PeerStateDestroyed.class)) {
            assertTrue(transport.addRemotePeerState(legacy));
            transport.dropPeer(legacy, false, "test legacy close");
            assertTrue(closing.constructed().isEmpty());
            assertNull(transport.getPeerState(0L));
        }
    }

    private static PeerState2 peer(int version, int identity, int port, long connectionID) {
        PeerState2 peer = mock(PeerState2.class);
        when(peer.getVersion()).thenReturn(version);
        when(peer.getRemotePeer()).thenReturn(hash(identity));
        when(peer.getRemoteHostId()).thenReturn(new RemoteHostId(new byte[] { 1, 2, 3, 4 }, port));
        when(peer.getRcvConnID()).thenReturn(connectionID);
        return peer;
    }

    private static Hash hash(int value) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) value;
        return Hash.create(data);
    }

    private void setField(String name, Object value) throws Exception {
        Class<?> owner = UDPTransport.class;
        while (owner != null) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                field.set(transport, value);
                return;
            } catch (NoSuchFieldException missing) {
                owner = owner.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
