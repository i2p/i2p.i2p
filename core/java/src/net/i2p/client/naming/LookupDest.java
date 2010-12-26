/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package net.i2p.client.naming;

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSimpleClient;
import net.i2p.data.Base32;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 * Connect via I2CP and ask the router to look up
 * the lease of a hash, convert it to a Destination and return it.
 * Obviously this can take a while.
 *
 * All calls are blocking and return null on failure.
 * Timeout is set to 10 seconds in I2PSimpleSession.
 *
 * As of 0.8.3, standard I2PSessions support lookups,
 * including multiple lookups in parallel, and overriding
 * the default timeout.
 * Using an existing I2PSession is much more efficient and
 * flexible than using this class.
 *
 */
class LookupDest {

    protected LookupDest(I2PAppContext context) {}

    /** @param key 52 chars (do not include the .b32.i2p suffix) */
    static Destination lookupBase32Hash(I2PAppContext ctx, String key) {
        byte[] h = Base32.decode(key);
        if (h == null)
            return null;
        return lookupHash(ctx, h);
    }

    /* Might be useful but not in the context of urls due to upper/lower case */
    /****
    static Destination lookupBase64Hash(I2PAppContext ctx, String key) {
        byte[] h = Base64.decode(key);
        if (h == null)
            return null;
        return lookupHash(ctx, h);
    }
    ****/

    /** @param h 32 byte hash */
    static Destination lookupHash(I2PAppContext ctx, byte[] h) {
        Hash key = new Hash(h);
        Destination rv = null;
        try {
            I2PClient client = new I2PSimpleClient();
            Properties opts = new Properties();
            String s = ctx.getProperty(I2PClient.PROP_TCP_HOST);
            if (s != null)
                opts.put(I2PClient.PROP_TCP_HOST, s);
            s = ctx.getProperty(I2PClient.PROP_TCP_PORT);
            if (s != null)
                opts.put(I2PClient.PROP_TCP_PORT, s);
            I2PSession session = client.createSession(null, opts);
            session.connect();
            rv = session.lookupDest(key);
            session.destroySession();
        } catch (I2PSessionException ise) {}
        return rv;
    }

    public static void main(String args[]) {
        Destination dest = lookupBase32Hash(I2PAppContext.getGlobalContext(), args[0]);
        if (dest == null)
            System.out.println("Destination not found!");
        else
            System.out.println(dest.toBase64());
    }
}
