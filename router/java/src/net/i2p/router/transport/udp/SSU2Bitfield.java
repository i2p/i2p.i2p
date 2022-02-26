package net.i2p.router.transport.udp;

import java.util.Arrays;

import net.i2p.router.transport.udp.SSU2Payload.AckBlock;


/**
 * Container of a long array representing set and unset bits.
 * When a bit higher than the current size + offset is set,
 * the offset shifts up and the lowest set bits are lost.
 *
 * Also contains methods to convert to/from an ACK block.
 *
 * @since 0.9.54
 */
public class SSU2Bitfield {

    private final long[] bitfield;
    private final int size;
    private final int max_shift;
    private final int min_shift;
    private long offset;
    // before offset
    private int highestSet = -1;

    private static final long[] MASKS = new long[64];
    static {
        for (int i = 0; i < 64; i++) {
            MASKS[i] = 1L << i;
        }
    }

    /**
     * Creates a new SSU2Bitfield that represents <code>size</code> unset bits.
     */
    public SSU2Bitfield(int size, long offset) {
        if (size <= 0 || offset < 0)
            throw new IllegalArgumentException();
        // force mult. of 256
        this.size = (size + 255) & 0x7FFFFF00;
        this.offset = offset;
        max_shift = Math.max(1024, size * 8);
        min_shift = Math.max(8, size / 4);
        bitfield = new long[size / 64];
    }

    public int size() {
        return size;
    }

    public long getOffset() {
        return offset;
    }

    /**
     * Sets the given bit to true.
     * When a bit higher than the current size + offset is set,
     * the offset shifts up and the lowest set bits are lost.
     *
     * @throws IndexOutOfBoundsException if bit is smaller then zero
     *                                   OR if the shift is too big
     * @return previous value, true if previously set or unknown
     */
    public boolean set(long bit) throws IndexOutOfBoundsException {
        if (bit < 0)
            throw new IndexOutOfBoundsException(Long.toString(bit));
        boolean rv;
        synchronized(this) {
            bit -= offset;
            // too old?
            if (bit < 0)
                return true;
            if (bit >= size) {
                long shift = bit + 1 - size;
                if (shift > max_shift)
                    throw new IndexOutOfBoundsException("Shift too big: " + shift);
                if (shift < min_shift)
                    shift = min_shift;
                // round up
                if ((shift & 0x3f) != 0)
                    shift = 64 + (shift & 0xc0);
                //System.out.println("Shifting bitfield, offset was " + offset + ", now " + (offset + shift));
                if (shift < size) {
                    // shift down
                    int bshift = (int) (shift / 64);
                    System.arraycopy(bitfield, bshift, bitfield, 0, bitfield.length - bshift);
                    // 2nd idx is exclusive
                    Arrays.fill(bitfield, bitfield.length - bshift, bitfield.length, 0L);
                    if (highestSet >= 0)
                        highestSet -= shift;
                } else {
                    // start over
                    Arrays.fill(bitfield, 0L);
                    highestSet = -1;
                }
                offset += shift;
                bit -= shift;
            }
            int index = (int) (bit >> 6);
            long mask = MASKS[((int) bit) & 0x3F];
            rv = (bitfield[index] & mask) != 0;
            if (!rv) {
                bitfield[index] |= mask;
                if (bit > highestSet) {
                    highestSet = (int) bit;
                }
            }
        }
        return rv;
    }

    /**
     * Return true if the bit is set or false if it is not.
     *
     * @throws IndexOutOfBoundsException if bit is smaller then zero
     */
    public boolean get(long bit) {
        if (bit < 0)
            throw new IndexOutOfBoundsException(Long.toString(bit));
        bit -= offset;
        if (bit < 0 || bit >= size)
            return false;
        int index = (int) (bit >> 6);
        long mask = MASKS[(int) (bit & 0x3F)];
        return (bitfield[index] & mask) != 0;
    }

    /**
     * Return the highest set bit, or -1 if none.
     */
    public synchronized long getHighestSet() {
        if (highestSet < 0)
            return -1;
        return highestSet + offset;
    }

    /**
     *  @param maxRanges may be 0
     *  @return null if nothing is set
     */
    public synchronized AckBlock toAckBlock(int maxRanges) {
        long highest = getHighestSet();
        // nothing to ack
        if (highest < 0)
            return null;
        //int lowest = getLowestUnset();
        byte[] ranges = new byte[maxRanges * 2];
        int acnt = 0;
        // get acnt
        int rangeCount = 0;

        for (long i = highest - 1; i >= offset && acnt < 255; i--) {
            if (!get(i))
                break;
            acnt++;
        }
        // now get ranges
        if (acnt < highest - offset) {
            // cur + 1 is set, cur is unset, start at cur
            long cur = highest - (acnt + 1); 
            for (int r = 0; r < maxRanges; r++) {
                int ncnt = 0;
                for ( ; cur >= offset && ncnt < 255; cur--) {
                    if (get(cur)) {
                        break;
                    }
                    ncnt++;
                }
                int aacnt = 0;
                for ( ; cur >= offset && aacnt < 255; cur--) {
                    if (!get(cur)) {
                        break;
                    }
                    aacnt++;
                }
                if (ncnt == 0 && aacnt == 0)
                    break;
                ranges[rangeCount * 2] = (byte) ncnt;
                ranges[(rangeCount * 2) + 1] = (byte) aacnt;
                rangeCount++;
                if (cur < offset)
                    break;
            }
        }
        //System.out.println(toString(highest, acnt, ranges, rangeCount));
        return new AckBlock(highest, acnt, ranges, rangeCount);
    }

    /**
     *  @param ranges may be null
     */
    public static SSU2Bitfield fromACKBlock(long thru, int acnt, byte[] ranges, int rangeCount) {
        int t = (int) thru;
        if (ranges == null || rangeCount == 0) {
            // easy case, no ranges
            SSU2Bitfield rv = new SSU2Bitfield(acnt + 1, t);
            for (int i = t; i >= t - acnt; i--) {
                rv.set(i);
            }
            return rv;
        }
        // get the minimum acked value
        int min = t - acnt;
        for (int i = 0; i < rangeCount * 2; i++) {
            min -= ranges[i] & 0xff;
        }
        // fixup if the last ack count was zero
        // this doesn't handle multple ranges with a zero ack count
        if (ranges[(rangeCount * 2) - 1] == 0)
            min += ranges[(rangeCount * 2) - 2] & 0xff;

        SSU2Bitfield rv = new SSU2Bitfield(1 + t - min, min);
        for (int i = t; i >= t - acnt; i--) {
            rv.set(i);
        }

        int j = t - (acnt + 1);
        for (int i = 0; i < rangeCount * 2; i += 2) {
            // nack count
            j -= ranges[i] & 0xff;
            // ack count
            int toAck = ranges[i + 1] & 0xff;
            for (int k = 0; k < toAck; k++) {
                rv.set(j--);
            }
        }
        return rv;
    }

    /**
     *  Pretty print an ACK block
     *
     *  @param ranges may be null
     */
    public static String toString(long thru, int acnt, byte[] ranges, int rangeCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("ACK ").append(thru);
        int cur = (int) thru;
        if (acnt > 0) {
            sb.append('-').append(thru - acnt);
            cur -= acnt;
        }
        if (ranges != null) {
            cur--;
            for (int i = 0; i < rangeCount * 2; i += 2) {
                int nacks = ranges[i] & 0xff;
                if (nacks > 0) {
                    sb.append(" NACK ").append(cur);
                    if (nacks > 1) {
                        sb.append('-').append(cur - (nacks - 1));
                    }
                    cur -= nacks;
                }
                int acks = ranges[i+1] & 0xff;
                if (acks > 0) {
                    sb.append(" ACK ").append(cur);
                    if (acks > 1) {
                        sb.append('-').append(cur - (acks - 1));
                    }
                    cur -= acks;
                }
            }
        }
        sb.append("    RAW: ").append(thru).append(" A:").append(acnt);
        if (ranges != null) {
            for (int i = 0; i < rangeCount * 2; i += 2) {
                sb.append(" N:").append(ranges[i] & 0xff);
                sb.append(" A:").append(ranges[i + 1] & 0xff);
            }
         }
         return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SSU2Bitfield(");
        sb.append(size).append(")");
        sb.append(" offset: ").append(offset);
        sb.append(" highest set: ").append(getHighestSet());
        sb.append(" [");
        for (long i = offset; i <= getHighestSet(); i++) {
            if (get(i)) {
              sb.append(' ');
              sb.append(i);
            }
        }
        sb.append(" ]");
        return sb.toString();
    }


/****
    public static void main(String[] args) {
        int off = 100;
        SSU2Bitfield bf = new SSU2Bitfield(256, off);
        System.out.println(bf.toString());
        bf.toAckBlock(20);
        bf.set(off);
        System.out.println(bf.toString());
        bf.toAckBlock(20);
        bf.set(off + 1);
        System.out.println(bf.toString());
        bf.toAckBlock(20);
        bf.set(off + 2);
        System.out.println(bf.toString());
        bf.toAckBlock(20);
        bf.set(off + 4);
        System.out.println(bf.toString());
        bf.toAckBlock(20);
        bf.set(off + 5);
        System.out.println(bf.toString());
        bf.toAckBlock(20);

        bf.set(off + 8);
        System.out.println(bf.toString());
        bf.toAckBlock(20);

        bf.set(off + 88);
        System.out.println(bf.toString());
        bf.toAckBlock(20);

        bf.set(off + 254);
        System.out.println(bf.toString());
        bf.toAckBlock(20);

        bf.set(off + 255);
        System.out.println(bf.toString());
        bf.toAckBlock(20);

        bf.set(off + 300);
        System.out.println(bf.toString());
        bf.toAckBlock(20);
    }
****/
}
