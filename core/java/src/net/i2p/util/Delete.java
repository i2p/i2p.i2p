package net.i2p.util;

/**
 * Usage: Delete name
 *
 */
public class Delete {
    public static void main(String args[]) {
        FileUtil.rmdir(args[0], false);
    }
}
