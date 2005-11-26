package net.i2p.router.networkdb.kademlia;

import java.util.*;
import net.i2p.router.*;
import net.i2p.router.networkdb.DatabaseStoreMessageHandler;
import net.i2p.data.i2np.*;
import net.i2p.data.*;
import net.i2p.util.Log;

/**
 *
 */
public class FloodfillNetworkDatabaseFacade extends KademliaNetworkDatabaseFacade {
    public static final char CAPACITY_FLOODFILL = 'f';
    private static final String PROP_FLOODFILL_PARTICIPANT = "router.floodfillParticipant";
    private static final String DEFAULT_FLOODFILL_PARTICIPANT = "false";
    
    public FloodfillNetworkDatabaseFacade(RouterContext context) {
        super(context);
    }

    protected void createHandlers() {
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseLookupMessage.MESSAGE_TYPE, new FloodfillDatabaseLookupMessageHandler(_context));
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new FloodfillDatabaseStoreMessageHandler(_context, this));
    }
    
    private static final long PUBLISH_TIMEOUT = 30*1000;
    
    /**
     * @throws IllegalArgumentException if the local router info is invalid
     */
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (localRouterInfo == null) throw new IllegalArgumentException("wtf, null localRouterInfo?");
        if (localRouterInfo.isHidden()) return; // DE-nied!
        super.publish(localRouterInfo);
        sendStore(localRouterInfo.getIdentity().calculateHash(), localRouterInfo, null, null, PUBLISH_TIMEOUT, null);
    }
    
    public void sendStore(Hash key, DataStructure ds, Job onSuccess, Job onFailure, long sendTimeout, Set toIgnore) {
        // if we are a part of the floodfill netDb, don't send out our own leaseSets as part 
        // of the flooding - instead, send them to a random floodfill peer so *they* can flood 'em out.
        // perhaps statistically adjust this so we are the source every 1/N times... or something.
        if (floodfillEnabled() && (ds instanceof RouterInfo)) {
            flood(ds);
            if (onSuccess != null) 
                _context.jobQueue().addJob(onSuccess);
        } else {
            _context.jobQueue().addJob(new FloodfillStoreJob(_context, this, key, ds, onSuccess, onFailure, sendTimeout, toIgnore));
        }
    }

    public void flood(DataStructure ds) {
        FloodfillPeerSelector sel = (FloodfillPeerSelector)getPeerSelector();
        List peers = sel.selectFloodfillParticipants(getKBuckets());
        int flooded = 0;
        for (int i = 0; i < peers.size(); i++) {
            Hash peer = (Hash)peers.get(i);
            RouterInfo target = lookupRouterInfoLocally(peer);
            if ( (target == null) || (_context.shitlist().isShitlisted(peer)) )
                continue;
            if (peer.equals(_context.routerHash()))
                continue;
            DatabaseStoreMessage msg = new DatabaseStoreMessage(_context);
            if (ds instanceof LeaseSet) {
                msg.setKey(((LeaseSet)ds).getDestination().calculateHash());
                msg.setLeaseSet((LeaseSet)ds);
            } else {
                msg.setKey(((RouterInfo)ds).getIdentity().calculateHash());
                msg.setRouterInfo((RouterInfo)ds);
            }
            msg.setReplyGateway(null);
            msg.setReplyToken(0);
            msg.setReplyTunnel(null);
            OutNetMessage m = new OutNetMessage(_context);
            m.setMessage(msg);
            m.setOnFailedReplyJob(null);
            m.setPriority(FLOOD_PRIORITY);
            m.setTarget(target);
            m.setExpiration(_context.clock().now()+FLOOD_TIMEOUT);
            _context.commSystem().processMessage(m);
            flooded++;
            if (_log.shouldLog(Log.INFO))
                _log.info("Flooding the entry for " + msg.getKey().toBase64() + " to " + peer.toBase64());
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Flooded the to " + flooded + " peers");
    }

    private static final int FLOOD_PRIORITY = 200;
    private static final int FLOOD_TIMEOUT = 30*1000;
    
    protected PeerSelector createPeerSelector() { return new FloodfillPeerSelector(_context); }
    
    public boolean floodfillEnabled() { return floodfillEnabled(_context); }
    public static boolean floodfillEnabled(RouterContext ctx) {
        String enabled = ctx.getProperty(PROP_FLOODFILL_PARTICIPANT, DEFAULT_FLOODFILL_PARTICIPANT);
        return "true".equals(enabled);
    }
    
    public static boolean isFloodfill(RouterInfo peer) {
        if (peer == null) return false;
        String caps = peer.getCapabilities();
        if ( (caps != null) && (caps.indexOf(FloodfillNetworkDatabaseFacade.CAPACITY_FLOODFILL) != -1) )
            return true;
        else
            return false;
    }
}
