package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Base64;
import net.i2p.router.Router;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

/**
 *
 */
public class ConfigPeerHandler extends FormHandler {
    private String _peer;
    private String _speed;
    private String _capacity;
    
    protected void processForm() {
        if ("Save Configuration".equals(_action)) {
            _context.router().saveConfig();
            addFormNotice("Settings saved - not really!!!!!");
        } else if (_action.startsWith("Ban")) {
            Hash h = getHash();
            if (h != null) {
                _context.shitlist().shitlistRouterForever(h, "Manually banned via <a href=\"configpeer.jsp\">configpeer.jsp</a>");
                addFormNotice("Peer " + _peer + " banned until restart");
                return;
            }
            addFormError("Invalid peer");
        } else if (_action.startsWith("Unban")) {
            Hash h = getHash();
            if (h != null) {
                if (_context.shitlist().isShitlisted(h)) {
                    _context.shitlist().unshitlistRouter(h);
                    addFormNotice("Peer " + _peer + " unbanned");
                } else
                    addFormNotice("Peer " + _peer + " is not currently banned");
                return;
            }
            addFormError("Invalid peer");
        } else if (_action.startsWith("Adjust")) {
            Hash h = getHash();
            if (h != null) {
                PeerProfile prof = _context.profileOrganizer().getProfile(h);
                if (prof != null) {
                    try {
                        prof.setSpeedBonus(Long.parseLong(_speed));
                    } catch (NumberFormatException nfe) {
                        addFormError("Bad speed value");
                    }
                    try {
                        prof.setCapacityBonus(Long.parseLong(_capacity));
                    } catch (NumberFormatException nfe) {
                        addFormError("Bad capacity value");
                    }
                    addFormNotice("Bonuses adjusted for " + _peer);
                } else
                    addFormError("No profile exists for " + _peer);
                return;
            }
            addFormError("Invalid peer");
        } else if (_action.startsWith("Check")) {
            addFormError("Unsupported");
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
