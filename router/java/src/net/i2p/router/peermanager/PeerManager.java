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
import java.io.Writer;
import java.util.*;

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
    private List _peersByCapability[];
    private Map _capabilitiesByPeer;
    
    public PeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(PeerManager.class);
        _persistenceHelper = new ProfilePersistenceHelper(context);
        _organizer = context.profileOrganizer();
        _organizer.setUs(context.routerHash());
        _capabilitiesByPeer = new HashMap(128);
        _peersByCapability = new List[26];
        for (int i = 0; i < _peersByCapability.length; i++)
            _peersByCapability[i] = new ArrayList(64);
        loadProfiles();
        _context.jobQueue().addJob(new EvaluateProfilesJob(_context));
        //_context.jobQueue().addJob(new PersistProfilesJob(_context, this));
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
        if (true)
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
        Set peers = new HashSet(criteria.getMinimumRequired());
        Set exclude = new HashSet(1);
        exclude.add(_context.routerHash());
        switch (criteria.getPurpose()) {
            case PeerSelectionCriteria.PURPOSE_TEST:
                // for now, the peers we test will be the reliable ones
                //_organizer.selectWellIntegratedPeers(criteria.getMinimumRequired(), exclude, curVals);
                _organizer.selectNotFailingPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_TUNNEL:
                // pull all of the fast ones, regardless of how many we 
                // want - we'll whittle them down later (40 lines from now)
                // int num = _organizer.countFastPeers();
                // if (num <= 0) 
                //    num = criteria.getMaximumRequired();
                // _organizer.selectFastPeers(num, exclude, curVals);
                _organizer.selectFastPeers(criteria.getMaximumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_SOURCE_ROUTE:
                _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_GARLIC:
                _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            default:
                break;
        }
        if (peers.size() <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We ran out of peers when looking for reachable ones after finding " 
                          + peers.size() + " with "
                          + _organizer.countWellIntegratedPeers() + "/" 
                          + _organizer.countHighCapacityPeers() + "/" 
                          + _organizer.countFastPeers() + " integrated/high capacity/fast peers");
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Peers selected: " + peers);
        return new ArrayList(peers);
    }
    
    public void setCapabilities(Hash peer, String caps) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Setting capabilities for " + peer.toBase64() + " to " + caps);
        if (caps != null) caps = caps.toLowerCase();
        synchronized (_capabilitiesByPeer) {
            String oldCaps = null;
            if (caps != null)
                oldCaps = (String)_capabilitiesByPeer.put(peer, caps);
            else
                oldCaps = (String)_capabilitiesByPeer.remove(peer);
            
            if (oldCaps != null) {
                for (int i = 0; i < oldCaps.length(); i++) {
                    char c = oldCaps.charAt(i);
                    if ( (caps == null) || (caps.indexOf(c) < 0) ) {
                        List peers = locked_getPeers(c);
                        if (peers != null)
                            peers.remove(peer);
                    }
                }
            }
            if (caps != null) {
                for (int i = 0; i < caps.length(); i++) {
                    char c = caps.charAt(i);
                    if ( (oldCaps != null) && (oldCaps.indexOf(c) >= 0) )
                        continue;
                    List peers = locked_getPeers(c);
                    if ( (peers != null) && (!peers.contains(peer)) )
                        peers.add(peer);
                }
            }
        }
    }
    
    private List locked_getPeers(char c) {
        c = Character.toLowerCase(c);
        int i = c - 'a';
        if ( (i < 0) || (i >= _peersByCapability.length) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Invalid capability " + c + " (" + i + ")");
            return null;
        }
        return _peersByCapability[i];
    }
    
    public void removeCapabilities(Hash peer) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Removing capabilities from " + peer.toBase64());
        synchronized (_capabilitiesByPeer) {
            String oldCaps = (String)_capabilitiesByPeer.remove(peer);
            if (oldCaps != null) {
                for (int i = 0; i < oldCaps.length(); i++) {
                    char c = oldCaps.charAt(i);
                    List peers = locked_getPeers(c);
                    if (peers != null)
                        peers.remove(peer);
                }
            }
        }
    }
    public Hash selectRandomByCapability(char capability) { 
        int index = _context.random().nextInt(Integer.MAX_VALUE);
        synchronized (_capabilitiesByPeer) {
            List peers = locked_getPeers(capability);
            if ( (peers != null) && (peers.size() > 0) ) {
                index = index % peers.size();
                return (Hash)peers.get(index);
            }
        }
        return null;
    }
    public List getPeersByCapability(char capability) { 
        synchronized (_capabilitiesByPeer) {
            List peers = locked_getPeers(capability);
            if (peers != null)
                return new ArrayList(peers);
        }
        return null;
    }

    public void renderStatusHTML(Writer out) throws IOException { 
        _organizer.renderStatusHTML(out); 
    }
}
