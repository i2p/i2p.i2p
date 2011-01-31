package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.util.Log;

/**
 * Simple job to try to keep our peer references up to date by aggressively
 * requerying them every few minutes.  This isn't useful for normal operation,
 * but instead helps with gathering operational data on the network - while old
 * RouterInfo refs are sufficient for functionality, newer ones let us harvest
 * the published peer statistics much more frequently.  By default this job 
 * is disabled (it runs but doesn't do anything), but if the router config 
 * option 'netDb.shouldHarvest' is set to 'true', then every minute it'll ask 
 * the 5 oldest peers to send their latest info (unless the info is less than
 * 30 minutes old).
 *
 */
class HarvesterJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    
    /** rerun every minute */
    private static final long REQUEUE_DELAY = 60*1000;
    /** if the routerInfo is more than 30 minutes old, refresh */
    private static final long MIN_UPDATE_FREQUENCY = 30*60*1000;
    /** don't try to update more than 5 peers during each run */
    private static final int MAX_PER_RUN = 5;
    /** background job, who cares */
    private static final int PRIORITY = 100;
    
    public static final String PROP_ENABLED = "netDb.shouldHarvest";

    private boolean harvestDirectly() { 
        return Boolean.valueOf(getContext().getProperty("netDb.harvestDirectly", "false")).booleanValue();
    }
    
    public HarvesterJob(RouterContext context, KademliaNetworkDatabaseFacade facade) {
        super(context);
        _facade = facade;
        _log = context.logManager().getLog(HarvesterJob.class);
    }
    
    public String getName() { return "Harvest the netDb"; }
    public void runJob() {
        if (shouldHarvest()) {
            List peers = selectPeersToUpdate();
            for (int i = 0; i < peers.size(); i++) {
                Hash peer= (Hash)peers.get(i);
                harvest(peer);
            }
        }
        requeue(REQUEUE_DELAY);
    }
    
    private boolean shouldHarvest() {
        String should = getContext().getProperty(PROP_ENABLED, "false");
        return ( (should != null) && ("true".equals(should)) );
    }
    
    /**
     * Retrieve a list of hashes for peers we want to update
     *
     */
    private List selectPeersToUpdate() { 
        Map routersByAge = new TreeMap();
        Set peers = _facade.getAllRouters();
        long now = getContext().clock().now();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            RouterInfo info = _facade.lookupRouterInfoLocally(peer);
            if (info != null) {
                long when = info.getPublished();
                if (when + MIN_UPDATE_FREQUENCY > now)
                    continue;
                while (routersByAge.containsKey(Long.valueOf(when)))
                    when++;
               routersByAge.put(Long.valueOf(when), info.getIdentity().getHash());
            }
        }
        
        // ok now we have the known peers sorted by date (oldest first),
        // ignoring peers that are new, so lets grab the oldest MAX_PER_RUN
        // entries
        List rv = new ArrayList(); 
        for (Iterator iter = routersByAge.values().iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            rv.add(peer);
            if (rv.size() >= MAX_PER_RUN)
                break;
        }
        return rv;
    }
    
    /**
     * Fire off a a message to query the peer directly.  We need to do this at
     * a lower level than usual (aka SearchJob) because search would find the 
     * data we already have.
     *
     */
    private void harvest(Hash peer) {
        long now = getContext().clock().now();
        if (harvestDirectly()) {
            DatabaseLookupMessage msg = new DatabaseLookupMessage(getContext(), true);
            msg.setFrom(getContext().routerHash());
            msg.setMessageExpiration(10*1000+now);
            msg.setSearchKey(peer);
            msg.setReplyTunnel(null);
            SendMessageDirectJob job = new SendMessageDirectJob(getContext(), msg, peer, 10*1000, PRIORITY);
            job.runJob();
            //getContext().jobQueue().addJob(job);
        } else {
            TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel();
            TunnelInfo sendTunnel = getContext().tunnelManager().selectOutboundTunnel();
            if ( (replyTunnel != null) && (sendTunnel != null) ) {
                DatabaseLookupMessage msg = new DatabaseLookupMessage(getContext(), true);
                msg.setFrom(replyTunnel.getPeer(0));
                msg.setMessageExpiration(10*1000+now);
                msg.setSearchKey(peer);
                msg.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
                // we don't even bother to register a reply selector, because we don't really care.
                // just send it out, and if we get a reply, neat.  if not, oh well
                getContext().tunnelDispatcher().dispatchOutbound(msg, sendTunnel.getSendTunnelId(0), peer);
            }
        }
    }
}
