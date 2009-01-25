package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

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
            Hash h = new Hash();
            try {
                h.fromBase64(_peer);
            } catch (DataFormatException dfe) {}
            if (h.getData() == null) {
                try {
                    Destination d = new Destination();
                    d.fromBase64(_peer);
                    h = d.calculateHash();
                } catch (DataFormatException dfe) {}
            }
            if (h.getData() == null) {
                Destination d = _context.namingService().lookup(_peer);
                if (d != null)
                    h = d.calculateHash();
            }
            SessionKey sk = new SessionKey();
            try {
                sk.fromBase64(_key);
            } catch (DataFormatException dfe) {}
            if (h.getData() != null && sk.getData() != null) {
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
