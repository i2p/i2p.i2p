package net.i2p.router;

import java.io.IOException;
import java.io.Writer;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.SessionKey;
import net.i2p.util.KeyRing;

/**
 *  ConcurrentHashMap with backing in the router.config file.
 *  router.keyring.key.{base64 hash, with = replaced with $}={base64 session key}
 *  Caution - not all HashMap methods are overridden.
 */
public class PersistentKeyRing extends KeyRing {

    private static final long serialVersionUID = 1L;
    private transient final RouterContext _ctx;
    private static final String PROP_PFX = "router.keyring.key.";

    public PersistentKeyRing(RouterContext ctx) {
        super();
        _ctx = ctx;
        addFromProperties();
    }

    @Override
    public SessionKey put(Hash h, SessionKey sk) {
        SessionKey old = super.put(h, sk);
        if (!sk.equals(old)) {
            _ctx.router().saveConfig(PROP_PFX + h.toBase64().replace("=", "$"),
                                           sk.toBase64());
        }
        return old;
    }

    @Override
    public SessionKey remove(Object o) {
        SessionKey rv = super.remove(o);
        if (rv != null && o != null && o instanceof Hash) {
            Hash h = (Hash) o;
            _ctx.router().saveConfig(PROP_PFX + h.toBase64().replace("=", "$"), null);
        }
        return rv;
    }

    private void addFromProperties() {
        for (String prop : _ctx.getPropertyNames()) {
            if (!prop.startsWith(PROP_PFX))
                continue;
            String key = _ctx.getProperty(prop);
            if (key == null || key.length() != 44)
                continue;
            String hb = prop.substring(PROP_PFX.length());
            hb = hb.replace("$", "=");
            Hash dest = new Hash();
            SessionKey sk = new SessionKey();
            try {
                dest.fromBase64(hb);
                sk.fromBase64(key);
                super.put(dest, sk);
            } catch (DataFormatException dfe) { continue; }
        }
    }
}
