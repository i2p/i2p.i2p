package net.i2p.util;

import java.util.Comparator;
import java.util.StringTokenizer;

/**
 * Compares versions.
 * Characters other than [0-9.-_] are ignored.
 * I2P only uses '.' but Sun Java uses '_' and plugins may use any of '.-_'
 * Moved from TrustedUpdate.java
 * @since 0.7.10
 */
public class VersionComparator implements Comparator<String> {
    /** l and r non-null */
    public int compare(String l, String r) {
        // try it the easy way first
        if (l.equals(r))
            return 0;
        StringTokenizer lTokens = new StringTokenizer(sanitize(l), VALID_SEPARATOR_CHARS);
        StringTokenizer rTokens = new StringTokenizer(sanitize(r), VALID_SEPARATOR_CHARS);

        while (lTokens.hasMoreTokens() && rTokens.hasMoreTokens()) {
            String lNumber = lTokens.nextToken();
            String rNumber = rTokens.nextToken();
            int diff = intCompare(lNumber, rNumber);
            if (diff != 0)
                return diff;
        }

        if (lTokens.hasMoreTokens() && !rTokens.hasMoreTokens())
            return 1;
        if (rTokens.hasMoreTokens() && !lTokens.hasMoreTokens())
            return -1;
        return 0;
    }

    private static final int intCompare(String lop, String rop) {
        int left, right;
        try {
            left = Integer.parseInt(lop);
        } catch (NumberFormatException nfe) {
            return -1;
        }
        try {
            right = Integer.parseInt(rop);
        } catch (NumberFormatException nfe) {
            return 1;
        }
        return left - right;
    }

    private static final String VALID_SEPARATOR_CHARS = ".-_";
    private static final String VALID_VERSION_CHARS = "0123456789" + VALID_SEPARATOR_CHARS;

    private static final String sanitize(String versionString) {
        StringBuilder versionStringBuilder = new StringBuilder(versionString);

        for (int i = 0; i < versionStringBuilder.length(); i++) {
            if (VALID_VERSION_CHARS.indexOf(versionStringBuilder.charAt(i)) == -1) {
                versionStringBuilder.deleteCharAt(i);
                i--;
            }
        }

        return versionStringBuilder.toString();
    }

    public static void main(String[] args) {
        System.out.println("" + (new VersionComparator()).compare(args[0], args[1]));
    }
}

