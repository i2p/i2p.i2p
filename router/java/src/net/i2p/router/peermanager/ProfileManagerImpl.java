package net.i2p.router.peermanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.ProfileManager;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Methods to update profiles.
 *  Unless otherwise noted, methods are blocking on the reorganize lock.
 */
public class ProfileManagerImpl implements ProfileManager {
    private final Log _log;
    private final RouterContext _context;
    
    public ProfileManagerImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(ProfileManagerImpl.class);
    }
    
    /**
     * Note that it took msToSend to send a message of size bytesSent to the peer over the transport.
     * This should only be called if the transport considered the send successful.
     * Non-blocking. Will not update the profile if we can't get the lock.
     */
    public void messageSent(Hash peer, String transport, long msToSend, long bytesSent) {
        PeerProfile data = getProfileNonblocking(peer);
        if (data == null) return;
        data.setLastSendSuccessful(_context.clock().now());
        //data.getSendSuccessSize().addData(bytesSent, msToSend);
    }
    
    /**
     * Note that the router failed to send a message to the peer over the transport specified.
     * Non-blocking. Will not update the profile if we can't get the lock.
     */
    public void messageFailed(Hash peer, String transport) {
        PeerProfile data = getProfileNonblocking(peer);
        if (data == null) return;
        data.setLastSendFailed(_context.clock().now());
    }
    
    /**
     * Note that the router failed to send a message to the peer over any transport.
     * Non-blocking. Will not update the profile if we can't get the lock.
     */
    public void messageFailed(Hash peer) {
        PeerProfile data = getProfileNonblocking(peer);
        if (data == null) return;
        data.setLastSendFailed(_context.clock().now());
    }
    
    /**
     * Note that there was some sort of communication error talking with the peer
     *
     */
    public void commErrorOccurred(Hash peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Comm error occurred for peer " + peer.toBase64(), new Exception("Comm error"));
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.setLastSendFailed(_context.clock().now());
    }
    
    /**
     * Note that the router agreed to participate in a tunnel
     *
     */
    public void tunnelJoined(Hash peer, long responseTimeMs) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.getTunnelCreateResponseTime().addData(responseTimeMs, responseTimeMs);
        data.setLastHeardFrom(_context.clock().now());
        data.getTunnelHistory().incrementAgreedTo();
    }
    
    /**
     * Note that a router explicitly rejected joining a tunnel.  
     *
     * @param responseTimeMs ignored
     * @param severity how much the peer doesnt want to participate in the 
     *                 tunnel (large == more severe)
     */
    public void tunnelRejected(Hash peer, long responseTimeMs, int severity) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.setLastHeardFrom(_context.clock().now());
        data.getTunnelHistory().incrementRejected(severity);
    }
    
    /**
     * Note that a router did not respond to a tunnel join. 
     *
     * Since TunnelHistory doesn't have a timeout stat, pretend we were
     * rejected for bandwidth reasons.
     */
    public void tunnelTimedOut(Hash peer) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.getTunnelHistory().incrementRejected(TunnelHistory.TUNNEL_REJECT_BANDWIDTH);
    }
    
    /**
     * Note that a tunnel that the router is participating in
     * was successfully tested with the given round trip latency
     *
     */
    public void tunnelTestSucceeded(Hash peer, long responseTimeMs) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.updateTunnelTestTimeAverage(responseTimeMs);
        data.getTunnelTestResponseTime().addData(responseTimeMs, responseTimeMs);
    }
    
    public void tunnelDataPushed(Hash peer, long rtt, int size) {
        if (_context.routerHash().equals(peer))
            return;
        PeerProfile data = getProfile(peer);
        //if (data != null)
            data.dataPushed(size); // ignore rtt, as we are averaging over a minute
    }

    public void tunnelDataPushed1m(Hash peer, int size) {
        if (_context.routerHash().equals(peer))
            return;
        PeerProfile data = getProfile(peer);
        //if (data != null)
            data.dataPushed1m(size);
    }

    
    public void tunnelLifetimePushed(Hash peer, long lifetime, long size) {
        if (_context.routerHash().equals(peer))
            return;
        PeerProfile data = getProfile(peer);
        //if (data != null)
            data.tunnelDataTransferred(size);
    }
    
    
    private int getSlowThreshold() {
        // perhaps we should have this compare vs. tunnel.testSuccessTime?
        return 5*1000;
    }
    
    /**
     * Note that the peer participated in a tunnel that failed.  Its failure may not have
     * been the peer's fault however.
     * Blame the peer with a probability of pct/100.
     *
     */
    public void tunnelFailed(Hash peer, int pct) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.setLastHeardFrom(_context.clock().now());
        data.getTunnelHistory().incrementFailed(pct);
    }
    
    /**
     * Note that the peer was able to return the valid data for a db lookup
     *
     * This will force creation of DB stats
     */
    public void dbLookupSuccessful(Hash peer, long responseTimeMs) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.setLastHeardFrom(_context.clock().now());
        if (!data.getIsExpandedDB())
            data.expandDBProfile();
        data.getDbResponseTime().addData(responseTimeMs, responseTimeMs);
        DBHistory hist = data.getDBHistory();
        hist.lookupSuccessful();
    }
    
    /**
     * Note that the peer was unable to reply to a db lookup - either with data or with
     * a lookupReply redirecting the user elsewhere
     *
     * This will force creation of DB stats
     */
    public void dbLookupFailed(Hash peer) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        if (!data.getIsExpandedDB())
            data.expandDBProfile();
        DBHistory hist = data.getDBHistory();
        hist.lookupFailed();
    }
    
    /**
     * Note that the peer replied to a db lookup with a redirect to other routers, where
     * the list of redirected users included newPeers routers that the local router didn't
     * know about, oldPeers routers that the local router already knew about, the given invalid
     * routers that were invalid in some way, and the duplicate number of routers that we explicitly
     * asked them not to send us, but they did anyway
     *
     */
    public void dbLookupReply(Hash peer, int newPeers, int oldPeers, int invalid, int duplicate, long responseTimeMs) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.setLastHeardFrom(_context.clock().now());
        if (!data.getIsExpandedDB())
            return;
        data.getDbResponseTime().addData(responseTimeMs, responseTimeMs);
        data.getDbIntroduction().addData(newPeers, responseTimeMs);
        DBHistory hist = data.getDBHistory();
        hist.lookupReply(newPeers, oldPeers, invalid, duplicate);
    }
    
    /**
     * Note that the local router received a db lookup from the given peer
     *
     */
    public void dbLookupReceived(Hash peer) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.setLastHeardFrom(_context.clock().now());
        if (!data.getIsExpandedDB())
            return;
        DBHistory hist = data.getDBHistory();
        hist.lookupReceived();
    }
    
    /**
     * Note that the local router received an unprompted db store from the given peer
     *
     */
    public void dbStoreReceived(Hash peer, boolean wasNewKey) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.setLastHeardFrom(_context.clock().now());
        if (!data.getIsExpandedDB())
            return;
        DBHistory hist = data.getDBHistory();
        hist.unpromptedStoreReceived(wasNewKey);
    }
    
    /**
     * Note that we've confirmed a successful send of db data to the peer (though we haven't
     * necessarily requested it again from them, so they /might/ be lying)
     *
     * This is not really interesting, since they could be lying, so we do not
     * increment any DB stats at all. On verify, call dbStoreSuccessful().
     *
     * @param responseTimeMs ignored
     */
    public void dbStoreSent(Hash peer, long responseTimeMs) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        long now = _context.clock().now();
        data.setLastHeardFrom(now);
        data.setLastSendSuccessful(now);
        //if (!data.getIsExpandedDB())
        //    data.expandDBProfile();
        //DBHistory hist = data.getDBHistory();
        //hist.storeSuccessful();
    }
    
    /**
     * Note that we've verified a successful send of db data to the floodfill peer
     * by querying another floodfill.
     *
     * This will force creation of DB stats
     */
    public void dbStoreSuccessful(Hash peer) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        long now = _context.clock().now();
        data.setLastHeardFrom(now);
        data.setLastSendSuccessful(now);
        if (!data.getIsExpandedDB())
            data.expandDBProfile();
        DBHistory hist = data.getDBHistory();
        hist.storeSuccessful();
    }
    
    /**
     * Note that we were unable to confirm a successful send of db data to
     * the peer, at least not within our timeout period
     *
     * This will force creation of DB stats
     */
    public void dbStoreFailed(Hash peer) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        if (!data.getIsExpandedDB())
            data.expandDBProfile();
        DBHistory hist = data.getDBHistory();
        hist.storeFailed();
        // we could do things like update some sort of "how many successful stores we've
        // failed to send them"...
    }
    
    /**
     * Note that the local router received a reference to the given peer, either
     * through an explicit dbStore or in a dbLookupReply
     */
    public void heardAbout(Hash peer) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        data.setLastHeardAbout(_context.clock().now());
    }

    /**
     * Note that the local router received a reference to the given peer
     * at a certain time. Only update the time if newer.
     */
    public void heardAbout(Hash peer, long when) {
        PeerProfile data = getProfile(peer);
        //if (data == null) return;
        if (when > data.getLastHeardAbout())
            data.setLastHeardAbout(when);
    }
    
    /**
     * Note that the router received a message from the given peer on the specified
     * transport.  Messages received without any "from" information aren't recorded
     * through this metric.  If msToReceive is negative, there was no timing information
     * available.
     * Non-blocking. Will not update the profile if we can't get the lock.
     */
    public void messageReceived(Hash peer, String style, long msToReceive, int bytesRead) {
        PeerProfile data = getProfileNonblocking(peer);
        if (data == null) return;
        data.setLastHeardFrom(_context.clock().now());
        //data.getReceiveSize().addData(bytesRead, msToReceive);
    }
    
    /**
     *   Blocking.
     *   Creates a new profile if it didn't exist.
     *   @return non-null
     */
    private PeerProfile getProfile(Hash peer) {
        PeerProfile prof = _context.profileOrganizer().getProfile(peer);
        if (prof == null) {
            prof = new PeerProfile(_context, peer);
            prof.setFirstHeardAbout(_context.clock().now());
            _context.profileOrganizer().addProfile(prof);
        }
        return prof;
    }
    
    /**
     *  Non-blocking.
     *  @return null if the profile doesn't exist, or the fetch would have blocked
     *  @since 0.8.12
     */
    private PeerProfile getProfileNonblocking(Hash peer) {
        return _context.profileOrganizer().getProfileNonblocking(peer);
    }
    
    /**
     *  provide a simple summary of a number of peers, suitable for publication in the netDb
     *  @deprecated unused
     */
    public Properties summarizePeers(int numPeers) {
/****
        Set peers = new HashSet(numPeers);
        // lets get the fastest ones we've got (this fails over to include just plain reliable,
        // or even notFailing peers if there aren't enough fast ones)
        _context.profileOrganizer().selectFastPeers(numPeers, null, peers);
****/
        Properties props = new Properties();
/****
        for (Iterator iter  = peers.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            PeerProfile prof = getProfile(peer);
            if (prof == null) continue;
            
            StringBuilder buf = new StringBuilder(64);
            
            buf.append("status: ");
            if (_context.profileOrganizer().isFast(peer)) {
                buf.append("fast");
            } else if (_context.profileOrganizer().isHighCapacity(peer)) {
                buf.append("highCapacity");
            } else if (_context.profileOrganizer().isFailing(peer)) {
                buf.append("failing");
            } else {
                buf.append("notFailing");
            }
            
            if (_context.profileOrganizer().isWellIntegrated(peer))
                buf.append("Integrated ");
            else
                buf.append(" ");
            
            buf.append("capacity: ").append(num(prof.getCapacityValue())).append(" ");
            buf.append("speed: ").append(num(prof.getSpeedValue())).append(" ");
            buf.append("integration: ").append(num(prof.getIntegrationValue()));
            
            props.setProperty("profile." + peer.toBase64().replace('=', '_'), buf.toString());
        }
****/
        return props;
    }
    
/****
    private final static DecimalFormat _fmt = new DecimalFormat("##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double val) {
        synchronized (_fmt) { return _fmt.format(val); }
    }
****/
}
