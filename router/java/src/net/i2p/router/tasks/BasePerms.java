package net.i2p.router.tasks;

import java.io.File;

import net.i2p.router.RouterContext;
import net.i2p.util.ShellCommand;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 *
 *  @since 0.9.46
 */
public class BasePerms {

    private static final String FIXED_VER = "0.9.46";
    private static final String PROP_FIXED = "router.fixedBasePerms";

    /**
     *
     */
    public static void fix(RouterContext ctx) {
        if (!SystemVersion.isWindows())
            return;
        if (ctx.getBooleanProperty(PROP_FIXED))
            return;
        if (!ctx.router().getKillVMOnEnd())  // embedded
            return;
        File dir = ctx.getBaseDir();
        File f = new File(dir, "history.txt");
        if (f.exists() && !f.canWrite())     // no permissions, nothing we can do
            return;

        // broad permissions set starting in 0.7.5,
        // but that's before we had the firstVersion property,
        // so no use checking for earlier than that
        String first = ctx.getProperty("router.firstVersion");
        if (first == null || VersionComparator.comp(first, FIXED_VER) < 0) {
            File f1 = new File(dir, "Uninstaller");  // izpack install
            File f2 = new File(dir, "fixperms.log"); // fixperms.bat was run
            if (f1.exists() && f2.exists()) {
                File f3 = new File(dir, "fixperms.bat");
                f3.delete();  // don't need it
                try {
                    fix(dir);
                } catch (Exception e) {
                }
            }
        }
        ctx.router().saveConfig(PROP_FIXED, "true");
    }

    /**
     *  Run the bat file
     */
    private static void fix(File f) {
        File bat = new File(f, "scripts");
        bat = new File(bat, "fixperms2.bat");
        String[] args = { bat.getAbsolutePath(), f.getAbsolutePath() };
        // don't wait, takes appx. 6 seconds on Windows 8 netbook
        (new ShellCommand()).executeSilentAndWaitTimed(args, 0);
    }
}
    
