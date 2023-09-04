package net.i2p.router.web.helpers;

import java.util.List;

import net.i2p.crypto.Blinding;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.BlindData;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.web.FormHandler;
import net.i2p.util.ConvertToHash;

/**
 *  Support additions via B64 Destkey, B64 Desthash, blahblah.i2p, and others supported by ConvertToHash
 */
public class ConfigKeyringHandler extends FormHandler {
    private String _peer;
    private String _key;
    private String _secret;
    private String[] _revokes;
    private int _mode;
    
    @Override
    protected void processForm() {
        if (_action.equals(_t("Add key"))) {
            if (_peer == null) {
                addFormError(_t("You must enter a destination"));
                return;
            }
            Hash h = null;
            if (!_peer.endsWith(".b32.i2p") || _peer.length() <= 60) {
                // don't wait for several seconds for b33 lookup
                h = ConvertToHash.getHash(_peer);
            }

                byte[] b = null;
                if (_mode == 1 || _mode == 4 || _mode == 5) {
                    if (_key == null) {
                        addFormError(_t("You must enter a key"));
                        return;
                    }
                    b = Base64.decode(_key);
                    if (b == null || b.length != 32) {
                        addFormError(_t("Invalid key"));
                        return;
                    }
                }
                if (_mode == 1) {
                    // LS1
                    if (h == null || h.getData() == null) {
                        addFormError(_t("Invalid destination"));
                    } else if (_context.clientManager().isLocal(h)) {
                        // don't bother translating
                        addFormError("Cannot add key for local destination. Enable encryption in the Hidden Services Manager.");
                    } else {
                        SessionKey sk = new SessionKey(b);
                        _context.keyRing().put(h, sk);
                        addFormNotice(_t("Key for {0} added to keyring", h.toBase32()));
                    }
                } else {
                    if ((_mode == 3 || _mode == 5 || _mode == 7) && _secret == null) {
                        addFormError(_t("Lookup password required"));
                        return;
                    }
                    // b33 if supplied as hostname
                    BlindData bdin = null;
                    try {
                        bdin = Blinding.decode(_context, _peer);
                    } catch (IllegalArgumentException iae) {}

                    // we need the dest or the spk, not just the desthash
                    SigningPublicKey spk = null;
                    Destination d = null;
                    // don't cause LS fetch
                    if (!_peer.endsWith(".b32.i2p"))
                        d = _context.namingService().lookup(_peer);
                    if (d != null) {
                        spk = d.getSigningPublicKey();
                    } else if (bdin != null) {
                        spk = bdin.getUnblindedPubKey();
                    }
                    if (spk == null) {
                        addFormError(_t("Requires hostname, destination, or blinded Base32"));
                        return;
                    }
                    // from BlindCache
                    List<String> clientBase32s = _context.netDb().lookupClientBySigningPublicKey(spk);
                    // TODO: This updates all of the blind data for all clients, turning the blind cache into a shared context for the owner of an encrypted leaseSet.
                    // This is probably not ideal, with some social-engineering a service operator who owns an encrypted destination could associate 2 tunnels.
                    // How realistic is it? Maybe not very, but I don't like it. Still, this is better than nothing.
                    for (String clientBase32 : clientBase32s) {
                        BlindData bdold = _context.netDb().getBlindData(spk, clientBase32);
                        if (bdold != null && d == null)
                            d = bdold.getDestination();
                        if (d != null && _context.clientManager().isLocal(d)) {
                            // don't bother translating
                            addFormError("Cannot add key for local destination. Enable encryption in the Hidden Services Manager.");
                            return;
                        }

                        SigType blindType;
                        if (bdin != null) {
                            blindType = bdin.getBlindedSigType();
                        } else if (bdold != null) {
                            blindType = bdold.getBlindedSigType();
                        } else {
                            blindType = Blinding.getDefaultBlindedType(spk.getType());
                        }

                        int atype;
                        PrivateKey pk;
                        if (_mode == 4 || _mode == 5) {
                            atype = BlindData.AUTH_PSK;
                            // use supplied pk
                            pk = new PrivateKey(EncType.ECIES_X25519, b);
                        } else if (_mode == 6 || _mode == 7) {
                            atype = BlindData.AUTH_DH;
                            // create new pk
                            b = new byte[32];
                            _context.random().nextBytes(b);
                            pk = new PrivateKey(EncType.ECIES_X25519, b);
                        } else {
                            // modes 2 and 3
                            atype = BlindData.AUTH_NONE;
                            pk = null;
                        }
                        if (_mode == 2 || _mode == 4 || _mode == 6)
                            _secret = null;
                        if (bdin != null) {
                            // more checks based on supplied b33
                            if (bdin.getSecretRequired() && _secret == null) {
                                addFormError(_t("Destination requires lookup password"));
                                return;
                            }
                            if (!bdin.getSecretRequired() && _secret != null) {
                                addFormError(_t("Destination does not require lookup password"));
                                return;
                            }
                            if (bdin.getAuthRequired() && pk == null) {
                                addFormError(_t("Destination requires encryption key"));
                                return;
                            }
                            if (!bdin.getAuthRequired() && pk != null) {
                                addFormError(_t("Destination does not require encryption key"));
                                return;
                            }
                        }

                        // to BlindCache
                        BlindData bdout;
                        if (d != null) {
                            bdout = new BlindData(_context, d, blindType, _secret, atype, pk);
                        } else {
                            bdout = new BlindData(_context, spk, blindType, _secret, atype, pk);
                        }
                        if (bdold != null) {
                            if (_log.shouldDebug())
                                _log.debug("already cached: " + bdold);
                        }
                        try {
                            _context.netDb().setBlindData(bdout, clientBase32);
                            addFormNotice(_t("Key for {0} added to keyring", bdout.toBase32()));
                            if (_mode == 6 || _mode == 7) {
                                addFormNotice(_t("Send key to server operator.") + ' ' + pk.toPublic().toBase64());
                            }
                        } catch (IllegalArgumentException iae) {
                            addFormError(_t("Invalid destination") + ": " + iae.getLocalizedMessage());
                        }
                    }
                }
        } else if (_action.equals(_t("Delete key")) && _revokes != null) {
            // these should all be b32s or b33s
            for (String p : _revokes) {
                boolean removed = false;
                if (p.length() == 60) {
                    // don't wait for several seconds for b33 lookup
                    Hash h = ConvertToHash.getHash(p);
                    if (h != null) {
                        if (_context.clientManager().isLocal(h)) {
                            // don't bother translating
                            addFormError("Cannot remove key for local destination. Disable encryption in the Hidden Services Manager.");
                        } else if (_context.keyRing().remove(h) != null) {
                            removed = true;
                        }
                    }
                } else if (p.length() > 60) {
                    try {
                        BlindData bd = Blinding.decode(_context, p);
                        if (bd != null) {
                            SigningPublicKey spk = bd.getUnblindedPubKey();
                            removed = _context.mainNetDb().removeBlindData(spk);
                        }
                    } catch (IllegalArgumentException iae) {}
                } else {
                    addFormError(_t("Invalid destination") + ": " + p);
                }
                if (removed) {
                    addFormNotice(_t("Key for {0} removed from keyring", p));
                } else {
                    addFormError(_t("Key for {0} not found in keyring", p));
                }
            }
        } else {
            //addFormError(_t("Unsupported"));
        }
    }

    public void setPeer(String peer) { if (peer != null) _peer = peer.trim(); }
    public void setKey(String key) { if (key != null) _key = key.trim(); }

    /** @since 0.9.41 */
    public void setNofilter_blindedPassword(String pw) {
         if (pw != null) {
             pw = pw.trim();
             if (pw.length() > 0)
                 _secret = pw;
        }
    }

    /** @since 0.9.41 */
    public void setEncryptMode(String m) {
        try {
             _mode = Integer.parseInt(m);
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.41 */
    public void setRevokeClient(String[] revokes) { _revokes = revokes; }
}
