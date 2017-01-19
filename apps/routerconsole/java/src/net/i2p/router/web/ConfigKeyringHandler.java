package net.i2p.router.web;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
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
                if (h != null && h.getData() != null && sk.getData() != null) {
                    _context.keyRing().put(h, sk);
                    addFormNotice(_t("Key for") + " " + h.toBase64() + " " + _t("added to keyring"));
                } else {
                    addFormError(_t("Invalid destination or key"));
                }
            } else {  // Delete
                if (h != null && h.getData() != null) {
                    if (_context.keyRing().remove(h) != null)
                        addFormNotice(_t("Key for") + " " + h.toBase64() + " " + _t("removed from keyring"));
                    else
                        addFormNotice(_t("Key for") + " " + h.toBase64() + " " + _t("not found in keyring"));
                } else {
                    addFormError(_t("Invalid destination"));
                }
            }
        } else {
            //addFormError(_t("Unsupported"));
        }
    }

    public void setPeer(String peer) { _peer = peer; }
    public void setKey(String peer) { _key = peer; }
}
