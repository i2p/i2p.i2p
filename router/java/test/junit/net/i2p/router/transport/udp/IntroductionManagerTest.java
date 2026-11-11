package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.OrderedProperties;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class IntroductionManagerTest {
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
    public void reusedIntroducerRenewsKeepaliveWindow() {
        PeerState peer = addIntroducer(10, hash(10), false);
        OrderedProperties published = new OrderedProperties();
        publish(published, 0, peer.getRemotePeer(), 10);
        Properties selected = new Properties();

        assertEquals(1, manager.pickInbound(new RouterAddress("SSU2", published, 10), false, selected, 3));

        verify(peer).setIntroducerTime();
        assertEquals(published.getProperty("ih0"), selected.getProperty("ih0"));
        assertEquals(published.getProperty("iexp0"), selected.getProperty("iexp0"));
    }

    @Test
    public void reusedIntroducersRespectRequestedCount() {
        PeerState first = addIntroducer(10, hash(10), false);
        PeerState second = addIntroducer(20, hash(20), false);
        OrderedProperties published = new OrderedProperties();
        publish(published, 0, first.getRemotePeer(), 10);
        publish(published, 1, second.getRemotePeer(), 20);
        Properties selected = new Properties();

        assertEquals(1, manager.pickInbound(new RouterAddress("SSU2", published, 10), false, selected, 1));
        assertNull(selected.getProperty("ih1"));
        verify(second, never()).setIntroducerTime();
    }

    @Test
    public void tagCollisionDoesNotRepublishDisconnectedIntroducer() {
        Hash disconnected = hash(10);
        PeerState replacement = addIntroducer(10, hash(20), false);
        OrderedProperties published = new OrderedProperties();
        publish(published, 0, disconnected, 10);

        assertEquals(0, manager.pickInbound(new RouterAddress("SSU2", published, 10), false, new Properties(), 3));
        verify(replacement, never()).setIntroducerTime();
    }

    @Test
    public void reusedIntroducerMustUseRequestedAddressFamily() {
        PeerState peer = addIntroducer(10, hash(10), true);
        OrderedProperties published = new OrderedProperties();
        publish(published, 0, peer.getRemotePeer(), 10);

        assertEquals(0, manager.pickInbound(new RouterAddress("SSU2", published, 10), false, new Properties(), 3));
        verify(peer, never()).setIntroducerTime();
    }

    @Test
    public void relayResponseUsesNatMappedExternalPort() throws Exception {
        byte[] reply = receiveRelayIntro(false, false);
        assertEquals(SSU2Util.RELAY_ACCEPT, reply[1] & 0xff);
        assertEquals(45678, DataHelper.fromLong(reply, 12, 2));
    }

    @Test
    public void ipv4SymmetricNatDoesNotRejectIpv6HolePunch() throws Exception {
        byte[] reply = receiveRelayIntro(true, true);
        assertEquals(SSU2Util.RELAY_ACCEPT, reply[1] & 0xff);
        assertEquals(45678, DataHelper.fromLong(reply, 12, 2));
        verify(builder).buildHolePunch(any(InetAddress.class), eq(23456), any(), anyLong(), anyLong(), eq(2), any(byte[].class));
    }

    @Test
    public void ipv4SymmetricNatStillRejectsIpv4HolePunch() throws Exception {
        byte[] reply = receiveRelayIntro(false, true);
        assertEquals(SSU2Util.RELAY_REJECT_CHARLIE_ADDRESS, reply[1] & 0xff);
        verify(builder, never()).buildHolePunch(any(InetAddress.class), anyInt(), any(), anyLong(), anyLong(), anyInt(), any(byte[].class));
    }

    private PeerState addIntroducer(long tag, Hash remote, boolean ipv6) {
        PeerState peer = mock(PeerState.class);
        when(peer.getRemotePort()).thenReturn(12345);
        when(peer.getTheyRelayToUsAs()).thenReturn(tag);
        when(peer.getRemotePeer()).thenReturn(remote);
        when(peer.isIPv6()).thenReturn(ipv6);
        manager.add(peer);
        return peer;
    }

    private static void publish(OrderedProperties options, int index, Hash remote, long tag) {
        options.setProperty("ih" + index, remote.toBase64());
        options.setProperty("itag" + index, Long.toString(tag));
        options.setProperty("iexp" + index, Long.toString(NOW / 1000 + 3600));
    }

    private byte[] receiveRelayIntro(boolean ipv6, boolean symmetricNat) throws Exception {
        when(context.dsa()).thenReturn(I2PAppContext.getGlobalContext().dsa());
        when(context.keyManager().getSigningPrivateKey()).thenReturn(privateKey);
        when(transport.haveCapacity(95)).thenReturn(true);
        when(transport.isSymNatted()).thenReturn(symmetricNat);
        when(transport.isValid(any(byte[].class))).thenReturn(true);
        when(transport.getRequestedPort()).thenReturn(12345);
        // A firewalled published address can omit the port, so this helper
        // falls back to the internal port. Use the stored external endpoint.
        when(transport.getExternalPort(ipv6)).thenReturn(12345);
        when(transport.getEstablisher().getInboundToken(any(RemoteHostId.class), anyLong(), eq(true)).getToken())
            .thenReturn(123456L);

        OrderedProperties options = new OrderedProperties();
        options.setProperty("host", ipv6 ? "2001:4860:4860::8844" : "8.8.4.4");
        options.setProperty("port", "45678");
        options.setProperty("i", Base64.encode(new byte[32]));
        RouterAddress address = new RouterAddress("SSU2", options, 10);
        when(transport.getCurrentExternalAddress(ipv6)).thenReturn(address);
        Hash alice = hash(30);
        when(transport.getPeerState(alice)).thenReturn(null);
        RouterInfo aliceInfo = mock(RouterInfo.class, RETURNS_DEEP_STUBS);
        when(aliceInfo.getIdentity().getSigningPublicKey()).thenReturn(publicKey);
        when(context.netDb().lookupRouterInfoLocally(alice)).thenReturn(aliceInfo);
        when(transport.getTargetAddresses(aliceInfo)).thenReturn(Collections.singletonList(address));
        PeerState2 bob = mock(PeerState2.class);
        when(bob.getRemotePeer()).thenReturn(hash(40));
        byte[] aliceIP = InetAddress.getByName(ipv6 ? "2001:4860:4860::8888" : "8.8.8.8").getAddress();
        byte[] request = SSU2Util.createRelayRequestData(context, bob.getRemotePeer(), context.routerHash(),
                                                       1234, 5678, aliceIP, 23456, privateKey);

        assertEquals(IntroductionManager.RelayIntroResult.REPLIED,
                     manager.receiveRelayIntro(bob, alice, Arrays.copyOfRange(request, 1, request.length)));
        ArgumentCaptor<byte[]> response = ArgumentCaptor.forClass(byte[].class);
        verify(builder).buildRelayResponse(response.capture(), eq(bob));
        return response.getValue();
    }

    private static Hash hash(int value) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) value;
        return new Hash(data);
    }
}
