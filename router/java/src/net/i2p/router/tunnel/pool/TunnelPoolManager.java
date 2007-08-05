package net.i2p.router.tunnel.pool;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.stat.RateStat;
import net.i2p.router.*;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.I2PThread;
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
    private LoadTestManager _loadTestManager;
    private BuildExecutor _executor;
    private boolean _isShutdown;
    
    public TunnelPoolManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPoolManager.class);
        
        //HandlerJobBuilder builder = new HandleTunnelCreateMessageJob.Builder(ctx);
        //ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelCreateMessage.MESSAGE_TYPE, builder);
        //HandlerJobBuilder b = new TunnelMessageHandlerBuilder(ctx);
        //ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelGatewayMessage.MESSAGE_TYPE, b);
        //ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelDataMessage.MESSAGE_TYPE, b);

        _clientInboundPools = new HashMap(4);
        _clientOutboundPools = new HashMap(4);
        _loadTestManager = new LoadTestManager(_context);
        
        _isShutdown = false;
        _executor = new BuildExecutor(ctx, this);
        I2PThread execThread = new I2PThread(_executor, "BuildExecutor");
        execThread.setDaemon(true);
        execThread.start();
        
        ctx.statManager().createRateStat("tunnel.testSuccessTime", 
                                         "How long do successful tunnel tests take?", "Tunnels", 
                                         new long[] { 60*1000, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.participatingTunnels", 
                                         "How many tunnels are we participating in?", "Tunnels", 
                                         new long[] { 60*1000, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
    }
    
    /** pick an inbound tunnel not bound to a particular destination */
    public TunnelInfo selectInboundTunnel() { 
        TunnelPool pool = _inboundExploratory;
        if (pool == null) return null;
        TunnelInfo info = pool.selectTunnel(); 
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
        TunnelPool pool = _outboundExploratory;
        if (pool == null) return null;
        TunnelInfo info = pool.selectTunnel();
        if (info == null) {
            pool.buildFallback();
            // still can be null, but probably not
            info = pool.selectTunnel();
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
        info = _inboundExploratory.getTunnel(id);
        if (info != null) return info;
        info = _outboundExploratory.getTunnel(id);
        if (info != null) return info;
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
    public int getInboundClientTunnelCount() { 
        int count = 0;
        List destinations = null;
        synchronized (_clientInboundPools) {
            destinations = new ArrayList(_clientInboundPools.keySet());
        }
        for (int i = 0; i < destinations.size(); i++) {
            Hash client = (Hash)destinations.get(i);
            TunnelPool pool = null;
            synchronized (_clientInboundPools) {
                pool = (TunnelPool)_clientInboundPools.get(client);
            }
            count += pool.listTunnels().size();
        }
        return count;
    }
    public int getOutboundClientTunnelCount() { 
        int count = 0;
        List destinations = null;
        synchronized (_clientInboundPools) {
            destinations = new ArrayList(_clientOutboundPools.keySet());
        }
        for (int i = 0; i < destinations.size(); i++) {
            Hash client = (Hash)destinations.get(i);
            TunnelPool pool = null;
            synchronized (_clientOutboundPools) {
                pool = (TunnelPool)_clientOutboundPools.get(client);
            }
            count += pool.listTunnels().size();
        }
        return count;
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
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Building tunnels for the client " + client.calculateHash().toBase64() + ": " + settings);
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
                                         new ClientPeerSelector());
                _clientInboundPools.put(dest, inbound);
            } else {
                inbound.setSettings(settings.getInboundSettings());
            }
        }
        synchronized (_clientOutboundPools) {
            outbound = (TunnelPool)_clientOutboundPools.get(dest);
            if (outbound == null) {
                outbound = new TunnelPool(_context, this, settings.getOutboundSettings(), 
                                          new ClientPeerSelector());
                _clientOutboundPools.put(dest, outbound);
            } else {
                outbound.setSettings(settings.getOutboundSettings());
            }
        }
        inbound.startup();
        try { Thread.sleep(3*1000); } catch (InterruptedException ie) {}
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
    
    void buildComplete(PooledTunnelCreatorConfig cfg) {
        buildComplete();
        _loadTestManager.addTunnelTestCandidate(cfg);
        if (cfg.getLength() > 1) {
            TunnelPool pool = cfg.getTunnelPool();
            if (pool == null) {
                _log.error("How does this not have a pool?  " + cfg, new Exception("baf"));
                if (cfg.getDestination() != null) {
                    if (cfg.isInbound()) {
                        synchronized (_clientInboundPools) {
                            pool = (TunnelPool)_clientInboundPools.get(cfg.getDestination());
                        }
                    } else {
                        synchronized (_clientOutboundPools) {
                            pool = (TunnelPool)_clientOutboundPools.get(cfg.getDestination());
                        }
                    }
                } else {
                    if (cfg.isInbound()) {
                        pool = _inboundExploratory;
                    } else {
                        pool = _outboundExploratory;
                    }
                }
                cfg.setTunnelPool(pool);
            }
            _context.jobQueue().addJob(new TestJob(_context, cfg, pool));
        }
    }
    void buildComplete() {}
    
    private static final String PROP_LOAD_TEST = "router.loadTest";
    
    public void startup() { 
        _isShutdown = false;
        if (!_executor.isRunning()) {
            I2PThread t = new I2PThread(_executor, "BuildExecutor");
            t.setDaemon(true);
            t.start();
        }
        ExploratoryPeerSelector selector = new ExploratoryPeerSelector();
        
        TunnelPoolSettings inboundSettings = new TunnelPoolSettings();
        inboundSettings.setIsExploratory(true);
        inboundSettings.setIsInbound(true);
        _inboundExploratory = new TunnelPool(_context, this, inboundSettings, selector);
        _inboundExploratory.startup();
        
        try { Thread.sleep(3*1000); } catch (InterruptedException ie) {}
        TunnelPoolSettings outboundSettings = new TunnelPoolSettings();
        outboundSettings.setIsExploratory(true);
        outboundSettings.setIsInbound(false);
        _outboundExploratory = new TunnelPool(_context, this, outboundSettings, selector);
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
        _isShutdown = true;
    }
    
    /** list of TunnelPool instances currently in play */
    void listPools(List out) {
        synchronized (_clientInboundPools) {
            out.addAll(_clientInboundPools.values());
        }
        synchronized (_clientOutboundPools) {
            out.addAll(_clientOutboundPools.values());
        }
        if (_inboundExploratory != null)
            out.add(_inboundExploratory);
        if (_outboundExploratory != null)
            out.add(_outboundExploratory);
    }
    void tunnelFailed() { _executor.repoll(); }
    BuildExecutor getExecutor() { return _executor; }
    boolean isShutdown() { return _isShutdown; }

    public int getInboundBuildQueueSize() { return _executor.getInboundBuildQueueSize(); }
    
    
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
        int inactive = 0;
        for (int i = 0; i < participating.size(); i++) {
            HopConfig cfg = (HopConfig)participating.get(i);
            if (cfg.getProcessedMessagesCount() <= 0) {
                inactive++;
                continue;
            }
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
                out.write("<td align=right>" + DataHelper.formatDuration(timeLeft) + "</td>");
            else
                out.write("<td align=right>(grace period)</td>");
            out.write("<td align=right>" + cfg.getProcessedMessagesCount() + "KB</td>");
            out.write("</tr>\n");
            processed += cfg.getProcessedMessagesCount();
        }
        out.write("</table>\n");
        out.write("Inactive participating tunnels: " + inactive + "<br />\n");
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
            out.write("<td align=right>" + DataHelper.formatDuration(timeLeft) + "</td>\n");
            out.write("<td align=right>" + info.getProcessedMessagesCount() + "KB</td>\n");
            for (int j = 0; j < info.getLength(); j++) {
                Hash peer = info.getPeer(j);
                String cap = getCapacity(peer);
                TunnelId id = (info.isInbound() ? info.getReceiveTunnelId(j) : info.getSendTunnelId(j));
                if (_context.routerHash().equals(peer))
                    out.write("<td><i>" + peer.toBase64().substring(0,4) + (id == null ? "" : ":" + id) + "</i>" + cap + "</td>");
                else
                    out.write("<td>" + peer.toBase64().substring(0,4) + (id == null ? "" : ":" + id) + cap + "</td>");                
            }
            out.write("</tr>\n");
            
            if (info.isInbound()) 
                processedIn += info.getProcessedMessagesCount();
            else
                processedOut += info.getProcessedMessagesCount();
        }
        out.write("</table>\n");
        if (in != null) {
            List pending = in.listPending();
            for (int i = 0; i < pending.size(); i++) {
                TunnelInfo info = (TunnelInfo)pending.get(i);
                out.write("In progress: <code>" + info.toString() + "</code><br />\n");
            }
            live += pending.size();
        }
        if (outPool != null) {
            List pending = outPool.listPending();
            for (int i = 0; i < pending.size(); i++) {
                TunnelInfo info = (TunnelInfo)pending.get(i);
                out.write("In progress: <code>" + info.toString() + "</code><br />\n");
            }
            live += pending.size();
        }
        if (live <= 0)
            out.write("<b>No tunnels, waiting for the grace period to end</b><br />\n");
        out.write("Lifetime bandwidth usage: " + processedIn + "KB in, " + processedOut + "KB out<br />");
    }
    
    private String getCapacity(Hash peer) {
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
        if (info != null) {
            String caps = info.getCapabilities();
            if (caps.indexOf(Router.CAPABILITY_BW12) >= 0) {
                return "[&lt;12&nbsp;]";
            } else if (caps.indexOf(Router.CAPABILITY_BW32) >= 0) {
                return "[&lt;=32&nbsp;]";
            } else if (caps.indexOf(Router.CAPABILITY_BW64) >= 0) {
                return "[&lt;=64&nbsp;]";
            } else if (caps.indexOf(Router.CAPABILITY_BW128) >= 0) {
                return "<b>[&lt;=128]</b>";
            } else if (caps.indexOf(Router.CAPABILITY_BW256) >= 0) {
                return "<b>[&gt;128]</b>";
            } else {
                return "[old&nbsp;]";
            }
        } else {
            return "[unkn]";
        }
    }
}
