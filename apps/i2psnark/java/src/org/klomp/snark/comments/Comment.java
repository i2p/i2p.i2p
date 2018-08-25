/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package org.klomp.snark.comments;

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * Store a single comment and/or rating.
 * Unmodifiable except for marking as hidden.
 * Stores a one-second timestamp but designed so identical
 * comments within a certain time frame (bucket) are equal.
 * Don't store in a plain set - see equals().
 *
 * @since 0.9.31
 */
public class Comment implements Comparable<Comment> {

    private final String text, name;
    // seconds since 1/1/2005
    private final int time;
    private final byte rating;
    private final boolean byMe;
    private boolean hidden;
    private static final AtomicInteger _id = new AtomicInteger();
    private final int id = _id.incrementAndGet();

    public static final int MAX_NAME_LEN = 32;
    // same as IRC, more or less
    private static final int MAX_TEXT_LEN = 512;
    private static final int BUCKET_SIZE = 10*60*1000;
    private static final long TIME_SHRINK = 1000L;
    private static final int MAX_SKEW = (int) (BUCKET_SIZE / TIME_SHRINK);
    // 1/1/2005
    private static final long TIME_OFFSET = 1104537600000L;

    /**
     *  My comment, now
     *
     *  @param text may be null, will be truncated to max length, newlines replaced with spaces
     *  @param name may be null, will be truncated to max length, newlines and commas removed
     *  @param rating 0-5
     */
    public Comment(String text, String name, int rating) {
        this(text, name, rating, I2PAppContext.getGlobalContext().clock().now(), true);
    }

    /**
     *  @param text may be null, will be truncated to max length, newlines replaced with spaces
     *  @param name may be null, will be truncated to max length, newlines and commas removed
     *  @param time java time (ms)
     *  @param rating 0-5
     */
    public Comment(String text, String name, int rating, long time, boolean isMine) {
        if (text != null) {
            text = text.trim();
            text = text.replaceAll("[\r\n]", " ");
            if (text.length() == 0)
                text = null;
            else if (text.length() > MAX_TEXT_LEN)
                text = text.substring(0, MAX_TEXT_LEN);
        }
        this.text = text;
        if (name != null) {
            name = name.trim();
            // comma because it's not last in the persistent string
            name = name.replaceAll("[,\r\n]", "");
            if (name.length() == 0)
                name = null;
            else if (name.length() > MAX_NAME_LEN)
                name = name.substring(0, MAX_NAME_LEN);
        }
        this.name = name;
        if (rating < 0)
            rating = 0;
        else if (rating > 5)
            rating = 5;
        this.rating = (byte) rating;
        if (time < TIME_OFFSET) {
            time = TIME_OFFSET;
        } else {
            long now = I2PAppContext.getGlobalContext().clock().now();
            if (time > now)
                time = now;
        }
        this.time = (int) ((time - TIME_OFFSET) / TIME_SHRINK);
        this.byMe = isMine;
    }

    public String getText() { return text; }

    public String getName() { return name; }

    public int getRating() { return rating; }

    /** java time (ms) */
    public long getTime() { return (time * TIME_SHRINK) + TIME_OFFSET; }

    public boolean isMine() { return byMe; }

    public boolean isHidden() { return hidden; }

    void setHidden() { hidden = true; }

    /**
     *  A unique ID that may be used to delete this comment from
     *  the CommentSet via remove(int). NOT persisted across restarts.
     */
    public int getID() { return id; }

    /**
     *  reverse
     */
    public int compareTo(Comment c) {
        if (time > c.time)
            return -1;
        if (time < c.time)
            return 1;
        // arbitrary sort below here
        if (rating != c.rating)
            return c.rating - rating;
        if (name != null || c.name != null) {
            if (name == null)
                return 1;
            if (c.name == null)
                return -1;
            int rv = name.compareTo(c.name);
            if (rv != 0)
                return rv;
        }
        if (text != null || c.text != null) {
            if (text == null)
                return 1;
            if (c.text == null)
                return -1;
            int rv = text.compareTo(c.text);
            if (rv != 0)
                return rv;
        }
        return 0;
    }

    /**
     *  @return time,rating,mine,hidden,name,text
     */
    public String toPersistentString() {
        StringBuilder buf = new StringBuilder();
        buf.append(getTime());
        buf.append(',');
        buf.append(Byte.toString(rating));
        buf.append(',');
        buf.append(byMe ? "1" : "0");
        buf.append(',');
        buf.append(hidden ? "1" : "0");
        buf.append(',');
        if (name != null)
            buf.append(name);
        buf.append(',');
        if (text != null)
            buf.append(text);
        return buf.toString();
    }

    /**
     *  @return null if can't be parsed
     */
    public static Comment fromPersistentString(String s) {
        String[] ss = DataHelper.split(s, ",", 6);
        if (ss.length != 6)
            return null;
        try {
            long t = Long.parseLong(ss[0]);
            int r = Integer.parseInt(ss[1]);
            boolean m = !ss[2].equals("0");
            boolean h = !ss[3].equals("0");
            Comment rv = new Comment(ss[5], ss[4], r, t, m);
            if (h)
                rv.setHidden();
            return rv;
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /**
     *  @return bucket number
     */
    @Override
    public int hashCode() {
        return time / MAX_SKEW;
    }

    /**
     *  Comments within 10 minutes (not necessarily in same bucket)
     *  and otherwise equal are considered equal.
     *  Violates contract, as equal objects may have different hashcodes and
     *  be in adjacent buckets.
     */
    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (!(o instanceof Comment)) return false;
        Comment c = (Comment) o;
        int tdiff = time - c.time;
        if (tdiff > MAX_SKEW || tdiff < 0 - MAX_SKEW)
            return false;
        return equalsIgnoreTimestamp(c);
    }

    /**
     *  Ignores timestamp
     *  @param c non-null
     */
    public boolean equalsIgnoreTimestamp(Comment c) {
        return rating == c.rating &&
               eq(text, c.text) &&
               eq(name, c.name);
    }

    private static boolean eq(String lhs, String rhs) {
        return (lhs == null && rhs == null) || (lhs != null && lhs.equals(rhs));
    }
}
