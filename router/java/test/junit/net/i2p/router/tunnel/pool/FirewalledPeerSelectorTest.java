package net.i2p.router.tunnel.pool;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.peermanager.ProfileOrganizer.Slice;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.OrderedProperties;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FirewalledPeerSelectorTest {
    private RouterContext context;
    private RouterInfo localInfo;
    private final Hash us = hash(0);
    private final Hash disconnected = hash(1);
    private final Hash connected = hash(2);
    private final Hash client = hash(3);

    @Before
    public void setUp() {
        context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        when(context.logManager()).thenReturn(I2PAppContext.getGlobalContext().logManager());
        when(context.routerHash()).thenReturn(us);
        when(context.commSystem().getStatus()).thenReturn(Status.REJECT_UNSOLICITED);
        when(context.commSystem().haveInboundCapacity(95)).thenReturn(true);
        when(context.commSystem().haveHighOutboundCapacity()).thenReturn(true);
        when(context.commSystem().isEstablished(connected)).thenReturn(true);
        when(context.getBooleanProperty("i2np.allowLocal")).thenReturn(true);
        // These addresses permit outgoing connections but publish no endpoint
        // or introducer through which another router can reach us.
        localInfo = info(address("SSU2", "caps", "4"), address("NTCP2", "caps", "4"));
        when(context.router().getRouterInfo()).thenReturn(localInfo);
        when(context.netDb().lookupLocallyWithoutValidation(us)).thenReturn(localInfo);
        RouterInfo peerInfo = info(address("SSU2", "host", "192.0.2.2", "port", "12345"));
        when(context.netDb().lookupLocallyWithoutValidation(disconnected)).thenReturn(peerInfo);
        when(context.netDb().lookupLocallyWithoutValidation(connected)).thenReturn(peerInfo);

        TunnelPoolSettings inboundSettings = new TunnelPoolSettings(true);
        inboundSettings.setLength(0);
        inboundSettings.setLengthVariance(0);
        TunnelPool inboundPool = mock(TunnelPool.class);
        when(inboundPool.getSettings()).thenReturn(inboundSettings);
        when(context.tunnelManager().getInboundExploratoryPool()).thenReturn(inboundPool);
        when(context.tunnelManager().getInboundPool(client)).thenReturn(inboundPool);

        ProfileOrganizer profiles = context.profileOrganizer();
        Answer<Void> general = invocation -> select(invocation, false);
        Answer<Void> active = invocation -> select(invocation, true);
        doAnswer(general).when(profiles).selectNotFailingPeers(anyInt(), anySet(), anySet(), eq(false),
                                                              anyInt(), nullable(MaskedIPSet.class));
        doAnswer(general).when(profiles).selectFastPeers(anyInt(), anySet(), anySet());
        doAnswer(general).when(profiles).selectFastPeers(anyInt(), anySet(), anySet(), anyInt(), nullable(MaskedIPSet.class));
        doAnswer(general).when(profiles).selectFastPeers(anyInt(), anySet(), anySet(), any(SessionKey.class),
                                                        any(Slice.class), anyInt(), nullable(MaskedIPSet.class));
        doAnswer(active).when(profiles).selectActiveNotFailingPeers(anyInt(), anySet(), anySet());
        doAnswer(active).when(profiles).selectActiveNotFailingPeers(anyInt(), anySet(), anySet(),
                                                                  anyInt(), nullable(MaskedIPSet.class));
    }

    @Test
    public void clientInboundUsesAnEstablishedPeerWithoutIntroducers() {
        assertInboundTunnel(clientSelector(), false, 1);
        assertInboundTunnel(clientSelector(), false, 2);
    }

    @Test
    public void exploratoryInboundUsesAnEstablishedPeerWithoutIntroducers() {
        assertInboundTunnel(exploratorySelector(), true, 1);
        assertInboundTunnel(exploratorySelector(), true, 2);
    }

    @Test
    public void clientInboundUsesEstablishedPeersWhileReachabilityIsUnknown() {
        when(context.commSystem().getStatus()).thenReturn(Status.UNKNOWN);
        assertInboundTunnel(clientSelector(), false, 1);
        assertInboundTunnel(clientSelector(), false, 2);
    }

    @Test
    public void exploratoryInboundUsesEstablishedPeersWhileReachabilityIsUnknown() {
        when(context.commSystem().getStatus()).thenReturn(Status.UNKNOWN);
        assertInboundTunnel(exploratorySelector(), true, 1);
        assertInboundTunnel(exploratorySelector(), true, 2);
    }

    @Test
    public void clientInboundSymmetricNATUsesEstablishedPeersDespiteIntroducers() {
        useIPv4Introducers();
        for (Status status : new Status[] { Status.DIFFERENT, Status.IPV4_SNAT_IPV6_UNKNOWN, Status.IPV4_SNAT_IPV6_OK }) {
            when(context.commSystem().getStatus()).thenReturn(status);
            assertInboundTunnel(clientSelector(), false, 1);
            assertInboundTunnel(clientSelector(), false, 2);
        }
    }

    @Test
    public void exploratoryInboundSymmetricNATUsesEstablishedPeersDespiteIntroducers() {
        useIPv4Introducers();
        for (Status status : new Status[] { Status.DIFFERENT, Status.IPV4_SNAT_IPV6_UNKNOWN, Status.IPV4_SNAT_IPV6_OK }) {
            when(context.commSystem().getStatus()).thenReturn(status);
            assertInboundTunnel(exploratorySelector(), true, 1);
            assertInboundTunnel(exploratorySelector(), true, 2);
        }
    }

    @Test
    public void symmetricNATDoesNotFallBackToDisconnectedAdjacentPeers() {
        useIPv4Introducers();
        when(context.commSystem().getStatus()).thenReturn(Status.DIFFERENT);
        when(context.commSystem().isEstablished(connected)).thenReturn(false);
        assertNull(clientSelector().selectPeers(settings(true, false, 1)));
        assertNull(exploratorySelector().selectPeers(settings(true, true, 1)));
    }

    @Test
    public void ordinaryFirewalledIntroducersStillAllowDisconnectedAdjacentPeers() {
        useIPv4Introducers();
        for (TunnelPeerSelector selector : new TunnelPeerSelector[] { clientSelector(), exploratorySelector() }) {
            assertFalse(selector.requiresConnectedInboundPeer(localInfo));
            List<Hash> tunnel = selector.selectPeers(settings(true, selector instanceof ExploratoryPeerSelector, 1));
            assertEquals(Arrays.asList(us, disconnected), tunnel);
        }
    }

    @Test
    public void clientOutboundBootstrapUsesAnEstablishedEndpoint() {
        assertOutboundBootstrap(clientSelector(), false);
    }

    @Test
    public void exploratoryOutboundBootstrapUsesAnEstablishedEndpoint() {
        assertOutboundBootstrap(exploratorySelector(), true);
    }

    @Test
    public void clientInboundDoesNotFallBackToDisconnectedPeers() {
        when(context.commSystem().isEstablished(connected)).thenReturn(false);
        assertNull(clientSelector().selectPeers(settings(true, false, 1)));
    }

    @Test
    public void exploratoryInboundDoesNotFallBackToDisconnectedPeers() {
        when(context.commSystem().isEstablished(connected)).thenReturn(false);
        assertNull(exploratorySelector().selectPeers(settings(true, true, 1)));
    }

    private void assertInboundTunnel(TunnelPeerSelector selector, boolean exploratory, int length) {
        assertTrue("The local inbound route must use an established adjacent peer",
                   selector.requiresConnectedInboundPeer(localInfo));
        List<Hash> tunnel = selector.selectPeers(settings(true, exploratory, length));
        assertNotNull("The established candidate should allow tunnel creation", tunnel);
        assertEquals(length + 1, tunnel.size());
        assertEquals(us, tunnel.get(0));
        assertEquals(connected, tunnel.get(1));
        if (length > 1)
            assertEquals(disconnected, tunnel.get(2));
    }

    private void useIPv4Introducers() {
        localInfo = info(address("SSU2", "caps", "4", "itag0", "123456", "ih0", connected.toBase64()),
                         address("NTCP2", "caps", "4"));
        when(context.router().getRouterInfo()).thenReturn(localInfo);
        when(context.netDb().lookupLocallyWithoutValidation(us)).thenReturn(localInfo);
    }

    private void assertOutboundBootstrap(TunnelPeerSelector selector, boolean exploratory) {
        List<Hash> tunnel = selector.selectPeers(settings(false, exploratory, 2));
        assertNotNull(tunnel);
        assertEquals(Arrays.asList(connected, disconnected, us), tunnel);
        verify(context.commSystem()).exemptIncoming(connected);
    }

    private TunnelPoolSettings settings(boolean inbound, boolean exploratory, int length) {
        TunnelPoolSettings settings = new TunnelPoolSettings(exploratory ? null : client, inbound);
        settings.setLength(length);
        settings.setLengthVariance(0);
        return settings;
    }

    private ClientPeerSelector clientSelector() {
        return new ClientPeerSelector(context) {
            @Override
            protected boolean isIPv6Only() { return false; }

            @Override
            protected boolean isNTCPDisabled() { return false; }

            @Override
            protected boolean isSSUDisabled() { return false; }

            @Override
            protected Set<Hash> getExclude(boolean inbound, boolean exploratory) { return new HashSet<Hash>(); }
        };
    }

    private ExploratoryPeerSelector exploratorySelector() {
        return new ExploratoryPeerSelector(context) {
            @Override
            protected boolean isIPv6Only() { return false; }

            @Override
            protected boolean isNTCPDisabled() { return false; }

            @Override
            protected boolean isSSUDisabled() { return false; }

            @Override
            protected Set<Hash> getExclude(boolean inbound, boolean exploratory) { return new HashSet<Hash>(); }
        };
    }

    private Void select(InvocationOnMock invocation, boolean activeOnly) {
        int count = invocation.getArgument(0);
        Set<Hash> excluded = invocation.getArgument(1);
        Set<Hash> selected = invocation.getArgument(2);
        // General selection favors the disconnected peer. Active selection
        // must restrict itself to peers with an established transport session.
        for (Hash candidate : new Hash[] { disconnected, connected }) {
            if (selected.size() >= count)
                break;
            if ((!activeOnly || context.commSystem().isEstablished(candidate)) && !excluded.contains(candidate))
                selected.add(candidate);
        }
        return null;
    }

    private static RouterAddress address(String style, String... options) {
        OrderedProperties properties = new OrderedProperties();
        for (int i = 0; i < options.length; i += 2)
            properties.setProperty(options[i], options[i + 1]);
        return new RouterAddress(style, properties, 5);
    }

    private static RouterInfo info(RouterAddress... addresses) {
        RouterInfo info = new RouterInfo();
        info.setAddresses(Arrays.asList(addresses));
        Properties options = new Properties();
        options.setProperty("caps", "R");
        info.setOptions(options);
        return info;
    }

    private static Hash hash(int value) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) value;
        return Hash.create(data);
    }
}
