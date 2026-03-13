package net.i2p.util;

import java.io.IOException;
import java.io.Writer;

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 *  See net.i2p.router.PersistentKeyRing for extension.
 *
 *  Deprecated -
 *  This is for the deprecated LS1 AES encrypted leasesets only.
 *  LS2 encrypted leaseset data are stored in netdb BlindCache and router.blindcache.dat.
 */
public class KeyRing extends ConcurrentHashMap<Hash, SessionKey> {
    public KeyRing() {
        super(0);
    }

    /**
     *  @deprecated unused since 0.9.33; code moved to routerconsole
     */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {}
}
