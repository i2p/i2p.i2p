package net.i2p.util;

/**
 * Usage: Copy from to
 *
 * @deprecated only for use by installer, to be removed from i2p.jar, use FileUtil.copy()
 */
public class Copy {
    public static void main(String args[]) {
        FileUtil.copy(args[0], args[1], true);
    }
}
