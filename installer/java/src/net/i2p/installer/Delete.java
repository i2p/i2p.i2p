package net.i2p.installer;

import net.i2p.util.FileUtil;

/**
 * Usage: Delete name
 *
 * only for use by installer
 */
public class Delete {
    public static void main(String args[]) {
        for(int file=0; file < args.length; file++)
            FileUtil.rmdir(args[file], false);
    }
}
