package net.i2p.router.transport.udp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SimpleTimer2;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise real idle-session maintenance without sockets or background timers. */
public class UDPTransportKeepaliveTest {
    private static final long NOW = 1000000;
    private static final int LOCAL_PORT = 23456;
    private UDPTransport transport;
    private PacketBuilder2 builder;
    private Map<Hash, PeerState> peers;

    @Before
    public void setUp() throws Exception {
        RouterContext context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        when(context.clock().now()).thenReturn(NOW);
        transport = mock(UDPTransport.class, CALLS_REAL_METHODS);
        builder = mock(PacketBuilder2.class);
        peers = new LinkedHashMap<Hash, PeerState>();
        setField("_context", context);
        setField("_log", mock(Log.class));
        setField("_peersByIdent", peers);
        setField("_packetBuilder2", builder);
        setField("_expireTimeout", (long) UDPTransport.EXPIRE_TIMEOUT);
        setField("_reachabilityStatus", Status.REJECT_UNSOLICITED);
        UDPEndpoint endpoint = mock(UDPEndpoint.class);
        when(endpoint.isIPv4()).thenReturn(true);
        when(endpoint.getListenPort()).thenReturn(LOCAL_PORT);
        setField("_endpoints", Collections.singletonList(endpoint));
        doReturn(true).when(transport).haveCapacity(33);
        doReturn(address(LOCAL_PORT)).when(transport).getCurrentExternalAddress(false);
        // The published address of a firewalled router has no port, so this
        // accessor falls back to the requested local port in production.
        doReturn(LOCAL_PORT).when(transport).getExternalPort(false);
        doNothing().when(transport).send(any(UDPPacket.class));
    }

    @Test
    public void symmetricNATRefreshesEveryDestinationEvenWithAPreservedPort() throws Exception {
        for (Status status : new Status[] { Status.DIFFERENT, Status.IPV4_SNAT_IPV6_OK,
                                            Status.IPV4_SNAT_IPV6_UNKNOWN }) {
            setUp();
            setField("_reachabilityStatus", status);
            PeerState2 first = addPeer(false, 1);
            PeerState2 second = addPeer(false, 2);
            runMaintenance();
            verify(builder).buildPing(first);
            verify(builder).buildPing(second);
        }
    }

    @Test
    public void unpublishedTranslatedPortRefreshesEveryDestination() throws Exception {
        doReturn(address(LOCAL_PORT + 1)).when(transport).getCurrentExternalAddress(false);
        PeerState2 first = addPeer(false, 1);
        PeerState2 second = addPeer(false, 2);
        runMaintenance();
        verify(builder).buildPing(first);
        verify(builder).buildPing(second);
    }

    @Test
    public void unknownExternalMappingRefreshesEveryDestination() throws Exception {
        doReturn(null).when(transport).getCurrentExternalAddress(false);
        PeerState2 first = addPeer(false, 1);
        PeerState2 second = addPeer(false, 2);
        runMaintenance();
        verify(builder).buildPing(first);
        verify(builder).buildPing(second);
    }

    @Test
    public void ipv6KeepaliveDoesNotConsumeIPv4Keepalive() throws Exception {
        PeerState2 ipv6 = addPeer(true, 1);
        PeerState2 ipv4 = addPeer(false, 2);
        runMaintenance();
        verify(builder).buildPing(ipv6);
        verify(builder).buildPing(ipv4);
    }

    @Test
    public void ipv4KeepaliveDoesNotSuppressIPv6FirewallSessions() throws Exception {
        PeerState2 ipv4 = addPeer(false, 1);
        PeerState2 firstIPv6 = addPeer(true, 2);
        PeerState2 secondIPv6 = addPeer(true, 3);
        runMaintenance();
        verify(builder).buildPing(ipv4);
        verify(builder).buildPing(firstIPv6);
        verify(builder).buildPing(secondIPv6);
    }

    @Test
    public void knownPreservedNonSymmetricMappingKeepsSingleIPv4Optimization() throws Exception {
        PeerState2 first = addPeer(false, 1);
        PeerState2 second = addPeer(false, 2);
        runMaintenance();
        verify(builder).buildPing(first);
        verify(builder, never()).buildPing(second);
    }

    @Test
    public void recentTrafficDoesNotNeedKeepalive() throws Exception {
        setField("_reachabilityStatus", Status.DIFFERENT);
        PeerState2 sent = addPeer(false, 1);
        PeerState2 received = addPeer(false, 2);
        when(sent.getLastSendOrPingTime()).thenReturn(NOW - 1000);
        when(received.getLastReceiveTime()).thenReturn(NOW - 1000);
        runMaintenance();
        verify(builder, never()).buildPing(any(PeerState2.class));
    }

    private PeerState2 addPeer(boolean ipv6, int id) throws Exception {
        // Maintenance uses four identity-hash slices. Put peers in the first
        // slice so this test exercises the per-cycle budget deterministically.
        PeerState2 peer = null;
        for (int attempt = 0; attempt < 1000; attempt++) {
            PeerState2 candidate = mock(PeerState2.class);
            if ((candidate.hashCode() & 3) == 1) {
                peer = candidate;
                break;
            }
        }
        assertNotNull("Could not create a peer in the first maintenance slice", peer);
        when(peer.isIPv6()).thenReturn(ipv6);
        when(peer.getLastReceiveTime()).thenReturn(NOW - 20000);
        when(peer.getLastSendTime()).thenReturn(NOW - 20000);
        when(peer.getLastSendOrPingTime()).thenReturn(NOW - 20000);
        when(peer.getKeyEstablishedTime()).thenReturn(NOW);
        UDPPacket packet = mock(UDPPacket.class);
        when(builder.buildPing(peer)).thenReturn(packet);
        byte[] hash = new byte[Hash.HASH_LENGTH];
        hash[0] = (byte) id;
        peers.put(Hash.create(hash), peer);
        return peer;
    }

    private void runMaintenance() throws Exception {
        Class<?> type = Class.forName(UDPTransport.class.getName() + "$ExpirePeerEvent");
        Constructor<?> constructor = type.getDeclaredConstructor(UDPTransport.class);
        constructor.setAccessible(true);
        SimpleTimer2.TimedEvent event = (SimpleTimer2.TimedEvent) constructor.newInstance(transport);
        event.timeReached();
    }

    private static RouterAddress address(int port) {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("host", "8.8.8.8");
        options.setProperty("port", Integer.toString(port));
        return new RouterAddress("SSU2", options, 5);
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
