package net.i2p.router.peermanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.JobQueue;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.Router;
import net.i2p.util.Log;

/**
 * Manage the current state of the statistics
 *
 */
class PeerManager {
    private final static Log _log = new Log(PeerManager.class);
    private ProfileOrganizer _organizer = ProfileOrganizer.getInstance();
    
    public PeerManager() { 
	_organizer.setUs(Router.getInstance().getRouterInfo().getIdentity().getHash());
	loadProfiles();
	JobQueue.getInstance().addJob(new EvaluateProfilesJob());
	JobQueue.getInstance().addJob(new PersistProfilesJob(this));
    }
    
    void storeProfiles() {
	Set peers = selectPeers();
	for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
	    Hash peer = (Hash)iter.next();
	    storeProfile(peer);
	}
    }
    Set selectPeers() {
	return _organizer.getInstance().selectAllPeers();
    }
    void storeProfile(Hash peer) {
	if (peer == null) return;
	PeerProfile prof = _organizer.getInstance().getProfile(peer);
	if (prof == null) return;
	ProfilePersistenceHelper.getInstance().writeProfile(prof);
    }
    void loadProfiles() {
	Set profiles = ProfilePersistenceHelper.getInstance().readProfiles();
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
    Set selectPeers(PeerSelectionCriteria criteria) {
	int numPasses = 0;
	Set rv = new HashSet(criteria.getMinimumRequired());
	Set exclude = new HashSet(1);
	exclude.add(Router.getInstance().getRouterInfo().getIdentity().getHash());
	while (rv.size() < criteria.getMinimumRequired()) {
	    Set curVals = new HashSet(criteria.getMinimumRequired());
	    switch (criteria.getPurpose()) {
		case PeerSelectionCriteria.PURPOSE_TEST:
		    _organizer.selectWellIntegratedPeers(criteria.getMinimumRequired(), exclude, curVals);
		    break;
		case PeerSelectionCriteria.PURPOSE_TUNNEL:
		    _organizer.selectFastAndReliablePeers(criteria.getMinimumRequired(), exclude, curVals);
		    break;
		case PeerSelectionCriteria.PURPOSE_SOURCE_ROUTE:
		    _organizer.selectReliablePeers(criteria.getMinimumRequired(), exclude, curVals);
		    break;
		case PeerSelectionCriteria.PURPOSE_GARLIC:
		    _organizer.selectReliablePeers(criteria.getMinimumRequired(), exclude, curVals);
		    break;
		default:
		    break;
	    }
	    if (curVals.size() <= 0) {
		if (_log.shouldLog(Log.WARN))
		    _log.warn("We ran out of peers when looking for reachable ones after finding " + rv.size());
		break;
	    } else {
		for (Iterator iter = curVals.iterator(); iter.hasNext(); ) {
		    Hash peer = (Hash)iter.next();
		    if (null != NetworkDatabaseFacade.getInstance().lookupRouterInfoLocally(peer)) {
			if (_log.shouldLog(Log.DEBUG))
			    _log.debug("Peer " + peer.toBase64() + " is locally known, so we'll allow its selection");
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
	return rv;
    }
    
    public String renderStatusHTML() { return _organizer.renderStatusHTML(); }
}
