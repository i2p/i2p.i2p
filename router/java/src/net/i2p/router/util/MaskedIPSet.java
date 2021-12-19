package net.i2p.router.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;

/**
 *  Used for detection of routers with matching IPs or family.
 *  Moved out of ProfileOrganizer for use in netdb also.
 *
 *  @since 0.9.28
 */
public class MaskedIPSet extends HashSet<String> {

    public MaskedIPSet() {
        super();
    }

    public MaskedIPSet(int initialCapacity) {
        super(initialCapacity);
    }

    /**
      * The Set of IPs for this peer, with a given mask.
      * Includes the comm system's record of the IP, and all netDb addresses.
      *
      * As of 0.9.24, returned set will include netdb family as well.
      *
      * This gets the peer from the netdb without validation,
      * for efficiency and to avoid deadlocks.
      * Peers are presumed to be validated elsewhere.
      *
      * @param peer non-null
      * @param mask is 1-4 (number of bytes to match)
      */
    public MaskedIPSet(RouterContext ctx, Hash peer, int mask) {
        this(ctx, peer, lookupRILocally(ctx, peer), mask);
    }

    /**
      * This gets the peer from the netdb without validation,
      * for efficiency and to avoid deadlocks.
      *
      * @since 0.9.38
      */
    private static RouterInfo lookupRILocally(RouterContext ctx, Hash peer) {
        DatabaseEntry ds = ctx.netDb().lookupLocallyWithoutValidation(peer);
        if (ds != null) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                return (RouterInfo) ds;
        }
        return null;
    }

    /**
      * The Set of IPs for this peer, with a given mask.
      * Includes the comm system's record of the IP, and all netDb addresses.
      *
      * As of 0.9.24, returned set will include netdb family as well.
      *
      * @param pinfo may be null
      * @param mask is 1-4 (number of bytes to match)
      */
    public MaskedIPSet(RouterContext ctx, RouterInfo pinfo, int mask) {
        this(ctx, pinfo != null ? pinfo.getHash() : null, pinfo, mask);
    }

    /**
      * The Set of IPs for this peer, with a given mask.
      * Includes the comm system's record of the IP, and all netDb addresses.
      *
      * As of 0.9.24, returned set will include netdb family as well.
      *
      * @param pinfo may be null
      * @param mask is 1-4 (number of bytes to match)
      */
    public MaskedIPSet(RouterContext ctx, Hash peer, RouterInfo pinfo, int mask) {
        super(4);
        if (pinfo == null)
            return;
        byte[] commIP = ctx.commSystem().getIP(peer);
        if (commIP != null)
            add(maskedIP(commIP, mask));
        Collection<RouterAddress> paddr = pinfo.getAddresses();
        for (RouterAddress pa : paddr) {
            byte[] pib = pa.getIP();
            if (pib == null) continue;
            add(maskedIP(pib, mask));
            // Routers with a common port may be run
            // by a single entity with a common configuration
            int port = pa.getPort();
            if (port > 0)
                add("p" + port);
        }
        String family = pinfo.getOption("family");
        if (family != null) {
            // TODO should KNDF put a family-verified indicator in the RI,
            // after checking the sig, or does it matter?
            // What's the threat here of not avoiding a router
            // falsely claiming to be in the family?
            // Prefix with something so an IP can't be spoofed
            add('x' + family);
        }
    }

    /**
     * generate an arbitrary unique value for this ip/mask (mask = 1-4)
     * If IPv6, double the mask value
     * @param mask is 1-4 (number of bytes to match)
     */
    private static String maskedIP(byte[] ip, int mask) {
        final char delim;
        if (ip.length == 16) {
            mask *= 2;
            delim = ':';
        } else {
            delim = '.';
        }
        final StringBuilder buf = new StringBuilder(1 + (mask*2));
        buf.append(delim);
        for (int i = 0; i < mask; i++) {
            // fake hex "0123456789:;<=>?"
            byte b = ip[i];
            buf.append('0' + (char) ((b >> 4) & 0x0f));
            buf.append('0' + (char) (b & 0x0f));
        }
        return buf.toString();
    }

    /** does this contain any of the elements in b? */
    public boolean containsAny(Set<String> b) {
        if (isEmpty() || b.isEmpty())
            return false;
        for (String s : b) {
            if (contains(s))
                return true;
        }
        return false;
    }
}
