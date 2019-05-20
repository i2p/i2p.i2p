package net.i2p.router.sybil;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import net.i2p.data.DataHelper;

/**
 *  A total score and a List of reason Strings
 *
 *  @since 0.9.38 moved from SybilRenderer
 */
public class Points implements Comparable<Points> {
    private double points;
    private final List<String> reasons;

    /**
     *  @since 0.9.38
     */
    private Points() {
        reasons = new ArrayList<String>(4);
    }

    /**
     *  @param reason may not contain '%'
     */
    public Points(double d, String reason) {
        this();
        addPoints(d, reason);
    }

    /**
     *  @since 0.9.38
     */
    public double getPoints() {
        return points;
    }

    /**
     *  @since 0.9.38
     */
    public List<String> getReasons() {
        return reasons;
    }

    /**
     *  @param reason may not contain '%'
     *  @since 0.9.38
     */
    public void addPoints(double d, String reason) {
        points += d;
        DecimalFormat format = new DecimalFormat("#0.00");
        String rsn = format.format(d) + ": " + reason;
        reasons.add(rsn);
    }

    public int compareTo(Points r) {
        return Double.compare(points, r.points);
    }

    /**
     *  @since 0.9.38
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        toString(buf);
        return buf.toString();
    }

    /**
     *  For persistence.
     *  Total points and reasons, '%' separated, no newline.
     *  The separation character is chosen to not conflict with
     *  decimal point in various locales, or chars in reasons, including HTML links,
     *  or special chars in Pattern.
     *
     *  @since 0.9.38
     */
    public void toString(StringBuilder buf) {
        buf.append(points);
        for (String r : reasons) {
            buf.append('%').append(r.replace("%", "&#x25;"));
        }
    }

    /**
     *  For persistence.
     *  @return null on failure
     *  @since 0.9.38
     */
    public static Points fromString(String s) {
        String[] ss = DataHelper.split(s, "%");
        if (ss.length < 2)
            return null;
        double d;
        try {
            d = Double.parseDouble(ss[0]);
        } catch (NumberFormatException nfe) {
            return null;
        }
        Points rv = new Points();
        for (int i = 1; i < ss.length; i++) {
            rv.reasons.add(ss[i]);
        }
        rv.points = d;
        return rv;
    }
}

