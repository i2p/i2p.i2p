package net.i2p.util;

/**
 * Usage: Copy from to
 *
 */
public class Copy {
    public static void main(String args[]) {
        FileUtil.copy(args[0], args[1], true);
    }
}
