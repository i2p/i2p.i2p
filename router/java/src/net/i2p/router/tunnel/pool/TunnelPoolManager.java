package net.i2p.router.tunnel.pool;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.stat.RateStat;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.util.Log;

/**
 * 
 */
public class TunnelPoolManager implements TunnelManagerFacade {
    private RouterContext _context;
    private Log _log;
    /** Hash (destination) to TunnelPool */
    private Map _clientInboundPools;
    /** Hash (destination) to TunnelPool */
    private Map _clientOutboundPools;
    private TunnelPool _inboundExploratory;
    private TunnelPool _outboundExploratory;
    /** how many build requests are in process */
    private int _outstandingBuilds;
    /** max # of concurrent build requests */
    private int _maxOutstandingBuilds;
    
    private static final String PROP_MAX_OUTSTANDING_BUILDS = "router.tunnel.maxConcurrentBuilds";
    private static final int DEFAULT_MAX_OUTSTANDING_BUILDS = 20;

    private static final String PROP_THROTTLE_CONCURRENT_TUNNELS = "router.tunnel.shouldThrottle";
    private static final boolean DEFAULT_THROTTLE_CONCURRENT_TUNNELS = false;
    
    public TunnelPoolManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPoolManager.class);
        
        HandlerJobBuilder builder = new HandleTunnelCreateMessageJob.Builder(ctx);
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelCreateMessage.MESSAGE_TYPE, builder);
        //HandlerJobBuilder b = new TunnelMessageHandlerBuilder(ctx);
        //ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelGatewayMessage.MESSAGE_TYPE, b);
        //ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelDataMessage.MESSAGE_TYPE, b);

        _clientInboundPools = new HashMap(4);
        _clientOutboundPools = new HashMap(4);
        _outstandingBuilds = 0;
        _maxOutstandingBuilds = DEFAULT_MAX_OUTSTANDING_BUILDS;
        String max = ctx.getProperty(PROP_MAX_OUTSTANDING_BUILDS, String.valueOf(DEFAULT_MAX_OUTSTANDING_BUILDS));
        if (max != null) {
            try {
                _maxOutstandingBuilds = Integer.parseInt(max);
            } catch (NumberFormatException nfe) {
                _maxOutstandingBuilds = DEFAULT_MAX_OUTSTANDING_BUILDS;
            }
        }
        
        ctx.statManager().createRateStat("tunnel.testSuccessTime", 
                                         "How long do successful tunnel tests take?", "Tunnels", 
                                         new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.participatingTunnels", 
                                         "How many tunnels are we participating in?", "Tunnels", 
                                         new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
    }
    
    /** pick an inbound tunnel not bound to a particular destination */
    public TunnelInfo selectInboundTunnel() { 
        TunnelInfo info = _inboundExploratory.selectTunnel(); 
        if (info == null) {
            _inboundExploratory.buildFallback();
            // still can be null, but probably not
            info = _inboundExploratory.selectTunnel();
        }
        return info;
    }
    
    /** pick an inbound tunnel bound to the given destination */
    public TunnelInfo selectInboundTunnel(Hash destination) { 
        if (destination == null) return selectInboundTunnel();
        TunnelPool pool = null;
        synchronized (_clientInboundPools) {
            pool = (TunnelPool)_clientInboundPools.get(destination);
        }
        if (pool != null) {
            return pool.selectTunnel();
        }
        if (_log.shouldLog(Log.CRIT))
            _log.log(Log.CRIT, "wtf, want the inbound tunnel for " + destination.calculateHash().toBase64() +
                     " but there isn't a pool?");
        return null;
    }
    
    /** pick an outbound tunnel not bound to a particular destination */
    public TunnelInfo selectOutboundTunnel() { 
        TunnelInfo info = _outboundExploratory.selectTunnel();
        if (info == null) {
            _outboundExploratory.buildFallback();
            // still can be null, but probably not
            info = _outboundExploratory.selectTunnel();
        }
        return info;
    }
    
    /** pick an outbound tunnel bound to the given destination */
    public TunnelInfo selectOutboundTunnel(Hash destination)  {
        if (destination == null) return selectOutboundTunnel();
        TunnelPool pool = null;
        synchronized (_clientOutboundPools) {
            pool = (TunnelPool)_clientOutboundPools.get(destination);
        }
        if (pool != null) {
            return pool.selectTunnel();
        }
        return null;
    }
    
    public TunnelInfo getTunnelInfo(TunnelId id) {
        TunnelInfo info = null;
        synchronized (_clientInboundPools) {
            for (Iterator iter = _clientInboundPools.values().iterator(); iter.hasNext(); ) {
                TunnelPool pool = (TunnelPool)iter.next();
                info = pool.getTunnel(id);
                if (info != null)
                    return info;
            }
        }
        return null;
    }
    
    public int getFreeTunnelCount() { 
        if (_inboundExploratory == null)
            return 0;
        else
            return _inboundExploratory.size(); 
    }
    public int getOutboundTunnelCount() { 
        if (_outboundExploratory == null)
            return 0;
        else
            return _outboundExploratory.size(); 
    }
    public int getParticipatingCount() { return _context.tunnelDispatcher().getParticipatingCount(); }
    public long getLastParticipatingExpiration() { return _context.tunnelDispatcher().getLastParticipatingExpiration(); }
    
    public boolean isInUse(Hash peer) { 
        // this lets peers that are in our tunnels expire (forcing us to refetch them) 
        // if the info is old
        //!! no, dont.  bad.
        return true; 
    }
    
    public TunnelPoolSettings getInboundSettings() { return _inboundExploratory.getSettings(); }
    public TunnelPoolSettings getOutboundSettings() { return _outboundExploratory.getSettings(); }
    public void setInboundSettings(TunnelPoolSettings settings) { _inboundExploratory.setSettings(settings); }
    public void setOutboundSettings(TunnelPoolSettings settings) { _outboundExploratory.setSettings(settings); }
    public TunnelPoolSettings getInboundSettings(Hash client) { 
        TunnelPool pool = null;
        synchronized (_clientInboundPools) { 
            pool = (TunnelPool)_clientInboundPools.get(client); 
        }
        if (pool != null)
            return pool.getSettings();
        else
            return null;
    }
    public TunnelPoolSettings getOutboundSettings(Hash client) { 
        TunnelPool pool = null;
        synchronized (_clientOutboundPools) { 
            pool = (TunnelPool)_clientOutboundPools.get(client); 
        }
        if (pool != null)
            return pool.getSettings();
        else
            return null;
    }
    public void setInboundSettings(Hash client, TunnelPoolSettings settings) {
        
        TunnelPool pool = null;
        synchronized (_clientInboundPools) { 
            pool = (TunnelPool)_clientInboundPools.get(client); 
        }
        if (pool != null)
            pool.setSettings(settings);
    }
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings) {
        
        TunnelPool pool = null;
        synchronized (_clientOutboundPools) { 
            pool = (TunnelPool)_clientOutboundPools.get(client); 
        }
        if (pool != null)
            pool.setSettings(settings);
    }
    
    public void restart() { 
        shutdown();
        startup();
    }
        
    public void buildTunnels(Destination client, ClientTunnelSettings settings) {
        Hash dest = client.calculateHash();
        settings.getInboundSettings().setDestination(dest);
        settings.getOutboundSettings().setDestination(dest);
        TunnelPool inbound = null;
        TunnelPool outbound = null;
        // should we share the clientPeerSelector across both inbound and outbound?
        synchronized (_clientInboundPools) {
            inbound = (TunnelPool)_clientInboundPools.get(dest);
            if (inbound == null) {
                inbound = new TunnelPool(_context, this, settings.getInboundSettings(), 
                                         new ClientPeerSelector(), new TunnelBuilder());
                _clientInboundPools.put(dest, inbound);
            } else {
                inbound.setSettings(settings.getInboundSettings());
            }
        }
        synchronized (_clientOutboundPools) {
            outbound = (TunnelPool)_clientOutboundPools.get(dest);
            if (outbound == null) {
                outbound = new TunnelPool(_context, this, settings.getOutboundSettings(), 
                                          new ClientPeerSelector(), new TunnelBuilder());
                _clientOutboundPools.put(dest, outbound);
            } else {
                outbound.setSettings(settings.getOutboundSettings());
            }
        }
        inbound.startup();
        outbound.startup();
    }
    
    
    public void removeTunnels(Hash destination) {
        if (destination == null) return;
        if (_context.clientManager().isLocal(destination)) {
            if (_log.shouldLog(Log.CRIT))
                _log.log(Log.CRIT, "wtf, why are you removing the pool for " + destination.toBase64(), new Exception("i did it"));
        }
        TunnelPool inbound = null;
        TunnelPool outbound = null;
        synchronized (_clientInboundPools) {
            inbound = (TunnelPool)_clientInboundPools.remove(destination);
        }
        synchronized (_clientOutboundPools) {
            outbound = (TunnelPool)_clientOutboundPools.remove(destination);
        }
        if (inbound != null)
            inbound.shutdown();
        if (outbound != null)
            outbound.shutdown();
    }
    
    /** 
     * Check to make sure we can build this many new tunnels (throttled so
     * we don't build too many at a time across all pools).
     *
     * @param wanted how many tunnels the pool wants to build
     * @return how many are allowed to be built
     */
    int allocateBuilds(int wanted) {
        boolean shouldThrottle = shouldThrottleTunnels();
        if (!shouldThrottle) return wanted;
        
        synchronized (this) {
            if (_outstandingBuilds >= _maxOutstandingBuilds) {
                // ok, as a failsafe, always let one through
                // nah, its failsafe for a reason.  fix the cause.
                //_outstandingBuilds++;
                //return 1;
                return 0;
            }
            if (_outstandingBuilds + wanted < _maxOutstandingBuilds) {
                _outstandingBuilds += wanted;
                return wanted;
            } else {
                int allowed = _maxOutstandingBuilds - _outstandingBuilds;
                _outstandingBuilds = _maxOutstandingBuilds;
                return allowed;
            }
        }
    }
    
    private boolean shouldThrottleTunnels() {
        Boolean rv = Boolean.valueOf(_context.getProperty(PROP_THROTTLE_CONCURRENT_TUNNELS, ""+DEFAULT_THROTTLE_CONCURRENT_TUNNELS));
        return rv.booleanValue();
    }

    void buildComplete() {
        synchronized (this) {
            if (_outstandingBuilds > 0)
                _outstandingBuilds--;
        }
    }
    
    public void startup() { 
        TunnelBuilder builder = new TunnelBuilder();
        ExploratoryPeerSelector selector = new ExploratoryPeerSelector();
        
        TunnelPoolSettings inboundSettings = new TunnelPoolSettings();
        inboundSettings.setIsExploratory(true);
        inboundSettings.setIsInbound(true);
        _inboundExploratory = new TunnelPool(_context, this, inboundSettings, selector, builder);
        _inboundExploratory.startup();
        
        TunnelPoolSettings outboundSettings = new TunnelPoolSettings();
        outboundSettings.setIsExploratory(true);
        outboundSettings.setIsInbound(false);
        _outboundExploratory = new TunnelPool(_context, this, outboundSettings, selector, builder);
        _outboundExploratory.startup();
        
        // try to build up longer tunnels
        _context.jobQueue().addJob(new BootstrapPool(_context, _inboundExploratory));
        _context.jobQueue().addJob(new BootstrapPool(_context, _outboundExploratory));
    }
    
    private class BootstrapPool extends JobImpl {
        private TunnelPool _pool;
        public BootstrapPool(RouterContext ctx, TunnelPool pool) {
            super(ctx);
            _pool = pool;
            getTiming().setStartAfter(ctx.clock().now() + 30*1000);
        }
        public String getName() { return "Bootstrap tunnel pool"; }
        public void runJob() {
            _pool.buildFallback();
        }
    }
    
    public void shutdown() { 
        if (_inboundExploratory != null)
            _inboundExploratory.shutdown();
        if (_outboundExploratory != null)
            _outboundExploratory.shutdown();
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        out.write("<h2><a name=\"exploratory\">Exploratory tunnels</a> (<a href=\"/configtunnels.jsp#exploratory\">config</a>):</h2>\n");
        renderPool(out, _inboundExploratory, _outboundExploratory);
        
        List destinations = null;
        synchronized (_clientInboundPools) {
            destinations = new ArrayList(_clientInboundPools.keySet());
        }
        for (int i = 0; i < destinations.size(); i++) {
            Hash client = (Hash)destinations.get(i);
            TunnelPool in = null;
            TunnelPool outPool = null;
            synchronized (_clientInboundPools) {
                in = (TunnelPool)_clientInboundPools.get(client);
            }
            synchronized (_clientOutboundPools) {
                outPool = (TunnelPool)_clientOutboundPools.get(client);
            }
            String name = (in != null ? in.getSettings().getDestinationNickname() : null);
            if ( (name == null) && (outPool != null) )
                name = outPool.getSettings().getDestinationNickname();
            if (name == null)
                name = client.toBase64().substring(0,4);
            out.write("<h2><a name=\"" + client.toBase64().substring(0,4)
                      + "\">Client tunnels</a> for " + name + " (<a href=\"/configtunnels.jsp#"
                      + client.toBase64().substring(0,4) +"\">config</a>):</h2>\n");
            renderPool(out, in, outPool);
        }
        
        List participating = _context.tunnelDispatcher().listParticipatingTunnels();
        out.write("<h2><a name=\"participating\">Participating tunnels</a>:</h2><table border=\"1\">\n");
        out.write("<tr><td><b>Receive on</b></td><td><b>From</b></td><td>"
                  + "<b>Send on</b></td><td><b>To</b></td><td><b>Expiration</b></td>"
                  + "<td><b>Usage</b></td></tr>\n");
        long processed = 0;
        RateStat rs = _context.statManager().getRate("tunnel.participatingMessageCount");
        if (rs != null)
            processed = (long)rs.getRate(10*60*1000).getLifetimeTotalValue();
        for (int i = 0; i < participating.size(); i++) {
            HopConfig cfg = (HopConfig)participating.get(i);
            out.write("<tr>");
            if (cfg.getReceiveTunnel() != null)
                out.write("<td>" + cfg.getReceiveTunnel().getTunnelId() +"</td>");
            else
                out.write("<td>n/a</td>");
            if (cfg.getReceiveFrom() != null)
                out.write("<td>" + cfg.getReceiveFrom().toBase64().substring(0,4) +"</td>");
            else
                out.write("<td>n/a</td>");
            if (cfg.getSendTunnel() != null)
                out.write("<td>" + cfg.getSendTunnel().getTunnelId() +"</td>");
            else
                out.write("<td>n/a</td>");
            if (cfg.getSendTo() != null)
                out.write("<td>" + cfg.getSendTo().toBase64().substring(0,4) +"</td>");
            else
                out.write("<td>n/a</td>");
            long timeLeft = cfg.getExpiration()-_context.clock().now();
            if (timeLeft > 0)
                out.write("<td>" + DataHelper.formatDuration(timeLeft) + "</td>");
            else
                out.write("<td>(grace period)</td>");
            out.write("<td>" + cfg.getProcessedMessagesCount() + "KB</td>");
            out.write("</tr>\n");
            processed += cfg.getProcessedMessagesCount();
        }
        out.write("</table>\n");
        out.write("Lifetime bandwidth usage: " + processed + "KB<br />\n");
    }
    
    private void renderPool(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        List tunnels = null;
        if (in == null)
            tunnels = new ArrayList();
        else
            tunnels = in.listTunnels();
        if (outPool != null)
            tunnels.addAll(outPool.listTunnels());
        
        long processedIn = (in != null ? in.getLifetimeProcessed() : 0);
        long processedOut = (outPool != null ? outPool.getLifetimeProcessed() : 0);
        
        out.write("<table border=\"1\"><tr><td><b>Direction</b></td><td><b>Expiration</b></td><td><b>Usage</b></td><td align=\"left\">Hops (gateway first)</td></tr>\n");
        int live = 0;
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = (TunnelInfo)tunnels.get(i);
            long timeLeft = info.getExpiration()-_context.clock().now();
            if (timeLeft <= 0)
                continue; // don't display tunnels in their grace period
            live++;
            if (info.isInbound())
                out.write("<tr><td><b>inbound</b></td>");
            else
                out.write("<tr><td><b>outbound</b></td>");
            out.write("<td>" + DataHelper.formatDuration(timeLeft) + "</td>\n");
            out.write("<td>" + info.getProcessedMessagesCount() + "KB</td>\n");
            for (int j = 0; j < info.getLength(); j++) {
                Hash peer = info.getPeer(j);
                TunnelId id = (info.isInbound() ? info.getReceiveTunnelId(j) : info.getSendTunnelId(j));
                if (_context.routerHash().equals(peer))
                    out.write("<td><i>" + peer.toBase64().substring(0,4) + (id == null ? "" : ":" + id) + "</i></td>");
                else
                    out.write("<td>" + peer.toBase64().substring(0,4) + (id == null ? "" : ":" + id) + "</td>");
            }
            out.write("</tr>\n");
            
            if (info.isInbound()) 
                processedIn += info.getProcessedMessagesCount();
            else
                processedOut += info.getProcessedMessagesCount();
        }
        if (live <= 0)
            out.write("<tr><td colspan=\"3\">No tunnels, waiting for the grace period to end</td></tr>\n");
        out.write("</table>\n");
        out.write("Lifetime bandwidth usage: " + processedIn + "KB in, " + processedOut + "KB out<br />");
    }
}
