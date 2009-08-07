package net.i2p.router.tunnel.pool;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.stat.RateStat;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;

/**
 * 
 */
public class TunnelPoolManager implements TunnelManagerFacade {
    private RouterContext _context;
    private Log _log;
    /** Hash (destination) to TunnelPool */
    private final Map _clientInboundPools;
    /** Hash (destination) to TunnelPool */
    private final Map _clientOutboundPools;
    private TunnelPool _inboundExploratory;
    private TunnelPool _outboundExploratory;
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
    
    public boolean isValidTunnel(Hash client, TunnelInfo tunnel) {
        if (tunnel.getExpiration() < _context.clock().now())
            return false;
        TunnelPool pool;
        if (tunnel.isInbound())
            pool = (TunnelPool)_clientInboundPools.get(client); 
        else
            pool = (TunnelPool)_clientOutboundPools.get(client); 
        if (pool == null)
            return false;
        return pool.listTunnels().contains(tunnel);
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
        setSettings(_clientInboundPools, client, settings);
    }
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings) {
        setSettings(_clientOutboundPools, client, settings);
    }
    private void setSettings(Map pools, Hash client, TunnelPoolSettings settings) {
        TunnelPool pool = null;
        synchronized (pools) { 
            pool = (TunnelPool)pools.get(client); 
        }
        if (pool != null) {
            settings.setDestination(client); // prevent spoofing or unset dest
            pool.setSettings(settings);
        }
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
    
    private static class BootstrapPool extends JobImpl {
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
        out.write("<div class=\"wideload\"><h2><a name=\"exploratory\" ></a>Exploratory tunnels (<a href=\"/configtunnels.jsp#exploratory\">config</a>):</h2>\n");
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
                      + "\" ></a>Client tunnels for " + name);
            if (_context.clientManager().isLocal(client))
                out.write(" (<a href=\"/configtunnels.jsp#" + client.toBase64().substring(0,4) +"\">config</a>):</h2>\n");
            else
                out.write(" (dead):</h2>\n");
            renderPool(out, in, outPool);
        }
        
        List participating = _context.tunnelDispatcher().listParticipatingTunnels();
        Collections.sort(participating, new TunnelComparator());
        out.write("<h2><a name=\"participating\"></a>Participating tunnels:</h2><table>\n");
        out.write("<tr><th>Receive on</th><th>From</th><th>"
                  + "Send on</th><th>To</th><th>Expiration</th>"
                  + "<th>Usage</th><th>Rate</th><th>Role</th></tr>\n");
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
                out.write(" <td class=\"cells\" align=\"center\">" + cfg.getReceiveTunnel().getTunnelId() +"</td>");
            else
                out.write(" <td class=\"cells\" align=\"center\">n/a</td>");
            if (cfg.getReceiveFrom() != null)
                out.write(" <td class=\"cells\" align=\"right\">" + netDbLink(cfg.getReceiveFrom()) +"</td>");
            else
                out.write(" <td class=\"cells\" align=\"center\">&nbsp;</td>");
            if (cfg.getSendTunnel() != null)
                out.write(" <td class=\"cells\" align=\"center\">" + cfg.getSendTunnel().getTunnelId() +"</td>");
            else
                out.write(" <td class=\"cells\" align=\"center\">&nbsp;</td>");
            if (cfg.getSendTo() != null)
                out.write(" <td class=\"cells\" align=\"center\">" + netDbLink(cfg.getSendTo()) +"</td>");
            else
//                out.write(" <td class=\"cells\" align=\"center\">&nbsp;</td>");
                out.write(" <td class=\"cells\" align=\"center\">&nbsp;</td>");
            long timeLeft = cfg.getExpiration()-_context.clock().now();
            if (timeLeft > 0)
                out.write(" <td class=\"cells\" align=\"center\">" + DataHelper.formatDuration(timeLeft) + "</td>");
            else
                out.write(" <td class=\"cells\" align=\"center\">(grace period)</td>");
            out.write(" <td class=\"cells\" align=\"center\">" + cfg.getProcessedMessagesCount() + "KB</td>");
            int lifetime = (int) ((_context.clock().now() - cfg.getCreation()) / 1000);
            if (lifetime <= 0)
                lifetime = 1;
            if (lifetime > 10*60)
                lifetime = 10*60;
            int bps = 1024 * (int) cfg.getProcessedMessagesCount() / lifetime;
            out.write(" <td class=\"cells\" align=\"center\">" + bps + "Bps</td>");
            if (cfg.getSendTo() == null)
                out.write(" <td class=\"cells\" align=\"center\">Outbound Endpoint</td>");
            else if (cfg.getReceiveFrom() == null)
                out.write(" <td class=\"cells\" align=\"center\">Inbound Gateway</td>");
            else
                out.write(" <td class=\"cells\" align=\"center\">Participant</td>");
            out.write("</tr>\n");
            processed += cfg.getProcessedMessagesCount();
        }
        out.write("</table>\n");
        out.write("<div class=\"statusnotes\"><center><b>Inactive participating tunnels: " + inactive + "</b></div>\n");
        out.write("<div class=\"statusnotes\"><b>Lifetime bandwidth usage: " + DataHelper.formatSize(processed*1024) + "B</b></center></div>\n");
        renderPeers(out);
    }
    
    class TunnelComparator implements Comparator {
         public int compare(Object l, Object r) {
             return (int) (((HopConfig)r).getProcessedMessagesCount() - ((HopConfig)l).getProcessedMessagesCount());
        }
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
        
        int live = 0;
        int maxLength = 1;
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = (TunnelInfo)tunnels.get(i);
            if (info.getLength() > maxLength)
                maxLength = info.getLength();
        }
        out.write("<table><tr><th>In/Out</th><th>Expiry</th><th>Usage</th><th>Gateway</th>");
        if (maxLength > 3) {
            out.write("<th align=\"center\" colspan=\"" + (maxLength - 2));
            out.write("\">Participants</th>");
        }
        else if (maxLength == 3) {
            out.write("<th>Participant</th>");
        }
        if (maxLength > 1) {
            out.write("<th>Endpoint</th>");
        }
        out.write("</tr>\n");
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = (TunnelInfo)tunnels.get(i);
            long timeLeft = info.getExpiration()-_context.clock().now();
            if (timeLeft <= 0)
                continue; // don't display tunnels in their grace period
            live++;
            if (info.isInbound())
                out.write("<tr> <td class=\"cells\" align=\"center\"><img src=\"/themes/console/images/inbound.png\" alt=\"Inbound\" title=\"Inbound\"/></td>");
            else
                out.write("<tr> <td class=\"cells\" align=\"center\"><img src=\"/themes/console/images/outbound.png\" alt=\"Outbound\" title=\"Outbound\"/></td>");
            out.write(" <td class=\"cells\" align=\"center\">" + DataHelper.formatDuration(timeLeft) + "</td>\n");
            out.write(" <td class=\"cells\" align=\"center\">" + info.getProcessedMessagesCount() + "KB</td>\n");
            for (int j = 0; j < info.getLength(); j++) {
                Hash peer = info.getPeer(j);
                TunnelId id = (info.isInbound() ? info.getReceiveTunnelId(j) : info.getSendTunnelId(j));
                if (_context.routerHash().equals(peer)) {
                    out.write(" <td class=\"cells\" align=\"center\">" + (id == null ? "" : "" + id) + "</td>");
                } else {
                    String cap = getCapacity(peer);
                    out.write(" <td class=\"cells\" align=\"center\">" + netDbLink(peer) + (id == null ? "" : " " + id) + cap + "</td>");                
                }
                if (info.getLength() < maxLength && (info.getLength() == 1 || j == info.getLength() - 2)) {
                    for (int k = info.getLength(); k < maxLength; k++)
                        out.write(" <td class=\"cells\" align=\"center\">&nbsp</td>");
                }
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
            if (pending.size() > 0)
                out.write("<div class=\"statusnotes\"><center><b>Build in progress: " + pending.size() + " inbound</b></center></div>\n");
            live += pending.size();
        }
        if (outPool != null) {
            List pending = outPool.listPending();
            if (pending.size() > 0)
                out.write("<div class=\"statusnotes\"><center><b>Build in progress: " + pending.size() + " outbound</b></center></div>\n");
            live += pending.size();
        }
        if (live <= 0)
            out.write("<div class=\"statusnotes\"><center><b>No tunnels; waiting for the grace period to end.</center></b></div>\n");
        out.write("<div class=\"statusnotes\"><center><b>Lifetime bandwidth usage: " + DataHelper.formatSize(processedIn*1024) + "B in, " +
                  DataHelper.formatSize(processedOut*1024) + "B out</b></center></div>");
    }
    
    private void renderPeers(Writer out) throws IOException {
        // count up the peers in the local pools
        ObjectCounter<Hash> lc = new ObjectCounter();
        int tunnelCount = countTunnelsPerPeer(lc);

        // count up the peers in the participating tunnels
        ObjectCounter<Hash> pc = new ObjectCounter();
        int partCount = countParticipatingPerPeer(pc);

        Set<Hash> peers = new HashSet(lc.objects());
        peers.addAll(pc.objects());
        List<Hash> peerList = new ArrayList(peers);
        Collections.sort(peerList, new HashComparator());

        out.write("<h2><a name=\"peers\"></a>Tunnel Counts By Peer:</h2>\n");
        out.write("<table><tr><th>Peer</th><th>Expl. + Client</th><th>% of total</th><th>Part. from + to</th><th>% of total</th></tr>\n");
        for (Hash h : peerList) {
             out.write("<tr> <td class=\"cells\" align=\"center\">");
             out.write(netDbLink(h));
             out.write(" <td class=\"cells\" align=\"center\">" + lc.count(h));
             out.write(" <td class=\"cells\" align=\"center\">");
             if (tunnelCount > 0)
                 out.write("" + (lc.count(h) * 100 / tunnelCount));
             else
                 out.write('0');
             out.write(" <td class=\"cells\" align=\"center\">" + pc.count(h));
             out.write(" <td class=\"cells\" align=\"center\">");
             if (partCount > 0)
                 out.write("" + (pc.count(h) * 100 / partCount));
             else
                 out.write('0');
             out.write('\n');
        }
        out.write("<tr class=\"tablefooter\"> <td align=\"center\"><b>Tunnels</b> <td align=\"center\"><b>" + tunnelCount);
        out.write("</b> <td>&nbsp;</td> <td align=\"center\"><b>" + partCount);
        out.write("</b> <td>&nbsp;</td></tr></table></div>\n");
    }

    /** @return total number of non-fallback expl. + client tunnels */
    private int countTunnelsPerPeer(ObjectCounter<Hash> lc) {
        List<TunnelPool> pools = new ArrayList();
        listPools(pools);
        int tunnelCount = 0;
        for (TunnelPool tp : pools) {
            for (TunnelInfo info : tp.listTunnels()) {
                if (info.getLength() > 1) {
                    tunnelCount++;
                    for (int j = 0; j < info.getLength(); j++) {
                        Hash peer = info.getPeer(j);
                        if (!_context.routerHash().equals(peer))
                            lc.increment(peer);
                    }
                }
            }
        }
        return tunnelCount;
    }

    private static final int DEFAULT_MAX_PCT_TUNNELS = 33;
    /**
     *  For reliability reasons, don't allow a peer in more than x% of
     *  client and exploratory tunnels.
     *
     *  This also will prevent a single huge-capacity (or malicious) peer from
     *  taking all the tunnels in the network (although it would be nice to limit
     *  the % of total network tunnels to 10% or so, but that appears to be
     *  too low to set as a default here... much lower than 33% will push client
     *  tunnels out of the fast tier into high cap or beyond...)
     *
     *  Possible improvement - restrict based on count per IP, or IP block,
     *  to slightly increase costs of collusion
     *
     *  @return Set of peers that should not be allowed in another tunnel
     */
    public Set<Hash> selectPeersInTooManyTunnels() {
        ObjectCounter<Hash> lc = new ObjectCounter();
        int tunnelCount = countTunnelsPerPeer(lc);
        Set<Hash> rv = new HashSet();
        if (tunnelCount >= 4 && _context.router().getUptime() > 10*60*1000) {
            int max = _context.getProperty("router.maxTunnelPercentage", DEFAULT_MAX_PCT_TUNNELS);
            for (Hash h : lc.objects()) {
                 if (lc.count(h) > 0 && (lc.count(h) + 1) * 100 / (tunnelCount + 1) > max)
                     rv.add(h);
            }
        }
        return rv;
    }

    /** @return total number of part. tunnels */
    private int countParticipatingPerPeer(ObjectCounter<Hash> pc) {
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        for (HopConfig cfg : participating) {
            Hash from = cfg.getReceiveFrom();
            if (from != null)
                pc.increment(from);
            Hash to = cfg.getSendTo();
            if (to != null)
                pc.increment(to);
        }
        return participating.size();
    }

    class HashComparator implements Comparator {
         public int compare(Object l, Object r) {
             return ((Hash)l).toBase64().compareTo(((Hash)r).toBase64());
        }
    }

    private String getCapacity(Hash peer) {
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
        if (info != null) {
            String caps = info.getCapabilities();
            for (char c = Router.CAPABILITY_BW12; c <= Router.CAPABILITY_BW256; c++) {
                if (caps.indexOf(c) >= 0)
                    return " " + c;
            }
        }
        return "";
    }

    private String netDbLink(Hash peer) {
        return _context.commSystem().renderPeerHTML(peer);
    }
}
