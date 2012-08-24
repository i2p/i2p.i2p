package net.i2p.client;

import net.i2p.data.DateAndFlags;

/**
 *  Advanced options attached to a single outgoing I2CP message.
 *
 *  Note that the packing of options into the 16-bit flags field is
 *  is subject to change. Therefore, for now, this is only recommended
 *  within RouterContext.
 *
 *  Static methods are for OutboundClientMessageOneShotJob to decode the
 *  flags field on the router side.
 *
 *  @since 0.9.2
 */
public class SendMessageOptions extends DateAndFlags {

    /** all subject to change */

    /**
     *  1 means don't send, 0 means default
     */
    private static final int LS_MASK = 0x0001;

    /**
     *  Tags to send field:
     *<pre>
     *  000 - default
     *  001 -   2
     *  010 -   4
     *  011 -   8
     *  100 -  16
     *  101 -  32
     *  110 -  64
     *  111 - 128
     *</pre>
     */
    private static final int TAGS_SEND_MASK = 0x000e;
    private static final int MAX_SEND_TAGS = 128;

    /**
     *  Tags threshold field:
     *<pre>
     *  000 - default
     *  001 -  1
     *  010 -  2
     *  011 -  4
     *  100 -  8
     *  101 - 16
     *  110 - 32
     *  111 - 64
     *</pre>
     */
    private static final int TAGS_REQD_MASK = 0x0070;
    private static final int MAX_REQD_TAGS = 64;

    /** default true */
    public void setSendLeaseSet(boolean yes) {
        if (yes)
            _flags &= ~LS_MASK;
        else
            _flags |= LS_MASK;
    }

    /** default true */
    public boolean getSendLeaseSet() {
        return getSendLeaseSet(_flags);
    }

    /** default true */
    public static boolean getSendLeaseSet(int flags) {
        return (flags & LS_MASK) == 0;
    }

    /**
     *  If we are low on tags, send this many.
     *  Power of 2 recommended - rounds down.
     *  default 0, meaning unset
     *  @param tags 0 or 2 to 128
     */
    public void setTagsToSend(int tags) {
        if (tags < 0)
            throw new IllegalArgumentException();
        _flags &= ~TAGS_SEND_MASK;
        _flags |= linToExp(Math.min(tags, MAX_SEND_TAGS) / 2) << 1;
    }

    /**
     *  If we are low on tags, send this many.
     *  @return default 0, meaning unset
     */
    public int getTagsToSend() {
        return getTagsToSend(_flags);
    }

    /**
     *  If we are low on tags, send this many.
     *  @return default 0, meaning unset
     */
    public static int getTagsToSend(int flags) {
        int exp = (flags & TAGS_SEND_MASK) >> 1;
        return 2 * expToLin(exp);
    }

    /**
     *  Low tag threshold. If less than this many, send more.
     *  Power of 2 recommended - rounds down.
     *  default 0, meaning unset
     *  @param tags 0 to 64
     */
    public void setTagThreshold(int tags) {
        if (tags < 0)
            throw new IllegalArgumentException();
        _flags &= ~TAGS_REQD_MASK;
        _flags |= linToExp(Math.min(tags, MAX_REQD_TAGS)) << 4;
    }

    /**
     *  Low tag threshold. If less than this many, send more.
     *  @return default 0, meaning unset
     */
    public int getTagThreshold() {
        return getTagThreshold(_flags);
    }

    /**
     *  Low tag threshold. If less than this many, send more.
     *  @return default 0, meaning unset
     */
    public static int getTagThreshold(int flags) {
        int exp = (flags & TAGS_REQD_MASK) >> 4;
        return expToLin(exp);
    }

    /** rounds down */
    private static int linToExp(int lin) {
        int exp = 0;
        while (lin > 0) {
            exp++;
            lin >>= 1;
        }
        return exp;
    }

    private static int expToLin(int exp) {
        if (exp <= 0)
            return 0;
        return 1 << (exp - 1);
    }
}
