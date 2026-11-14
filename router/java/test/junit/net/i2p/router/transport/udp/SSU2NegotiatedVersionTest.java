package net.i2p.router.transport.udp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.util.OrderedProperties;

import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.*;
import static net.i2p.router.transport.udp.OutboundEstablishState2.IntroState.*;
import static net.i2p.router.transport.udp.SSU2Util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Transport versions 2–4 retain the version 2 signed relay format. */
public class SSU2NegotiatedVersionTest {
    private static final long NOW = 1800000000000L;
    private static final long NONCE = 123456L;
    private static final long TOKEN = 98765L;
    private static final byte[] IP = { 8, 8, 8, 8 };
    private static SigningPublicKey publicKey;
    private static SigningPrivateKey privateKey;
    private RouterContext context;
    private UDPTransport transport;
    private EstablishmentManager manager;
    private OutboundEstablishState2 state;
    private PeerState2 bob;
    private Hash bobHash;
    private Hash charlieHash;
    private byte[] signedResponse;

    @BeforeClass
    public static void createKeys() throws Exception {
        SimpleDataStructure[] keys = I2PAppContext.getGlobalContext().keyGenerator()
                                                .generateSigningKeys(SigType.EdDSA_SHA512_Ed25519);
        publicKey = (SigningPublicKey) keys[0];
        privateKey = (SigningPrivateKey) keys[1];
    }

    @Before
    public void setUp() throws Exception {
        context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        when(context.clock().now()).thenReturn(NOW);
        // Every state needs distinct send/receive IDs. A finite Mockito return
        // sequence repeats its last ID and can trap a second state in its retry loop.
        AtomicLong connectionIds = new AtomicLong();
        when(context.random().nextLong()).thenAnswer(call -> connectionIds.incrementAndGet());
        when(context.dsa()).thenReturn(I2PAppContext.getGlobalContext().dsa());
        when(context.routerHash()).thenReturn(hash(1));
        transport = mock(UDPTransport.class);
        when(transport.isValid(any(byte[].class))).thenReturn(true);
        when(transport.getSSU2StaticIntroKey()).thenReturn(new byte[32]);
        KeyPair staticKeys = I2PAppContext.getGlobalContext().keyGenerator().generatePKIKeys(EncType.ECIES_X25519);
        when(transport.getSSU2StaticPrivKey()).thenReturn(staticKeys.getPrivate().getData());
        when(transport.getSSU2StaticPubKey()).thenReturn(staticKeys.getPublic().getData());
        when(transport.getXDHFactory()).thenReturn(new X25519KeyFactory(I2PAppContext.getGlobalContext()));
        manager = new EstablishmentManager(context, transport);
        bobHash = hash(2);
        charlieHash = hash(3);
        bob = mock(PeerState2.class);
        when(bob.getRemotePeer()).thenReturn(bobHash);
        when(bob.getRemoteIP()).thenReturn(new byte[] { 1, 1, 1, 1 });
        RouterIdentity identity = mock(RouterIdentity.class);
        when(identity.getHash()).thenReturn(charlieHash);
        when(identity.calculateHash()).thenReturn(charlieHash);
        when(identity.getSigningPublicKey()).thenReturn(publicKey);
        RouterInfo info = mock(RouterInfo.class);
        when(info.getIdentity()).thenReturn(identity);
        when(context.netDb().lookupRouterInfoLocally(charlieHash)).thenReturn(info);
        UDPAddress address = mock(UDPAddress.class);
        when(address.getIntroducerCount()).thenReturn(1);
        when(address.getIntroducerHash(0)).thenReturn(bobHash);
        OrderedProperties options = new OrderedProperties();
        options.setProperty("s", Base64.encode(staticKeys.getPublic().getData()));
        RouterAddress ra = new RouterAddress("SSU2", options, 10);
        RemoteHostId claimed = endpoint(34567);
        state = new OutboundEstablishState2(context, transport, claimed, new RemoteHostId(charlieHash),
                                           identity, "", false, new SessionKey(new byte[32]), ra, address, 2);
        state.setIntroNonce(NONCE);
        state.setIntroState(bobHash, INTRO_STATE_RELAY_REQUEST_SENT);
        states().put(state.getRemoteHostId(), state);
        claimedStates().put(claimed, state);
        introductions().put(NONCE, state);
        signedResponse = createRelayResponseData(context, bobHash, RELAY_ACCEPT, NONCE, IP, 45678, privateKey, TOKEN);
    }

    @Test
    public void pq512HandshakeAcceptsRelayResponse() throws Exception {
        useProtocolVersion(3);
        receiveResponse(signedResponse);
        assertEndpoint(45678);
        assertEquals(OB_STATE_INTRODUCED, state.getState());
        assertEquals(3, state.getVersion());
    }

    @Test
    public void pq768HandshakeAcceptsHolePunchBeforeRelayResponse() throws Exception {
        useProtocolVersion(4);
        manager.receiveHolePunch(endpoint(45678), holePunch(signedResponse, NONCE));
        receiveResponse(signedResponse);
        assertEndpoint(45678);
        assertEquals(4, state.getVersion());
        assertNull(introductions().get(NONCE));
    }

    @Test
    public void pqIntroducerConnectionsCanSendRelayRequests() throws Exception {
        PacketBuilder2 builder = mock(PacketBuilder2.class);
        when(transport.getBuilder2()).thenReturn(builder);
        when(context.keyManager().getSigningPrivateKey()).thenReturn(privateKey);
        when(transport.getPeerState(bobHash)).thenReturn(bob);
        OrderedProperties options = new OrderedProperties();
        options.setProperty("host", "1.1.1.1");
        options.setProperty("port", "34567");
        when(transport.getCurrentExternalAddress(false)).thenReturn(new RouterAddress("SSU2", options, 10));
        manager = new EstablishmentManager(context, transport);
        Method method = EstablishmentManager.class.getDeclaredMethod("handlePendingIntro", OutboundEstablishState.class);
        method.setAccessible(true);
        for (int version : new int[] { 3, 4 }) {
            when(bob.getVersion()).thenReturn(version);
            state.setIntroState(bobHash, INTRO_STATE_INIT);
            method.invoke(manager, state);
            assertEquals(INTRO_STATE_RELAY_REQUEST_SENT, state.getIntroState(bobHash));
        }
        verify(builder, times(2)).buildRelayRequest(any(byte[].class), eq(bob));
    }

    private void receiveResponse(byte[] response) {
        manager.receiveRelayResponse(bob, NONCE, response[1] & 0xff, Arrays.copyOfRange(response, 2, response.length));
    }

    private void useProtocolVersion(int version) throws Exception {
        RouterAddress address = (RouterAddress) field(OutboundEstablishState2.class, "_routerAddress").get(state);
        state = new OutboundEstablishState2(context, transport, state.getClaimedAddress(),
                                           new RemoteHostId(charlieHash), state.getRemoteIdentity(), "", false,
                                           new SessionKey(new byte[32]), address, state.getRemoteAddress(), version);
        state.setIntroNonce(NONCE);
        state.setIntroState(bobHash, INTRO_STATE_RELAY_REQUEST_SENT);
        states().clear();
        states().put(state.getRemoteHostId(), state);
        introductions().put(NONCE, state);
        claimedStates().put(state.getClaimedAddress(), state);
    }

    private UDPPacket holePunch(byte[] response, long nonce) throws Exception {
        SSU2Payload.Block time = new SSU2Payload.DateTimeBlock(context);
        SSU2Payload.Block relay = new SSU2Payload.RelayResponseBlock(response);
        int payloadLength = time.getTotalLength() + relay.getTotalLength();
        byte[] bytes = new byte[LONG_HEADER_SIZE + payloadLength + MAC_LEN];
        DataHelper.toLong8(bytes, 0, (nonce << 32) | nonce);
        DataHelper.toLong(bytes, PKT_NUM_OFFSET, 4, 7);
        bytes[TYPE_OFFSET] = HOLE_PUNCH_FLAG_BYTE;
        int offset = time.write(bytes, LONG_HEADER_SIZE);
        relay.write(bytes, offset);
        ChaChaPolyCipherState cipher = new ChaChaPolyCipherState();
        cipher.initializeKey(new byte[32], 0);
        cipher.setNonce(7);
        cipher.encryptWithAd(bytes, 0, LONG_HEADER_SIZE, bytes, LONG_HEADER_SIZE,
                             bytes, LONG_HEADER_SIZE, payloadLength);
        cipher.destroy();
        UDPPacket packet = mock(UDPPacket.class);
        when(packet.getPacket()).thenReturn(new DatagramPacket(bytes, bytes.length));
        return packet;
    }

    private void assertEndpoint(int port) throws Exception {
        assertEquals(endpoint(port), state.getRemoteHostId());
        assertEquals(port, state.getSentPort());
        assertEquals(port, state.getSentAddress().getPort());
        assertSame(state, states().get(endpoint(port)));
    }

    @SuppressWarnings("unchecked")
    private Map<Long, OutboundEstablishState> introductions() throws Exception {
        return (Map<Long, OutboundEstablishState>) field(EstablishmentManager.class, "_liveIntroductions").get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<RemoteHostId, OutboundEstablishState> states() throws Exception {
        return (Map<RemoteHostId, OutboundEstablishState>) field(EstablishmentManager.class, "_outboundStates").get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<RemoteHostId, OutboundEstablishState> claimedStates() throws Exception {
        return (Map<RemoteHostId, OutboundEstablishState>) field(EstablishmentManager.class, "_outboundByClaimedAddress").get(manager);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static RemoteHostId endpoint(int port) {
        return new RemoteHostId(IP, port);
    }

    private static Hash hash(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return new Hash(bytes);
    }
}
