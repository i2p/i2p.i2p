package net.i2p.router.peermanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Manage the current state of the statistics
 *
 */
class PeerManager {
    private Log _log;
    private RouterContext _context;
    private ProfileOrganizer _organizer;
    private ProfilePersistenceHelper _persistenceHelper;
    
    public PeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(PeerManager.class);
        _persistenceHelper = new ProfilePersistenceHelper(context);
        _organizer = context.profileOrganizer();
        _organizer.setUs(context.routerHash());
        loadProfiles();
        _context.jobQueue().addJob(new EvaluateProfilesJob(_context));
        _context.jobQueue().addJob(new PersistProfilesJob(_context, this));
    }
    
    void storeProfiles() {
        Set peers = selectPeers();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            storeProfile(peer);
        }
    }
    Set selectPeers() {
        return _organizer.selectAllPeers();
    }
    void storeProfile(Hash peer) {
        if (peer == null) return;
        PeerProfile prof = _organizer.getProfile(peer);
        if (prof == null) return;
        _persistenceHelper.writeProfile(prof);
    }
    void loadProfiles() {
        Set profiles = _persistenceHelper.readProfiles();
        for (Iterator iter = profiles.iterator(); iter.hasNext();) {
            PeerProfile prof = (PeerProfile)iter.next();
            if (prof != null) {
                _organizer.addProfile(prof);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Profile for " + prof.getPeer().toBase64() + " loaded");
            }
        }
    }
    
    /**
     * Find some peers that meet the criteria and we have the netDb info for locally
     *
     */
    List selectPeers(PeerSelectionCriteria criteria) {
        int numPasses = 0;
        List rv = new ArrayList(criteria.getMinimumRequired());
        Set exclude = new HashSet(1);
        exclude.add(_context.routerHash());
        while (rv.size() < criteria.getMinimumRequired()) {
            Set curVals = new HashSet(criteria.getMinimumRequired());
            switch (criteria.getPurpose()) {
                case PeerSelectionCriteria.PURPOSE_TEST:
                    // for now, the peers we test will be the reliable ones
                    //_organizer.selectWellIntegratedPeers(criteria.getMinimumRequired(), exclude, curVals);
                    _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, curVals);
                    break;
                case PeerSelectionCriteria.PURPOSE_TUNNEL:
                    // pull all of the fast ones, regardless of how many we 
                    // want - we'll whittle them down later (40 lines from now)
                    // int num = _organizer.countFastPeers();
                    // if (num <= 0) 
                    //    num = criteria.getMaximumRequired();
                    // _organizer.selectFastPeers(num, exclude, curVals);
                    _organizer.selectFastPeers(criteria.getMaximumRequired(), exclude, curVals);
                    break;
                case PeerSelectionCriteria.PURPOSE_SOURCE_ROUTE:
                    _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, curVals);
                    break;
                case PeerSelectionCriteria.PURPOSE_GARLIC:
                    _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, curVals);
                    break;
                default:
                    break;
            }
            if (curVals.size() <= 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("We ran out of peers when looking for reachable ones after finding " 
                              + rv.size() + " with "
                              + _organizer.countWellIntegratedPeers() + "/" 
                              + _organizer.countHighCapacityPeers() + "/" 
                              + _organizer.countFastPeers() + " integrated/high capacity/fast peers");
                break;
            } else {
                for (Iterator iter = curVals.iterator(); iter.hasNext(); ) {
                    Hash peer = (Hash)iter.next();
                    if (null != _context.netDb().lookupRouterInfoLocally(peer)) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Peer " + peer.toBase64() + " is locally known, so we'll allow its selection");
                        if (!rv.contains(peer))
                            rv.add(peer);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Peer " + peer.toBase64() + " is NOT locally known, disallowing its selection");
                    }
                }
                exclude.addAll(curVals);
            }
            numPasses++;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Peers selected after " + numPasses + ": " + rv);
        
        /*
        if (criteria.getPurpose() == PeerSelectionCriteria.PURPOSE_TUNNEL) {
            // we selected extra peers above.  now lets strip that down to the 
            // minimum requested, ordering it by the least recently agreed to
            // first
            TreeMap ordered = new TreeMap();
            for (Iterator iter = rv.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                PeerProfile prof = _organizer.getProfile(peer);
                long when = prof.getTunnelHistory().getLastAgreedTo();
                while (ordered.containsKey(new Long(when)))
                    when++;
                ordered.put(new Long(when), peer);
            }
            rv.clear();
            for (Iterator iter = ordered.values().iterator(); iter.hasNext(); ) {
                if (rv.size() >= criteria.getMaximumRequired()) break;
                Hash peer = (Hash)iter.next();
                rv.add(peer);
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Peers selected after " + numPasses + ", sorted for a tunnel: " + rv);
        }
         */
        return rv;
    }
    
    public void renderStatusHTML(OutputStream out) throws IOException { 
        _organizer.renderStatusHTML(out); 
    }
}
