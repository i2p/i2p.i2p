package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import com.thetransactioncompany.jsonrpc2.server.RequestHandler;

import net.i2p.I2PAppContext;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.ntcp.NTCPTransport;

import java.util.HashMap;
import java.util.Map;

/*
 *  Copyright 2011 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

public class RouterInfoHandler implements RequestHandler {
    private final JSONRPC2Helper _helper;
    private final RouterContext _context;

    public RouterInfoHandler(RouterContext ctx, JSONRPC2Helper helper) {
        _helper = helper;
        _context = ctx;
    }


    // Reports the method names of the handled requests
    public String[] handledRequests() {
        return new String[] { "RouterInfo" };
    }

    // Processes the requests
    public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
        if (req.getMethod().equals("RouterInfo")) {
            return process(req);
        } else {
            // Method name not supported
            return new JSONRPC2Response(JSONRPC2Error.METHOD_NOT_FOUND,
                                        req.getID());
        }
    }

    @SuppressWarnings("unchecked")
    private JSONRPC2Response process(JSONRPC2Request req) {
        JSONRPC2Error err = _helper.validateParams(null, req);
        if (err != null)
            return new JSONRPC2Response(err, req.getID());

        if (_context == null) {
            return new JSONRPC2Response(new JSONRPC2Error(
                                            JSONRPC2Error.INTERNAL_ERROR.getCode(),
                                            "RouterContext was not initialized. Query failed"),
                                        req.getID());
        }
        Map<String, Object> inParams = req.getNamedParams();
        Map outParams = new HashMap();

        if (inParams.containsKey("i2p.router.version")) {
            try {
                Class rvClass = Class.forName("net.i2p.router.RouterVersion");
                java.lang.reflect.Field field = rvClass.getDeclaredField("FULL_VERSION");
                String fullVersion = (String) field.get(new RouterVersion());
                outParams.put("i2p.router.version", fullVersion);
            } catch (Exception e) {} // Ignore
        }

        if (inParams.containsKey("i2p.router.uptime")) {
            Router router = _context.router();
            if (router == null) {
                outParams.put("i2p.router.uptime", 0);
            } else {
                outParams.put("i2p.router.uptime", router.getUptime());
            }
        }

        if (inParams.containsKey("i2p.router.status")) {
            outParams.put("i2p.router.status", _context.throttle().getLocalizedTunnelStatus());
        }

        if (inParams.containsKey("i2p.router.net.status")) {
            outParams.put("i2p.router.net.status", getNetworkStatus().ordinal());
        }

        if (inParams.containsKey("i2p.router.net.bw.inbound.1s")) {
            outParams.put("i2p.router.net.bw.inbound.1s", _context.bandwidthLimiter().getReceiveBps());
        }

        if (inParams.containsKey("i2p.router.net.bw.outbound.1s")) {
            outParams.put("i2p.router.net.bw.outbound.1s", _context.bandwidthLimiter().getSendBps());
        }

        if (inParams.containsKey("i2p.router.net.bw.inbound.15s")) {
            outParams.put("i2p.router.net.bw.inbound.15s", _context.bandwidthLimiter().getReceiveBps15s());
        }

        if (inParams.containsKey("i2p.router.net.bw.outbound.15s")) {
            outParams.put("i2p.router.net.bw.outbound.15s", _context.bandwidthLimiter().getSendBps15s());
        }

        if (inParams.containsKey("i2p.router.net.tunnels.participating")) {
            outParams.put("i2p.router.net.tunnels.participating", _context.tunnelManager().getParticipatingCount());
        }

        if (inParams.containsKey("i2p.router.netdb.knownpeers")) {
            // Why max(-1, 0) is used I don't know, it is the implementation used in the router console.
            outParams.put("i2p.router.netdb.knownpeers", Math.max(_context.mainNetDb().getKnownRouters() - 1, 0));
        }

        if (inParams.containsKey("i2p.router.netdb.activepeers")) {
            outParams.put("i2p.router.netdb.activepeers", _context.commSystem().countActivePeers());
        }

        if (inParams.containsKey("i2p.router.netdb.fastpeers")) {
            outParams.put("i2p.router.netdb.fastpeers", _context.profileOrganizer().countFastPeers());
        }

        if (inParams.containsKey("i2p.router.netdb.highcapacitypeers")) {
            outParams.put("i2p.router.netdb.highcapacitypeers", _context.profileOrganizer().countHighCapacityPeers());
        }

        if (inParams.containsKey("i2p.router.netdb.isreseeding")) {
            outParams.put("i2p.router.netdb.isreseeding", Boolean.valueOf(System.getProperty("net.i2p.router.web.ReseedHandler.reseedInProgress")).booleanValue());
        }
        return new JSONRPC2Response(outParams, req.getID());
    }

    private static enum NETWORK_STATUS {
        OK,
        TESTING,
        FIREWALLED,
        HIDDEN,
        WARN_FIREWALLED_AND_FAST,
        WARN_FIREWALLED_AND_FLOODFILL,
        WARN_FIREWALLED_WITH_INBOUND_TCP,
        WARN_FIREWALLED_WITH_UDP_DISABLED,
        ERROR_I2CP,
        ERROR_CLOCK_SKEW,
        ERROR_PRIVATE_TCP_ADDRESS,
        ERROR_SYMMETRIC_NAT,
        ERROR_UDP_PORT_IN_USE,
        ERROR_NO_ACTIVE_PEERS_CHECK_CONNECTION_AND_FIREWALL,
        ERROR_UDP_DISABLED_AND_TCP_UNSET,
    };

    // Ripped out of SummaryHelper.java
    private NETWORK_STATUS getNetworkStatus() {
        if (_context.router().getUptime() > 60 * 1000
                && (!_context.router().gracefulShutdownInProgress())
                && !_context.clientManager().isAlive())
            return (NETWORK_STATUS.ERROR_I2CP);
        long skew = _context.commSystem().getFramedAveragePeerClockSkew(10);
        // Display the actual skew, not the offset
        if (Math.abs(skew) > 60 * 1000)
            return NETWORK_STATUS.ERROR_CLOCK_SKEW;
        if (_context.router().isHidden())
            return (NETWORK_STATUS.HIDDEN);

        int status = _context.commSystem().getStatus().getCode();
        switch (status) {

          case CommSystemFacade.STATUS_OK:
          case CommSystemFacade.STATUS_IPV4_OK_IPV6_UNKNOWN:
          case CommSystemFacade.STATUS_IPV4_OK_IPV6_FIREWALLED:
          case CommSystemFacade.STATUS_IPV4_FIREWALLED_IPV6_OK:
          case CommSystemFacade.STATUS_IPV4_DISABLED_IPV6_OK:
            RouterAddress ra = _context.router().getRouterInfo().getTargetAddress("NTCP2");
            if (ra == null || TransportUtil.isPubliclyRoutable(ra.getIP(), true))
                return NETWORK_STATUS.OK;
            return NETWORK_STATUS.ERROR_PRIVATE_TCP_ADDRESS;

          case CommSystemFacade.STATUS_DIFFERENT:
          case CommSystemFacade.STATUS_IPV4_SNAT_IPV6_OK:
          case CommSystemFacade.STATUS_IPV4_SNAT_IPV6_UNKNOWN:
            return NETWORK_STATUS.ERROR_SYMMETRIC_NAT;

          case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
          case CommSystemFacade.STATUS_IPV4_FIREWALLED_IPV6_UNKNOWN:
          case CommSystemFacade.STATUS_IPV4_DISABLED_IPV6_FIREWALLED:
            if (_context.router().getRouterInfo().getTargetAddress("NTCP2") != null)
                return NETWORK_STATUS.WARN_FIREWALLED_WITH_INBOUND_TCP;
            if (_context.mainNetDb().floodfillEnabled())
                return NETWORK_STATUS.WARN_FIREWALLED_AND_FLOODFILL;
            if (_context.router().getRouterInfo().getCapabilities().indexOf('O') >= 0)
                return NETWORK_STATUS.WARN_FIREWALLED_AND_FAST;
            return NETWORK_STATUS.FIREWALLED;

          case CommSystemFacade.STATUS_HOSED:
            return NETWORK_STATUS.ERROR_UDP_PORT_IN_USE;

          case CommSystemFacade.STATUS_DISCONNECTED:
            return NETWORK_STATUS.ERROR_NO_ACTIVE_PEERS_CHECK_CONNECTION_AND_FIREWALL;

          case CommSystemFacade.STATUS_UNKNOWN: // fallthrough
          case CommSystemFacade.STATUS_IPV4_UNKNOWN_IPV6_OK:
          case CommSystemFacade.STATUS_IPV4_UNKNOWN_IPV6_FIREWALLED:
          case CommSystemFacade.STATUS_IPV4_DISABLED_IPV6_UNKNOWN:
          default:
            ra = _context.router().getRouterInfo().getTargetAddress("SSU");
            if (ra == null && _context.router().getUptime() > 5 * 60 * 1000) {
                if (_context.commSystem().countActivePeers() <= 0)
                    return NETWORK_STATUS.ERROR_NO_ACTIVE_PEERS_CHECK_CONNECTION_AND_FIREWALL;
                else if (_context.getProperty(NTCPTransport.PROP_I2NP_NTCP_HOSTNAME) == null || _context.getProperty(NTCPTransport.PROP_I2NP_NTCP_PORT) == null)
                    return NETWORK_STATUS.ERROR_UDP_DISABLED_AND_TCP_UNSET;
                else
                    return NETWORK_STATUS.WARN_FIREWALLED_WITH_UDP_DISABLED;
            }
            return NETWORK_STATUS.TESTING;
        }
    }
}
