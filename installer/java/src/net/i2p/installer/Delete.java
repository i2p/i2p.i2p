package net.i2p.installer;

import net.i2p.util.FileUtil;

/**
 * <p>This class is used by the installer to delete one or more specified files.</p>
 * Usage: <code>Delete <u>FILE</u> ...</code><br>
 *
 * See also: {@link net.i2p.util.FileUtil#rmdir FileUtil.rmdir()}.
 * @since 0.4.1.4, moved to {@link net.i2p.installer} in 0.9.5
 */

public class Delete {
    public static void main(String args[]) {
        for(int file=0; file < args.length; file++)
            FileUtil.rmdir(args[file], false);
    }
}
