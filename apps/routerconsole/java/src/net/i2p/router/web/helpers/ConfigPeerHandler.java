package net.i2p.router.web.helpers;

import net.i2p.data.Hash;
import net.i2p.data.Base64;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.web.FormHandler;

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
        } else if (_action.equals(_t("Ban peer until restart"))) {
            Hash h = getHash();
            if (h != null) {
                _context.banlist().banlistRouterForever(h, _t("Manually banned via {0}"), "<a href=\"configpeer\">configpeer</a>");
                addFormNotice(_t("Peer") + " " + _peer + " " + _t("banned until restart") );
                return;
            }
            addFormError(_t("Invalid peer"));
        } else if (_action.equals(_t("Unban peer"))) {
            Hash h = getHash();
            if (h != null) {
                if (_context.banlist().isBanlisted(h)) {
                    _context.banlist().unbanlistRouter(h);
                    addFormNotice(_t("Peer") + " " + _peer + " " + _t("unbanned") );
                } else
                    addFormNotice(_t("Peer") + " " + _peer + " " + _t("is not currently banned") );
                return;
            }
            addFormError(_t("Invalid peer"));
        } else if (_action.equals(_t("Adjust peer bonuses"))) {
            Hash h = getHash();
            if (h != null) {
                PeerProfile prof = _context.profileOrganizer().getProfile(h);
                if (prof != null) {
                    try {
                        prof.setSpeedBonus(Integer.parseInt(_speed));
                    } catch (NumberFormatException nfe) {
                        addFormError(_t("Bad speed value"));
                    }
                    try {
                        prof.setCapacityBonus(Integer.parseInt(_capacity));
                    } catch (NumberFormatException nfe) {
                        addFormError(_t("Bad capacity value"));
                    }
                    addFormNotice("Bonuses adjusted for " + _peer);
                } else
                    addFormError("No profile exists for " + _peer);
                return;
            }
            addFormError(_t("Invalid peer"));
        } else if (_action.startsWith("Check")) {
            addFormError(_t("Unsupported"));
        } else {
            //addFormError(_t("Unsupported") + ' ' + _action + '.');
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
