package net.i2p.util;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Compares versions.
 * Characters other than [0-9.-_] are ignored.
 * I2P only uses '.' but Sun Java uses '_' and plugins may use any of '.-_'
 * Moved from TrustedUpdate.java
 * @since 0.7.10
 */
public class VersionComparator implements Comparator<String>, Serializable {

    public int compare(String l, String r) {
        return comp(l, r);
    }
        
    /**
     *  To avoid churning comparators
     *  @since 0.9.7
     */
    public static int comp(String l, String r) {
        if (l.equals(r))
            return 0;
        
        final int ll = l.length();
        final int rl = r.length();
        int il = 0, ir = 0;
        int nl = 0, nr = 0;
        
        while(true) {
            
            // are we at end of strings?
            if (il >= ll) {
                if (ir >= rl)
                    return 0;
                return -1;
            } else if (ir >= rl)
                return 1;
            
            long lv = -1;
            while(lv == -1 && il < ll) {
                nl = nextSeparator(l, il);
                lv = parseLong(l,il,nl);
                il = nl + 1;
            }
            
            long rv = -1;
            while(rv == -1 && ir < rl) {
                nr = nextSeparator(r, ir);
                rv = parseLong(r,ir,nr);
                ir = nr + 1;
            }
            
            if (lv < rv)
                return -1;
            else if (lv > rv)
                return 1;
            
        }
    }
    
    private static boolean isSeparator(char c) {
        switch(c) {
        case '.':
        case '_':
        case '-':
            return true;
        default :
            return false;
        }
    }
    
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
    
    private static int getDigit(char c) {
        return c - '0';
    }
    
    /**
     * @param s string to process
     * @param start starting index in the string to process
     * @return the index of the next separator character, or end of string.
     */
    private static int nextSeparator(String s, int start) {
        while( start < s.length()) {
            if (isSeparator(s.charAt(start)))
                return start;
            start++;
        }
        return start;
    }
    
    /**
     * Parses a long, ignoring any non-digit characters.
     * @param s string to parse from
     * @param start index in the string to start
     * @param end index in the string to stop at
     * @return the parsed value, or -1 if nothing was parsed or there was a problem.
     */
    private static long parseLong(String s, int start, int end) {
        long rv = 0;
        boolean parsedAny = false;
        for (int i = start; i < end && rv >= 0; i++) {
            final char c = s.charAt(i);
            if (!isDigit(c))
                continue;
            parsedAny = true;
            rv = rv * 10 + getDigit(c);
        }
        if (!parsedAny)
            return -1;
        return rv;
    }
}
