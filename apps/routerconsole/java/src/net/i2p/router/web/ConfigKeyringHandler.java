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
        boolean adding = _action.startsWith("Add");
        if (adding || _action.startsWith("Delete")) {
            if (_peer == null)
                addFormError("You must enter a destination");
            if (_key == null && adding)
                addFormError("You must enter a key");
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
                    addFormNotice("Key for " + h.toBase64() + " added to keyring");
                } else {
                    addFormError("Invalid destination or key");
                }
            } else {  // Delete
                if (h != null && h.getData() != null) {
                    if (_context.keyRing().remove(h) != null)
                        addFormNotice("Key for " + h.toBase64() + " removed from keyring");
                    else
                        addFormNotice("Key for " + h.toBase64() + " not found in keyring");
                } else {
                    addFormError("Invalid destination");
                }
            }
        } else {
            addFormError("Unsupported");
        }
    }

    public void setPeer(String peer) { _peer = peer; }
    public void setKey(String peer) { _key = peer; }
}
