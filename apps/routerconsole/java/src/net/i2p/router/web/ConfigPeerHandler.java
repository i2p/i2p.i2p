package net.i2p.router.web;

import net.i2p.data.Hash;
import net.i2p.data.Base64;
import net.i2p.router.peermanager.PeerProfile;

/**
 *
 */
public class ConfigPeerHandler extends FormHandler {
    private String _peer;
    private String _speed;
    private String _capacity;
    
    @Override
    protected void processForm() {
        if ("Save Configuration".equals(_action)) {
            _context.router().saveConfig();
            addFormNotice("Settings saved - not really!!!!!");
        } else if (_action.equals(_("Ban peer until restart"))) {
            Hash h = getHash();
            if (h != null) {
                _context.shitlist().shitlistRouterForever(h, _("Manually banned via {0}"), "<a href=\"configpeer\">configpeer</a>");
                addFormNotice(_("Peer") + " " + _peer + " " + _("banned until restart") );
                return;
            }
            addFormError(_("Invalid peer"));
        } else if (_action.equals(_("Unban peer"))) {
            Hash h = getHash();
            if (h != null) {
                if (_context.shitlist().isShitlisted(h)) {
                    _context.shitlist().unshitlistRouter(h);
                    addFormNotice(_("Peer") + " " + _peer + " " + _("unbanned") );
                } else
                    addFormNotice(_("Peer") + " " + _peer + " " + _("is not currently banned") );
                return;
            }
            addFormError(_("Invalid peer"));
        } else if (_action.equals(_("Adjust peer bonuses"))) {
            Hash h = getHash();
            if (h != null) {
                PeerProfile prof = _context.profileOrganizer().getProfile(h);
                if (prof != null) {
                    try {
                        prof.setSpeedBonus(Long.parseLong(_speed));
                    } catch (NumberFormatException nfe) {
                        addFormError(_("Bad speed value"));
                    }
                    try {
                        prof.setCapacityBonus(Long.parseLong(_capacity));
                    } catch (NumberFormatException nfe) {
                        addFormError(_("Bad capacity value"));
                    }
                    addFormNotice("Bonuses adjusted for " + _peer);
                } else
                    addFormError("No profile exists for " + _peer);
                return;
            }
            addFormError(_("Invalid peer"));
        } else if (_action.startsWith("Check")) {
            addFormError(_("Unsupported"));
        } else {
            addFormError("Unknown action \"" + _action + '"');
        }
    }
    
    private Hash getHash() {
        if (_peer != null && _peer.length() == 44) {
            byte[] b = Base64.decode(_peer);
            if (b != null)
                return new Hash(b);
        }
        return null;
    }

    public void setPeer(String peer) { _peer = peer; }
    public void setSpeed(String bonus) { _speed = bonus; }
    public void setCapacity(String bonus) { _capacity = bonus; }
}
