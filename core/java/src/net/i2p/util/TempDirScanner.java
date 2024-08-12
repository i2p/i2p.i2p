package net.i2p.util;

import java.io.File;

import net.i2p.I2PAppContext;

/**
 *  Prevent systemd from deleting our temp dir or any dirs or files in it.
 *  Scheduled by I2PAppContext when the temp dir is created.
 *
 *  To configure/test systemd: Edit file /usr/lib/tmpfiles.d/tmp.conf,
 *  change line
 *  D /tmp 1777 root root -
 *  to
 *  D /tmp 1777 root root 24h
 *
 *  Ref: https://lwn.net/Articles/975565/
 *  Ref: https://systemd.io/TEMPORARY_DIRECTORIES/
 *  Ref: man systemd-tmpfiles; man tmpfiles.d
 *
 *  @since 0.9.64
 */
public class TempDirScanner extends SimpleTimer2.TimedEvent {
    private final I2PAppContext ctx;

    // systemd default is 10 days for /tmp? distro dependent.
    // go a little faster than 1 day just in case
    private static final long DELAY = 23*60*60*1000L;

    /**
     *  Schedules itself
     */
    public TempDirScanner(I2PAppContext context) {
        super(context.simpleTimer2());
        ctx = context;
        schedule(DELAY);
    }

    public void timeReached() {
        scan(ctx.getTempDir());
        schedule(DELAY);
    }

    /**
     *  Recursively timestamp all files and empty dirs
     *  We can't count on the filesystem updating access time.
     *  This should not affect any known usage of our temp dir.
     */
    private static void scan(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                if (files.length > 0) {
                    for (File ff : files) {
                        scan(ff);
                    }
                } else {
                    // Update last mod time on empty directories
                    f.setLastModified(System.currentTimeMillis());
                }
            }
        } else if (f.isFile()) {
            f.setLastModified(System.currentTimeMillis());
        }
    }
}
