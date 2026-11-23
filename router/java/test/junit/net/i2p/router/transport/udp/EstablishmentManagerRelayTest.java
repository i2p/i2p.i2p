package net.i2p.router.transport.udp;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Arrays;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.RouterContext;
import net.i2p.util.OrderedProperties;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class EstablishmentManagerRelayTest {
    private static SigningPublicKey publicKey;
    private static SigningPrivateKey privateKey;
    private RouterContext context;
    private UDPTransport transport;
    private PacketBuilder2 builder;
    private EstablishmentManager manager;
    private PeerState2 bob;
    private OutboundEstablishState charlie;
    private UDPPacket packet;

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
        when(context.clock().now()).thenReturn(1800000000000L);
        when(context.dsa()).thenReturn(I2PAppContext.getGlobalContext().dsa());
        when(context.keyManager().getSigningPrivateKey()).thenReturn(privateKey);
        transport = mock(UDPTransport.class);
        builder = mock(PacketBuilder2.class);
        when(transport.getBuilder2()).thenReturn(builder);
        when(transport.getRequestedPort()).thenReturn(12345);
        manager = new EstablishmentManager(context, transport);
        bob = mock(PeerState2.class);
        when(bob.getRemotePeer()).thenReturn(hash(1));
        charlie = mock(OutboundEstablishState.class, RETURNS_DEEP_STUBS);
        when(charlie.getRemoteIdentity().getHash()).thenReturn(hash(2));
        when(charlie.getIntroNonce()).thenReturn(1234L);
        packet = mock(UDPPacket.class);
        when(builder.buildRelayRequest(any(byte[].class), eq(bob))).thenReturn(packet);
    }

    @Test
    public void ipv4RequestSignsTheNatMappedPort() throws Exception {
        assertRelayEndpoint(false, false, 45678, 56789, 45678);
        verify(transport, never()).getExternalPort(false);
    }

    @Test
    public void ipv6RequestSignsItsOwnExternalPort() throws Exception {
        assertRelayEndpoint(true, true, 45678, 56789, 45678);
        verify(transport, never()).getExternalPort(true);
    }

    @Test
    public void ipv4RequestFallsBackToTheStoredExternalPort() throws Exception {
        assertRelayEndpoint(false, false, 0, 56789, 56789);
        verify(transport).getExternalPort(false);
    }

    @Test
    public void ipv6RequestFallsBackToItsStoredExternalPort() throws Exception {
        assertRelayEndpoint(true, true, 0, 56789, 56789);
        verify(transport).getExternalPort(true);
    }

    @Test
    public void ipv6TargetFallbackUsesTheIpv4EndpointAndPort() throws Exception {
        assertRelayEndpoint(true, false, 0, 56789, 56789);
        verify(transport).getExternalPort(false);
        verify(transport, never()).getExternalPort(true);
    }

    private void assertRelayEndpoint(boolean ipv6Target, boolean ipv6Endpoint, int addressPort,
                                     int storedPort, int expectedPort) throws Exception {
        when(charlie.getRemoteAddress().isIPv6()).thenReturn(ipv6Target);
        String host = ipv6Endpoint ? "2001:4860:4860::8888" : "8.8.8.8";
        OrderedProperties options = new OrderedProperties();
        options.setProperty("host", host);
        if (addressPort > 0)
            options.setProperty("port", Integer.toString(addressPort));
        when(transport.getCurrentExternalAddress(ipv6Endpoint)).thenReturn(new RouterAddress("SSU2", options, 10));
        when(transport.getExternalPort(ipv6Endpoint)).thenReturn(storedPort);
        // A distinct other-family port catches accidental use of the target
        // family when an IPv6 target falls back to our IPv4 address.
        when(transport.getExternalPort(!ipv6Endpoint)).thenReturn(34567);

        Method method = EstablishmentManager.class.getDeclaredMethod("sendRelayRequest", long.class,
                                                                     PeerState2.class, OutboundEstablishState.class);
        method.setAccessible(true);
        assertEquals(Boolean.TRUE, method.invoke(manager, 5678L, bob, charlie));

        ArgumentCaptor<byte[]> request = ArgumentCaptor.forClass(byte[].class);
        verify(builder).buildRelayRequest(request.capture(), eq(bob));
        byte[] data = request.getValue();
        assertEquals(expectedPort, DataHelper.fromLong(data, 15, 2));
        byte[] ip = InetAddress.getByName(host).getAddress();
        assertEquals(ip.length + 2, data[14] & 0xff);
        assertArrayEquals(ip, Arrays.copyOfRange(data, 17, 17 + ip.length));
        assertEquals(1234L, DataHelper.fromLong(data, 1, 4));
        assertEquals(5678L, DataHelper.fromLong(data, 5, 4));
        assertTrue("The transmitted endpoint must be covered by Alice's signature",
                   SSU2Util.validateSig(context, SSU2Util.RELAY_REQUEST_PROLOGUE, bob.getRemotePeer(),
                                        charlie.getRemoteIdentity().getHash(),
                                        Arrays.copyOfRange(data, 1, data.length), publicKey));
        verify(transport).send(packet);
    }

    private static Hash hash(int value) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) value;
        return new Hash(data);
    }
}
