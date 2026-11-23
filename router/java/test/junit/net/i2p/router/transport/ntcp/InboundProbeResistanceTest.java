package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Invalid message-1 prefixes share the existing bounded no-response drain. */
public class InboundProbeResistanceTest {
    private RouterContext context;
    private NTCPTransport transport;
    private NTCPConnection connection;
    private InboundEstablishState state;

    @Before
    public void setUp() {
        context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        when(context.routerHash()).thenReturn(Hash.create(new byte[32]));
        when(context.random().nextInt(128)).thenReturn(64);
        transport = mock(NTCPTransport.class);
        when(transport.getNTCP2StaticIV()).thenReturn(new byte[16]);
        connection = mock(NTCPConnection.class);
        when(connection.isInbound()).thenReturn(true);
        state = new InboundEstablishState(context, transport, connection);
    }

    @Test
    public void replayDoesNotCloseImmediatelyOrStartCryptographicHandshake() {
        when(transport.isHXHIValid(any(byte[].class))).thenReturn(false);
        state.receive(ByteBuffer.wrap(prefix(32)));
        assertFalse(state.isCorrupt());
        assertEquals(EstablishBase.State.IB_NTCP2_READ_RANDOM, state._state);
        verify(transport, never()).getXDHFactory();
        verify(transport, never()).getPumper();
    }

    @Test
    public void decodedZeroKeyUsesTheSameDrainWithoutAnIPBan() {
        when(transport.isHXHIValid(any(byte[].class))).thenReturn(true);
        // The mock AES engine leaves the all-zero input unchanged.
        state.receive(ByteBuffer.allocate(32));
        assertFalse(state.isCorrupt());
        assertEquals(EstablishBase.State.IB_NTCP2_READ_RANDOM, state._state);
        verify(transport, never()).getXDHFactory();
        verify(transport, never()).getPumper();
    }

    @Test
    public void coalescedBytesCountOnceAndFragmentedDrainConsumesItsBuffers() {
        state.receive(ByteBuffer.wrap(prefix(48)));
        assertFalse(state.isCorrupt());
        ByteBuffer first = ByteBuffer.allocate(47);
        state.receive(first);
        assertEquals(first.limit(), first.position());
        assertFalse("16 coalesced plus 47 subsequent bytes leave one byte", state.isCorrupt());
        ByteBuffer last = ByteBuffer.allocate(1);
        state.receive(last);
        assertEquals(last.limit(), last.position());
        assertTrue(state.isCorrupt());
    }

    @Test
    public void alreadyAvailableDrainBudgetClosesWithoutAdditionalReads() {
        state.receive(ByteBuffer.wrap(prefix(96)));
        assertTrue(state.isCorrupt());
        verify(transport, never()).getXDHFactory();
    }

    @Test
    public void existingAuthenticationFailureDrainAdvancesReadBuffer() throws Exception {
        // Exercise the established READ_RANDOM path independently of where
        // authentication failed (base or hybrid handshake).
        state._state = EstablishBase.State.IB_NTCP2_READ_RANDOM;
        state._received = 0;
        java.lang.reflect.Field remaining = InboundEstablishState.class.getDeclaredField("_padlen1");
        remaining.setAccessible(true);
        remaining.setInt(state, 64);
        ByteBuffer first = ByteBuffer.allocate(63);
        state.receive(first);
        assertEquals(63, first.position());
        assertFalse(state.isCorrupt());
        ByteBuffer last = ByteBuffer.allocate(1);
        state.receive(last);
        assertEquals(1, last.position());
        assertTrue(state.isCorrupt());
    }

    @Test
    public void validFragmentedPrefixStillWaitsForTheMessageBody() {
        when(transport.isHXHIValid(any(byte[].class))).thenReturn(true);
        state.receive(ByteBuffer.wrap(prefix(16)));
        assertEquals(EstablishBase.State.IB_INIT, state._state);
        state.receive(ByteBuffer.wrap(prefix(16)));
        assertFalse(state.isCorrupt());
        assertEquals(EstablishBase.State.IB_NTCP2_GOT_X, state._state);
        verify(context.random(), never()).nextInt(anyInt());
    }

    private static byte[] prefix(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 1);
        return bytes;
    }
}
