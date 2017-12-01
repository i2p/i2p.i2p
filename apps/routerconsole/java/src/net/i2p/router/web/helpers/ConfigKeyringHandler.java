package net.i2p.router.web.helpers;

import net.i2p.data.Base32;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.web.FormHandler;
import net.i2p.util.ConvertToHash;

/**
 *  Support additions via B64 Destkey, B64 Desthash, blahblah.i2p, and others supported by ConvertToHash
 */
public class ConfigKeyringHandler extends FormHandler {
    private String _peer;
    private String _key;
    
    @Override
    protected void processForm() {
        if (_action == null) return;
        boolean adding = _action.equals(_t("Add key"));
        if (adding || _action.equals(_t("Delete key"))) {
            if (_peer == null)
                addFormError(_t("You must enter a destination"));
            if (_key == null && adding)
                addFormError(_t("You must enter a key"));
            if (_peer == null || (_key == null && adding))
                return;
            Hash h = ConvertToHash.getHash(_peer);
            if (adding) {
                SessionKey sk = new SessionKey();
                try {
                    sk.fromBase64(_key);
                } catch (DataFormatException dfe) {}
                if (h == null || h.getData() == null) {
                    addFormError(_t("Invalid destination"));
                } else if (sk.getData() == null) {
                    addFormError(_t("Invalid key"));
                } else {
                    _context.keyRing().put(h, sk);
                    addFormNotice(_t("Key for {0} added to keyring",
                                     Base32.encode(h.getData()) + ".b32.i2p"));
                }
            } else {  // Delete
                if (h != null && h.getData() != null) {
                    if (_context.keyRing().remove(h) != null)
                        addFormNotice(_t("Key for {0} removed from keyring",
                                         Base32.encode(h.getData()) + ".b32.i2p"));
                    else
                        addFormNotice(_t("Key for {0} not found in keyring",
                                         Base32.encode(h.getData()) + ".b32.i2p"));
                } else {
                    addFormError(_t("Invalid destination"));
                }
            }
        } else {
            //addFormError(_t("Unsupported"));
        }
    }

    public void setPeer(String peer) { if (peer != null) _peer = peer.trim(); }
    public void setKey(String key) { if (key != null) _key = key.trim(); }
}
