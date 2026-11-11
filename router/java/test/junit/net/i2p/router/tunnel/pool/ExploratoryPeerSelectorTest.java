package net.i2p.router.tunnel.pool;

import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.OrderedProperties;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ExploratoryPeerSelectorTest {
    private RouterContext context;
    private final Hash us = hash(0);
    private final Hash incompatible = hash(1);
    private final Hash compatible = hash(2);

    @Before
    public void setUp() {
        context = mock(RouterContext.class);
        Router router = mock(Router.class);
        RouterInfo info = mock(RouterInfo.class);
        CommSystemFacade comm = mock(CommSystemFacade.class);
        ProfileOrganizer profiles = mock(ProfileOrganizer.class);
        when(context.logManager()).thenReturn(I2PAppContext.getGlobalContext().logManager());
        when(context.routerHash()).thenReturn(us);
        when(context.router()).thenReturn(router);
        when(router.getRouterInfo()).thenReturn(info);
        when(info.getAddressCount()).thenReturn(1);
        OrderedProperties addressOptions = new OrderedProperties();
        addressOptions.setProperty("host", "192.0.2.1");
        addressOptions.setProperty("port", "12345");
        when(info.getAddresses()).thenReturn(Collections.singleton(new RouterAddress("SSU2", addressOptions, 5)));
        when(context.commSystem()).thenReturn(comm);
        when(comm.getStatus()).thenReturn(CommSystemFacade.Status.OK);
        when(comm.haveInboundCapacity(95)).thenReturn(true);
        when(comm.haveHighOutboundCapacity()).thenReturn(true);
        when(context.profileOrganizer()).thenReturn(profiles);
        // Prefer the incompatible candidate unless selection applies the adjacent-hop filter.
        doAnswer(invocation -> {
            int count = invocation.getArgument(0);
            Set<Hash> excluded = invocation.getArgument(1);
            Set<Hash> selected = invocation.getArgument(2);
            for (Hash candidate : new Hash[] { incompatible, compatible }) {
                if (selected.size() >= count)
                    break;
                if (!excluded.contains(candidate))
                    selected.add(candidate);
            }
            return null;
        }).when(profiles).selectNotFailingPeers(anyInt(), anySet(), anySet(), eq(false),
                                              anyInt(), nullable(MaskedIPSet.class));
    }

    @Test
    public void testInboundWithNTCPDisabled() {
        assertCompatibleTunnel(true, true);
    }

    @Test
    public void testOutboundWithNTCPDisabled() {
        assertCompatibleTunnel(false, true);
    }

    @Test
    public void testInboundWithSSUDisabled() {
        assertCompatibleTunnel(true, false);
    }

    @Test
    public void testOutboundWithSSUDisabled() {
        assertCompatibleTunnel(false, false);
    }

    private void assertCompatibleTunnel(boolean inbound, boolean ntcpDisabled) {
        TunnelPoolSettings settings = new TunnelPoolSettings(inbound);
        settings.setLength(1);
        settings.setLengthVariance(0);
        ExploratoryPeerSelector selector = new ExploratoryPeerSelector(context) {
            @Override
            protected boolean isIPv6Only() { return false; }

            @Override
            protected boolean isNTCPDisabled() { return ntcpDisabled; }

            @Override
            protected boolean isSSUDisabled() { return !ntcpDisabled; }

            @Override
            protected Set<Hash> getExclude(boolean isInbound, boolean exploratory) {
                return new HashSet<Hash>();
            }

            @Override
            protected Set<Hash> getClosestHopExclude(boolean isInbound, Set<Hash> excluded) {
                excluded.add(incompatible);
                return excluded;
            }

            @Override
            protected boolean checkTunnel(boolean isInbound, boolean exploratory, List<Hash> tunnel) {
                return compatible.equals(tunnel.get(isInbound ? 1 : tunnel.size() - 2));
            }
        };
        List<Hash> tunnel = selector.selectPeers(settings);
        assertNotNull("An available compatible adjacent hop should produce a tunnel", tunnel);
        assertEquals(2, tunnel.size());
        assertEquals(us, tunnel.get(inbound ? 0 : 1));
        assertEquals(compatible, tunnel.get(inbound ? 1 : 0));
    }

    private static Hash hash(int value) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) value;
        return new Hash(data);
    }
}
