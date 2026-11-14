package net.i2p.router.transport.udp;

import java.util.Arrays;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class IntroductionVersionCompatibilityTest {
    private static final long NOW = 1800000000000L;
    private static SigningPublicKey publicKey;
    private static SigningPrivateKey privateKey;
    private RouterContext context;
    private UDPTransport transport;
    private PacketBuilder2 builder;
    private IntroductionManager manager;

    @BeforeClass
    public static void createKeys() throws Exception {
        SimpleDataStructure[] keys = I2PAppContext.getGlobalContext().keyGenerator()
                                                .generateSigningKeys(SigType.EdDSA_SHA512_Ed25519);
        publicKey = (SigningPublicKey) keys[0];
        privateKey = (SigningPrivateKey) keys[1];
    }

    @Before
    public void setUp() {
        context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        transport = mock(UDPTransport.class, RETURNS_DEEP_STUBS);
        builder = mock(PacketBuilder2.class);
        when(context.clock().now()).thenReturn(NOW);
        when(context.routerHash()).thenReturn(hash(1));
        when(transport.getBuilder2()).thenReturn(builder);
        manager = new IntroductionManager(context, transport);
    }

    @Test
    public void classicalSsu2SessionCanReceiveRelayedIntroductions() throws Exception {
        assertRelayRequestForwarded(2);
    }

    @Test
    public void postQuantum512SessionCanReceiveRelayedIntroductions() throws Exception {
        assertRelayRequestForwarded(3);
    }

    @Test
    public void postQuantum768SessionCanReceiveRelayedIntroductions() throws Exception {
        assertRelayRequestForwarded(4);
    }

    @Test
    public void legacySessionIsStillRejectedForSsu2Relay() throws Exception {
        when(context.dsa()).thenReturn(I2PAppContext.getGlobalContext().dsa());
        when(context.keyManager().getSigningPrivateKey()).thenReturn(privateKey);
        PeerState charlie = mock(PeerState.class);
        when(charlie.getRemotePort()).thenReturn(12345);
        when(charlie.getWeRelayToThemAs()).thenReturn(5678L);
        when(charlie.getVersion()).thenReturn(1);
        manager.add(charlie);
        PeerState2 alice = mock(PeerState2.class);
        byte[] request = SSU2Util.createRelayRequestData(context, context.routerHash(), hash(20),
                                                       1234, 5678, new byte[] { 8, 8, 8, 8 }, 23456, privateKey);
        manager.receiveRelayRequest(alice, Arrays.copyOfRange(request, 1, request.length));
        ArgumentCaptor<byte[]> response = ArgumentCaptor.forClass(byte[].class);
        verify(builder).buildRelayResponse(response.capture(), eq(alice));
        assertEquals(SSU2Util.RELAY_REJECT_BOB_NO_TAG, response.getValue()[1] & 0xff);
        verify(builder, never()).buildRelayIntro(any(byte[].class), any(), any(PeerState2.class));
    }

    private void assertRelayRequestForwarded(int negotiatedVersion) throws Exception {
        when(context.dsa()).thenReturn(I2PAppContext.getGlobalContext().dsa());
        PeerState2 charlie = mock(PeerState2.class);
        when(charlie.getRemotePeer()).thenReturn(hash(20));
        when(charlie.getRemotePort()).thenReturn(12345);
        when(charlie.getWeRelayToThemAs()).thenReturn(5678L);
        when(charlie.getVersion()).thenReturn(negotiatedVersion);
        when(charlie.getMTU()).thenReturn(2000);
        manager.add(charlie);
        PeerState2 alice = mock(PeerState2.class);
        when(alice.getRemotePeer()).thenReturn(hash(30));
        RouterInfo aliceInfo = mock(RouterInfo.class, RETURNS_DEEP_STUBS);
        when(aliceInfo.getIdentity().getSigningPublicKey()).thenReturn(publicKey);
        when(aliceInfo.toByteArray()).thenReturn(new byte[128]);
        when(context.netDb().lookupRouterInfoLocally(alice.getRemotePeer())).thenReturn(aliceInfo);
        UDPPacket forwarded = mock(UDPPacket.class);
        when(builder.buildRelayIntro(any(byte[].class), any(SSU2Payload.RIBlock.class), eq(charlie))).thenReturn(forwarded);
        byte[] request = SSU2Util.createRelayRequestData(context, context.routerHash(), charlie.getRemotePeer(),
                                                       1234, 5678, new byte[] { 8, 8, 8, 8 }, 23456, privateKey);
        manager.receiveRelayRequest(alice, Arrays.copyOfRange(request, 1, request.length));
        ArgumentCaptor<byte[]> introduction = ArgumentCaptor.forClass(byte[].class);
        verify(builder).buildRelayIntro(introduction.capture(), any(SSU2Payload.RIBlock.class), eq(charlie));
        assertArrayEquals(alice.getRemotePeer().getData(), Arrays.copyOfRange(introduction.getValue(), 1, 33));
        assertArrayEquals(Arrays.copyOfRange(request, 1, request.length),
                          Arrays.copyOfRange(introduction.getValue(), 33, introduction.getValue().length));
        verify(transport).send(forwarded);
        verify(builder, never()).buildRelayResponse(any(byte[].class), eq(alice));
    }

    private static Hash hash(int value) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) value;
        return new Hash(data);
    }
}
