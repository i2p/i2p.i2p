package net.i2p.installer;

import net.i2p.util.FileUtil;

/**
 * <p>This class is used by the installer to copy files.</p>
 * Usage: <code>Copy [FROM] [TO]</code><br>
 *
 * See also: {@link net.i2p.util.FileUtil#copy FileUtil.copy()}.
 * @since 0.4.1.4, moved to {@link net.i2p.installer} in 0.9.5
 */
public class Copy {
    public static void main(String args[]) {
        FileUtil.copy(args[0], args[1], true);
    }
}
