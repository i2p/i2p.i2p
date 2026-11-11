package net.i2p.router.transport.udp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.OrderedProperties;

import static net.i2p.router.transport.udp.OutboundEstablishState2.IntroState.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class EstablishmentManagerTest {
    private RouterContext _context;
    private UDPTransport _transport;
    private EstablishmentManager _manager;
    private OutboundEstablishState2 _state;
    private UDPAddress _address;
    private Hash _bob;
    private AtomicReference<OutboundEstablishState2.IntroState> _introState;

    @Before
    public void setUp() {
        _context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        when(_context.clock().now()).thenReturn(1000000L);
        when(_context.getProperty(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(_context.netDb().lookupRouterInfoLocally(any(Hash.class))).thenReturn(null);
        _transport = mock(UDPTransport.class);
        _manager = spy(new EstablishmentManager(_context, _transport));
        doNothing().when(_manager).establish(any(OutNetMessage.class));
        _bob = hash(1);
        _address = mock(UDPAddress.class);
        when(_address.getIntroducerCount()).thenReturn(1);
        when(_address.getIntroducerHash(0)).thenReturn(_bob);
        _state = mock(OutboundEstablishState2.class);
        when(_state.getRemoteAddress()).thenReturn(_address);
        when(_state.getIntroNonce()).thenReturn(42L);
        when(_state.getState()).thenReturn(OutboundEstablishState.OutboundState.OB_STATE_PENDING_INTRO);
        when(_state.getRemoteHostId()).thenReturn(new RemoteHostId(hash(2)));
        RouterIdentity identity = mock(RouterIdentity.class);
        when(identity.calculateHash()).thenReturn(hash(2));
        when(_state.getRemoteIdentity()).thenReturn(identity);
        _introState = new AtomicReference<OutboundEstablishState2.IntroState>(INTRO_STATE_INIT);
        when(_state.getIntroState(_bob)).thenAnswer(call -> _introState.get());
        doAnswer(call -> {
            _introState.set(call.getArgument(1));
            return null;
        }).when(_state).setIntroState(eq(_bob), any(OutboundEstablishState2.IntroState.class));
    }

    @Test
    public void testNewIntroducerConnectionIsAllowedToFinish() throws Exception {
        knownIntroducer();
        handlePendingIntro();
        assertEquals(INTRO_STATE_CONNECTING, _introState.get());
        handlePendingIntro();
        verify(_manager).establish(any(OutNetMessage.class));
        verify(_state, times(2)).introductionPending();
        verify(_state, never()).fail();
    }

    @Test
    public void testExistingIntroducerConnectionIsNotRejected() throws Exception {
        RouterAddress address = knownIntroducer();
        RemoteHostId id = new RemoteHostId(address.getIP(), address.getPort());
        outboundStates().put(id, mock(OutboundEstablishState.class));
        handlePendingIntro();
        assertEquals(INTRO_STATE_CONNECTING, _introState.get());
        verify(_manager, never()).establish(any(OutNetMessage.class));
        verify(_state).introductionPending();
        verify(_state, never()).fail();
    }

    @Test
    public void testRouterInfoLookupIsAllowedToFinish() throws Exception {
        handlePendingIntro();
        assertEquals(INTRO_STATE_LOOKUP_SENT, _introState.get());
        handlePendingIntro();
        verify(_context.netDb()).lookupRouterInfo(_bob, null, null, 10000);
        verify(_state, times(2)).introductionPending();
        verify(_state, never()).fail();
    }

    @Test
    public void testLateRouterInfoIsProcessedBeforeIntroductionDeadline() throws Exception {
        _state = newPendingState();
        outboundStates().put(_state.getRemoteHostId(), _state);
        // A lookup may take ten seconds. Polling must still notice a result
        // arriving at eight seconds, rather than backing off to the deadline.
        for (long elapsed = 0; elapsed <= 7500; elapsed += 1250) {
            when(_context.clock().now()).thenReturn(1000000L + elapsed);
            handleOutbound();
            assertEquals(1000000L + elapsed + 1250, _state.getNextSendTime());
        }
        when(_context.clock().now()).thenReturn(1008000L);
        knownIntroducer();
        when(_context.clock().now()).thenReturn(1008750L);
        handleOutbound();
        assertEquals(INTRO_STATE_CONNECTING, _state.getIntroState(_bob));
        assertEquals(1000000L, _state.getIntroSentTime());
        assertSame(_state, _manager.getOutboundState(_state.getRemoteHostId()));
        verify(_manager).establish(any(OutNetMessage.class));
    }

    @Test
    public void testPendingPollsPreserveRelayBackoffAndIntroductionDeadline() {
        OutboundEstablishState2 state = newPendingState();
        for (long elapsed = 0; elapsed <= 2500; elapsed += 1250) {
            when(_context.clock().now()).thenReturn(1000000L + elapsed);
            state.introductionPending();
        }
        when(_context.clock().now()).thenReturn(1003750L);
        state.introSent();
        assertEquals(1005000L, state.getNextSendTime());
        when(_context.clock().now()).thenReturn(1005000L);
        state.introSent();
        assertEquals(1007500L, state.getNextSendTime());
        assertEquals(1000000L, state.getIntroSentTime());
        when(_context.clock().now()).thenReturn(1014750L);
        state.introductionPending();
        assertEquals(1015000L, state.getNextSendTime());
        when(_context.clock().now()).thenReturn(1014900L);
        state.introSent();
        assertEquals(1015000L, state.getNextSendTime());
    }

    @Test
    public void testRelayRequestIsAllowedToFinish() throws Exception {
        _introState.set(INTRO_STATE_RELAY_REQUEST_SENT);
        handlePendingIntro();
        handlePendingIntro();
        verify(_state, times(2)).introductionPending();
        verify(_state, never()).fail();
    }

    @Test
    public void testPendingIntroductionStillExpiresAtIntroductionDeadline() throws Exception {
        _introState.set(INTRO_STATE_RELAY_REQUEST_SENT);
        when(_state.getIntroSentTime()).thenReturn(1000000L - EstablishmentManager.OB_MESSAGE_TIMEOUT);
        when(_state.getLifetime(1000000L)).thenReturn((long) EstablishmentManager.OB_MESSAGE_TIMEOUT);
        outboundStates().put(_state.getRemoteHostId(), _state);
        handleOutbound();
        verify(_state).fail();
        verify(_state, never()).introductionPending();
        assertNull(_manager.getOutboundState(_state.getRemoteHostId()));
    }

    @Test
    public void testPendingIntroductionStillExpiresAtOverallDeadline() throws Exception {
        _introState.set(INTRO_STATE_LOOKUP_SENT);
        when(_state.getIntroSentTime()).thenReturn(1000000L - 1000);
        when(_state.getLifetime(1000000L)).thenReturn(25000L);
        outboundStates().put(_state.getRemoteHostId(), _state);
        handleOutbound();
        verify(_state).fail();
        verify(_state, never()).introductionPending();
        assertNull(_manager.getOutboundState(_state.getRemoteHostId()));
    }

    @Test
    public void testExhaustedIntroducersFailImmediately() throws Exception {
        _introState.set(INTRO_STATE_REJECTED);
        handlePendingIntro();
        verify(_state).fail();
        verify(_state, never()).introductionPending();
    }

    @Test
    public void testIPv4SymmetricNatDoesNotPreventIPv6Tokens() {
        when(_transport.isSymNatted()).thenReturn(true);
        when(_transport.haveCapacity(95)).thenReturn(true);
        when(_context.random().nextLong()).thenReturn(123L);
        byte[] ip = new byte[16];
        ip[0] = 0x20;
        ip[1] = 0x01;
        ip[15] = 1;
        RemoteHostId peer = new RemoteHostId(ip, 12345);
        EstablishmentManager.Token token = _manager.getInboundToken(peer, 10000, false);
        assertNotNull(token);
        assertTrue(_manager.isInboundTokenValid(peer, token.getToken()));
    }

    @Test
    public void testIPv4SymmetricNatStillSuppressesOptionalTokens() {
        when(_transport.isSymNatted()).thenReturn(true);
        when(_transport.haveCapacity(95)).thenReturn(true);
        RemoteHostId peer = new RemoteHostId(new byte[] { 1, 2, 3, 4 }, 12345);
        assertNull(_manager.getInboundToken(peer, 10000, false));
    }

    @Test
    public void testIPv6TokensStillRequireCapacity() {
        when(_transport.isSymNatted()).thenReturn(true);
        when(_transport.haveCapacity(95)).thenReturn(false);
        RemoteHostId peer = new RemoteHostId(new byte[16], 12345);
        assertNull(_manager.getInboundToken(peer, 10000, false));
    }

    @Test
    public void testValidIntroducerAfterExpiredIntroducerIsAccepted() throws Exception {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("v", "2");
        options.setProperty("i", Base64.encode(new byte[32]));
        options.setProperty("itag0", "1");
        options.setProperty("ih0", hash(1).toBase64());
        options.setProperty("iexp0", "999");
        options.setProperty("itag1", "2");
        options.setProperty("ih1", hash(3).toBase64());
        options.setProperty("iexp1", "2000");
        RouterAddress address = new RouterAddress("SSU", options, 10);
        RouterInfo target = mock(RouterInfo.class, RETURNS_DEEP_STUBS);
        when(target.getIdentity().calculateHash()).thenReturn(hash(2));
        when(target.getCapabilities()).thenReturn("");
        when(_transport.getTargetAddress(target)).thenReturn(address);
        when(_transport.getSSUVersion(address)).thenReturn(2);
        when(_context.random().nextLong()).thenReturn(1L, 2L);
        OutNetMessage message = mock(OutNetMessage.class);
        when(message.getTarget()).thenReturn(target);
        DataMessage payload = new DataMessage(_context);
        when(message.getMessage()).thenReturn(payload);
        doCallRealMethod().when(_manager).establish(message);
        _manager.establish(message);
        OutboundEstablishState state = _manager.getOutboundState(new RemoteHostId(hash(2)));
        assertNotNull("The second, valid introducer must be considered", state);
        assertSame(message, state.getNextQueuedMessage());
        verify(_transport, never()).failed(eq(message), anyString());
    }

    private RouterAddress knownIntroducer() {
        RouterInfo info = mock(RouterInfo.class);
        when(_context.netDb().lookupRouterInfoLocally(_bob)).thenReturn(info);
        OrderedProperties options = new OrderedProperties();
        options.setProperty("host", "1.2.3.4");
        options.setProperty("port", "12345");
        RouterAddress address = new RouterAddress("SSU2", options, 10);
        when(_transport.getTargetAddresses(info)).thenReturn(Collections.singletonList(address));
        when(_transport.getSSUVersion(address)).thenReturn(2);
        when(_context.routerHash()).thenReturn(hash(4));
        return address;
    }

    private OutboundEstablishState2 newPendingState() {
        when(_context.routerHash()).thenReturn(hash(4));
        when(_context.random().nextLong()).thenReturn(1L, 2L);
        RouterIdentity identity = _state.getRemoteIdentity();
        RouterAddress address = new RouterAddress("SSU2", new OrderedProperties(), 10);
        return new OutboundEstablishState2(_context, _transport, null, new RemoteHostId(hash(2)),
                                           identity, "", false, new SessionKey(new byte[32]),
                                           address, _address, 2);
    }

    private void handlePendingIntro() throws Exception {
        Method method = EstablishmentManager.class.getDeclaredMethod("handlePendingIntro", OutboundEstablishState.class);
        method.setAccessible(true);
        method.invoke(_manager, _state);
    }

    private void handleOutbound() throws Exception {
        Method method = EstablishmentManager.class.getDeclaredMethod("handleOutbound");
        method.setAccessible(true);
        method.invoke(_manager);
    }

    @SuppressWarnings("unchecked")
    private Map<RemoteHostId, OutboundEstablishState> outboundStates() throws Exception {
        Field field = EstablishmentManager.class.getDeclaredField("_outboundStates");
        field.setAccessible(true);
        return (Map<RemoteHostId, OutboundEstablishState>) field.get(_manager);
    }

    private static Hash hash(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return new Hash(bytes);
    }
}
