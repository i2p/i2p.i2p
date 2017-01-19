package net.i2p.router.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

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
      * @param peer non-null
      * @param mask is 1-4 (number of bytes to match)
      */
    public MaskedIPSet(RouterContext ctx, Hash peer, int mask) {
        this(ctx, peer, ctx.netDb().lookupRouterInfoLocally(peer), mask);
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
            if (pa.getCost() == 2 && "NTCP".equals(pa.getTransportStyle()))
                add("=cost2");
        }
        String family = pinfo.getOption("family");
        if (family != null) {
            // TODO should KNDF put a family-verified indicator in the RI,
            // after checking the sig, or does it matter?
            // What's the threat here of not avoid ding a router
            // falsely claiming to be in the family?
            // Prefix with something so an IP can't be spoofed
            add('x' + family);
        }
    }

    /**
     * generate an arbitrary unique value for this ip/mask (mask = 1-4)
     * If IPv6, force mask = 6.
     * @param mask is 1-4 (number of bytes to match)
     */
    private static String maskedIP(byte[] ip, int mask) {
        final StringBuilder buf = new StringBuilder(1 + (mask*2));
        final char delim;
        if (ip.length == 16) {
            mask = 6;
            delim = ':';
        } else {
            delim = '.';
        }
        buf.append(delim);
        buf.append(Long.toHexString(DataHelper.fromLong(ip, 0, mask)));
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
