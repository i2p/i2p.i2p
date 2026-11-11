package net.i2p.router.transport;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Alternate transports must remain usable after temporary failures. */
public class TransportManagerTest {
    private TransportManager manager;
    private RouterContext context;
    private OutNetMessage message;
    private RouterInfo target;
    private Hash peer;
    private Transport udp;
    private Transport ntcp;

    @Before
    public void setUp() throws Exception {
        context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        manager = mock(TransportManager.class, CALLS_REAL_METHODS);
        Log log = mock(Log.class);
        // A transient bid must work even while investigating a failure at DEBUG.
        when(log.shouldLog(Log.DEBUG)).thenReturn(true);
        setField("_context", context);
        setField("_log", log);
        udp = mock(Transport.class);
        ntcp = mock(Transport.class);
        when(udp.getStyle()).thenReturn("SSU");
        when(ntcp.getStyle()).thenReturn("NTCP");
        Map<String, Transport> transports = new LinkedHashMap<String, Transport>();
        transports.put("SSU", udp);
        transports.put("NTCP", ntcp);
        setField("_transports", transports);
        message = mock(OutNetMessage.class);
        target = mock(RouterInfo.class, RETURNS_DEEP_STUBS);
        peer = Hash.create(new byte[Hash.HASH_LENGTH]);
        when(target.getIdentity().calculateHash()).thenReturn(peer);
        when(message.getTarget()).thenReturn(target);
        when(message.getMessageSize()).thenReturn(1024);
        when(message.getFailedTransports()).thenReturn(Collections.<String>emptyList());
    }

    @Test
    public void udpCapacityFailureDoesNotPreventTcpFallbackWithDebugLogging() {
        when(udp.bid(target, 1024)).thenReturn(bid(udp, TransportBid.TRANSIENT_FAIL));
        TransportBid tcpBid = bid(ntcp, 100);
        when(ntcp.bid(target, 1024)).thenReturn(tcpBid);
        assertSame(tcpBid, manager.getNextBid(message));
        verify(message).transportFailed("SSU");
        verify(context.banlist(), never()).banlistRouter(eq(peer), anyString());
    }

    @Test
    public void allTemporaryCapacityFailuresReturnNoBidWithoutBanningPeer() {
        when(udp.bid(target, 1024)).thenReturn(bid(udp, TransportBid.TRANSIENT_FAIL));
        when(ntcp.bid(target, 1024)).thenReturn(bid(ntcp, TransportBid.TRANSIENT_FAIL));
        assertNull(manager.getNextBid(message));
        verify(message).transportFailed("SSU");
        verify(message).transportFailed("NTCP");
        verify(context.banlist(), never()).banlistRouter(eq(peer), anyString());
    }

    @Test
    public void unreachableUdpPeerStillUsesOutboundTcp() {
        when(udp.isUnreachable(peer)).thenReturn(true);
        TransportBid tcpBid = bid(ntcp, 100);
        when(ntcp.bid(target, 1024)).thenReturn(tcpBid);
        assertSame(tcpBid, manager.getNextBid(message));
        verify(udp, never()).bid(any(RouterInfo.class), anyInt());
        verify(message).transportFailed("SSU");
        verify(context.banlist(), never()).banlistRouter(eq(peer), anyString());
    }

    @Test
    public void transportThatAlreadyFailedMessageIsNotRetried() {
        when(message.getFailedTransports()).thenReturn(Collections.singletonList("SSU"));
        TransportBid tcpBid = bid(ntcp, 100);
        when(ntcp.bid(target, 1024)).thenReturn(tcpBid);
        assertSame(tcpBid, manager.getNextBid(message));
        verify(udp, never()).bid(any(RouterInfo.class), anyInt());
    }

    private static TransportBid bid(Transport transport, int latency) {
        TransportBid bid = new TransportBid();
        bid.setTransport(transport);
        bid.setLatencyMs(latency);
        return bid;
    }

    private void setField(String name, Object value) throws Exception {
        Field field = TransportManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }
}
