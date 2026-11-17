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

public class IntroductionOrderingTest {
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
    public void relayResponseThenHolePunchUsesObservedNatPort() throws Exception {
        receiveResponse(signedResponse);
        assertEquals(endpoint(45678), state.getRemoteHostId());
        assertSame(state, introductions().get(NONCE));
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        assertEndpoint(56789);
        assertNull(introductions().get(NONCE));
        assertNull(states().get(endpoint(45678)));
        assertNull(claimedStates().get(endpoint(34567)));
        assertEquals(NOW, state.getNextSendTime());
    }

    @Test
    public void lateHolePunchRetargetsSavedRequestWithoutResettingHandshake() throws Exception {
        receiveResponse(signedResponse);
        state.requestSent();
        byte[] saved = new byte[] { 1, 2, 3 };
        field(OutboundEstablishState2.class, "_sessReqForReTX").set(state, saved);
        Object handshake = field(OutboundEstablishState2.class, "_handshakeState").get(state);
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        assertEndpoint(56789);
        assertEquals(OB_STATE_REQUEST_SENT, state.getState());
        assertSame(saved, field(OutboundEstablishState2.class, "_sessReqForReTX").get(state));
        assertSame(handshake, field(OutboundEstablishState2.class, "_handshakeState").get(state));
        assertEquals(TOKEN, state.getToken());
    }

    @Test
    public void holePunchBeforeRelayResponseKeepsObservedPort() throws Exception {
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        receiveResponse(signedResponse);
        assertEndpoint(56789);
        assertNull(states().get(endpoint(45678)));
        assertNull(introductions().get(NONCE));
    }

    @Test
    public void invalidRelaySignatureDoesNotConsumeIntroduction() throws Exception {
        byte[] invalid = signedResponse.clone();
        invalid[20] ^= 1;
        receiveResponse(invalid);
        assertPending();
        receiveResponse(signedResponse);
        assertEndpoint(45678);
    }

    @Test
    public void malformedRelayResponseDoesNotConsumeIntroduction() throws Exception {
        receiveResponse(Arrays.copyOf(signedResponse, 20));
        assertPending();
        receiveResponse(signedResponse);
        assertEndpoint(45678);
    }

    @Test
    public void signedResponseWithoutAnEndpointDoesNotPoisonIntroduction() throws Exception {
        byte[] malformed = createRelayResponseData(context, bobHash, RELAY_ACCEPT, NONCE,
                                                   null, 0, privateKey, TOKEN);
        receiveResponse(malformed);
        assertPending();
    }

    @Test
    public void lateRejectionCannotInvalidateAnAcceptedIntroduction() throws Exception {
        receiveResponse(signedResponse);
        byte[] rejection = createRelayResponseData(context, bobHash, RELAY_REJECT_CHARLIE_ADDRESS,
                                                   NONCE, null, 0, privateKey, 0);
        receiveResponse(rejection);
        assertEquals(OB_STATE_INTRODUCED, state.getState());
        assertEndpoint(45678);
        assertSame(state, introductions().get(NONCE));
    }

    @Test
    public void lateIntroducerRejectionDoesNotPreventValidHolePunch() throws Exception {
        RouterInfo bobInfo = mock(RouterInfo.class, RETURNS_DEEP_STUBS);
        when(bobInfo.getIdentity().getSigningPublicKey()).thenReturn(publicKey);
        when(context.netDb().lookupRouterInfoLocally(bobHash)).thenReturn(bobInfo);
        receiveResponse(signedResponse);
        byte[] rejection = createRelayResponseData(context, bobHash, RELAY_REJECT_BOB_NO_TAG,
                                                   NONCE, null, 0, privateKey, 0);
        receiveResponse(rejection);
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        assertEndpoint(56789);
        assertNull(introductions().get(NONCE));
    }

    @Test
    public void invalidHolePunchSignatureDoesNotConsumeIntroduction() throws Exception {
        byte[] invalid = signedResponse.clone();
        invalid[20] ^= 1;
        manager.receiveHolePunch(endpoint(56789), holePunch(invalid, NONCE));
        assertPending();
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        assertEndpoint(56789);
    }

    @Test
    public void mismatchedSourceIpDoesNotPoisonSignedIntroduction() throws Exception {
        manager.receiveHolePunch(new RemoteHostId(new byte[] { 9, 9, 9, 9 }, 56789), holePunch(signedResponse, NONCE));
        assertPending();
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        assertEndpoint(56789);
    }

    @Test
    public void unknownNonceCannotSelectAStateBySourceEndpoint() throws Exception {
        receiveResponse(signedResponse);
        byte[] other = createRelayResponseData(context, bobHash, RELAY_ACCEPT, NONCE + 1, IP, 45678, privateKey, TOKEN);
        manager.receiveHolePunch(endpoint(45678), holePunch(other, NONCE + 1));
        assertSame(state, introductions().get(NONCE));
        assertEndpoint(45678);
    }

    @Test
    public void duplicateHolePunchCannotMoveTheEndpointAgain() throws Exception {
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        manager.receiveHolePunch(endpoint(56790), holePunch(signedResponse, NONCE));
        assertEndpoint(56789);
        assertNull(states().get(endpoint(56790)));
    }

    @Test
    public void authenticatedHandshakeCannotBeRetargeted() throws Exception {
        receiveResponse(signedResponse);
        state._currentState = OB_STATE_CREATED_RECEIVED;
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        assertEndpoint(45678);
        assertEquals(OB_STATE_CREATED_RECEIVED, state.getState());
        assertNull(states().get(endpoint(56789)));
        assertNull(introductions().get(NONCE));
    }

    @Test
    public void endpointCollisionDoesNotReplaceAnotherEstablishment() throws Exception {
        receiveResponse(signedResponse);
        OutboundEstablishState other = mock(OutboundEstablishState.class);
        states().put(endpoint(56789), other);
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        assertEndpoint(45678);
        assertSame(other, states().get(endpoint(56789)));
        assertSame(state, introductions().get(NONCE));
    }

    @Test
    public void missingSignerInfoDoesNotConsumeIntroduction() throws Exception {
        when(context.netDb().lookupRouterInfoLocally(charlieHash)).thenReturn(null);
        receiveResponse(signedResponse);
        manager.receiveHolePunch(endpoint(56789), holePunch(signedResponse, NONCE));
        assertPending();
    }

    @Test
    public void completedHandshakeReleasesRetainedNonce() throws Exception {
        receiveResponse(signedResponse);
        state.complete();
        Method method = EstablishmentManager.class.getDeclaredMethod("handleCompletelyEstablished", OutboundEstablishState.class);
        method.setAccessible(true);
        method.invoke(manager, state);
        assertNull(introductions().get(NONCE));
    }

    @Test
    public void expiredHandshakeReleasesRetainedNonce() throws Exception {
        receiveResponse(signedResponse);
        Method method = EstablishmentManager.class.getDeclaredMethod("processExpired", OutboundEstablishState.class);
        method.setAccessible(true);
        method.invoke(manager, state);
        assertNull(introductions().get(NONCE));
        assertNull(states().get(endpoint(45678)));
    }

    private void receiveResponse(byte[] response) {
        manager.receiveRelayResponse(bob, NONCE, response[1] & 0xff, Arrays.copyOfRange(response, 2, response.length));
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

    private void assertPending() throws Exception {
        assertEquals(OB_STATE_PENDING_INTRO, state.getState());
        assertSame(state, introductions().get(NONCE));
        assertSame(state, states().get(new RemoteHostId(charlieHash)));
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
