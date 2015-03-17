package net.i2p.util;

/**
 * Usage: Delete name
 *
 * @deprecated only for use by installer, to be removed from i2p.jar, use FileUtil.rmdir()
 */
public class Delete {
    public static void main(String args[]) {
        FileUtil.rmdir(args[0], false);
    }
}
