package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.ConvertToHash;

/**
 *  Support additions via B64 Destkey, B64 Desthash, or blahblah.i2p
 */
public class ConfigKeyringHandler extends FormHandler {
    private String _peer;
    private String _key;
    
    protected void processForm() {
        if ("Add key".equals(_action)) {
            if (_peer == null || _key == null) {
                addFormError("You must enter a destination and a key");
                return;
            }
            Hash h = ConvertToHash.getHash(_peer);
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
        } else {
            addFormError("Unsupported");
        }
    }

    public void setPeer(String peer) { _peer = peer; }
    public void setKey(String peer) { _key = peer; }
}
