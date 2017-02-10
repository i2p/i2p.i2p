package net.i2p.router.tasks;

import java.io.File;

import net.i2p.I2PAppContext;
import net.i2p.util.ShellCommand;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Only works with wrapper on non-windows platforms
 *
 *  @since 0.9.3 moved from RouterWatchdog
 */
abstract class ThreadDump {

    /**
     *  Signal the wrapper to asynchronously dump threads to wrapper.log.
     *  It waits for the signal to complete (which should be fast)
     *  but does not wait for the dump itself.
     *
     *  @param secondsToWait if &lt;= 0, don't wait
     *  @return success, false if windows or no wrapper, true if secondsToWait &lt;= 0,
                         false if timed out, dump result otherwise
     */
    public static boolean dump(I2PAppContext context, int secondsToWait) {
        if (SystemVersion.isWindows() || !context.hasWrapper())
            return false;
        ShellCommand sc = new ShellCommand();
        File i2pr = new File(context.getBaseDir(), "i2prouter");
        String[] args = new String[2];
        args[0] = i2pr.getAbsolutePath();
        args[1] = "dump";
        boolean success = sc.executeSilentAndWaitTimed(args, secondsToWait);
        if (secondsToWait <= 0)
            success = true;
        if (success) {
            Log log = context.logManager().getLog(ThreadDump.class);
            File f = new File(context.getConfigDir(), "wrapper.log");
            String loc = f.exists() ? f.getAbsolutePath() : "wrapper.log";
            log.log(Log.CRIT, "Threads dumped to " + loc);
        }
        return success;
    }
}
