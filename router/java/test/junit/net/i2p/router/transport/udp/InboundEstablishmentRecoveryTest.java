package net.i2p.router.transport.udp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;

import static net.i2p.router.transport.udp.InboundEstablishState.InboundState.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Lost pre-handshake packets must not prevent the next connection attempt. */
public class InboundEstablishmentRecoveryTest {
    private static final long NOW = 1000000L;
    private RouterContext _context;
    private UDPTransport _transport;
    private EstablishmentManager _manager;
    private RemoteHostId _peer;

    @Before
    public void setUp() {
        _context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        when(_context.clock().now()).thenReturn(NOW);
        when(_context.getProperty(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        _transport = mock(UDPTransport.class);
        _manager = new EstablishmentManager(_context, _transport);
        _peer = new RemoteHostId(new byte[] { 1, 2, 3, 4 }, 12345);
    }

    @Test
    public void testLostTokenRequestDoesNotBanEndpoint() throws Exception {
        expire(IB_STATE_TOKEN_REQUEST_RECEIVED);
        assertFalse(bans().containsKey(_peer));
    }

    @Test
    public void testLostRetryDoesNotBanEndpoint() throws Exception {
        expire(IB_STATE_RETRY_SENT);
        assertFalse(bans().containsKey(_peer));
    }

    @Test
    public void testStaleTokenDoesNotBanEndpoint() throws Exception {
        expire(IB_STATE_REQUEST_BAD_TOKEN_RECEIVED);
        assertFalse(bans().containsKey(_peer));
    }

    @Test
    public void testSessionCreatedTimeoutRetainsExpensiveHandshakeProtection() throws Exception {
        expire(IB_STATE_CREATED_SENT);
        assertEquals(Long.valueOf(NOW + 39*60*1000), bans().get(_peer));
    }

    @Test
    public void testPartialConfirmationRetainsExpensiveHandshakeProtection() throws Exception {
        expire(IB_STATE_CONFIRMED_PARTIALLY);
        assertTrue(bans().get(_peer) > NOW);
    }

    @Test
    public void testNewInboundStateSurvivesOldTimeoutCleanup() throws Exception {
        InboundEstablishState2 old = state(IB_STATE_RETRY_SENT);
        InboundEstablishState2 replacement = state(IB_STATE_RETRY_SENT);
        states().put(_peer, replacement);
        Method expire = EstablishmentManager.class.getDeclaredMethod("processExpired", InboundEstablishState.class);
        expire.setAccessible(true);
        expire.invoke(_manager, old);
        assertSame(replacement, states().get(_peer));
        assertFalse(bans().containsKey(_peer));
    }

    private void expire(InboundEstablishState.InboundState phase) throws Exception {
        InboundEstablishState2 state = state(phase);
        OutNetMessage queued = mock(OutNetMessage.class);
        when(state.getNextQueuedMessage()).thenReturn(queued, (OutNetMessage) null);
        states().put(_peer, state);
        Method handle = EstablishmentManager.class.getDeclaredMethod("handleInbound");
        handle.setAccessible(true);
        handle.invoke(_manager);
        assertFalse("Expired state must release its establishment slot", states().containsKey(_peer));
        verify(_transport).failed(eq(queued), anyString());
        verify(_context.banlist(), never()).banlistRouter(any(Hash.class), anyString());
    }

    private InboundEstablishState2 state(InboundEstablishState.InboundState phase) {
        InboundEstablishState2 state = mock(InboundEstablishState2.class);
        when(state.getState()).thenReturn(phase);
        when(state.getRemoteHostId()).thenReturn(_peer);
        when(state.getLifetime(NOW)).thenReturn(13000L);
        return state;
    }

    @SuppressWarnings("unchecked")
    private Map<RemoteHostId, InboundEstablishState> states() throws Exception {
        Field field = EstablishmentManager.class.getDeclaredField("_inboundStates");
        field.setAccessible(true);
        return (Map<RemoteHostId, InboundEstablishState>) field.get(_manager);
    }

    @SuppressWarnings("unchecked")
    private Map<RemoteHostId, Long> bans() throws Exception {
        Field field = EstablishmentManager.class.getDeclaredField("_inboundBans");
        field.setAccessible(true);
        return (Map<RemoteHostId, Long>) field.get(_manager);
    }
}
