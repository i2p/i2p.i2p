package net.i2p.installer;

import net.i2p.util.FileUtil;

/**
 * Usage: Copy from to
 *
 * only for use by installer
 */
public class Copy {
    public static void main(String args[]) {
        FileUtil.copy(args[0], args[1], true);
    }
}
