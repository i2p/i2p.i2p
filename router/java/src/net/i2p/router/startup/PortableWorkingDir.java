package net.i2p.router.startup;

import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

import java.io.*;
import java.util.Properties;

/**
 * Get a relative working directory for i2p based upon properties by parent process.
 *
 * This is used together with the Browser Bundle.
 *
 * This class is slim/light because the initial config is deployed by
 * I2PCtrl.js if the config directory is missing from the relative directory it's using,
 * and if any migrations is needed, I2PCtrl.js will do that part.
 *
 * In the future BB might have flavors or plugins whichs would be apps normally distributed
 * with default install of i2p, and other plugins available. I2PCtrl.js will manage these
 * since it's closer to the end-user via browser options and GUI.
 *
 *
 * Just FYI:
 * In the context of a BB:
 *
 * On OSX it has to look 3 paths up, but for the other two, only one.
 *
 * OSX Path: I2PBrowser.app/Contents/MacOS/firefox
 *
 * OSX =&gt; ../../../I2PBrowser-Data/I2P
 * Linux/Windows =&gt; ../I2PBrowser-Data/I2P
 *
 * ---- However,
 *
 * The javascript plugin I2PCtrl.js is setting correct working and base directory so this class can be quite dumb.
 *
 * Working directory is where we have most of our config, and keys.
 *
 * Base directory is where we usually find the wrapper config (on normal installs) together with jars and
 * static files in an I2P installation.
 *
 * Portable uses both, with the key usage being that I2P is extracted from (whatever) to a base directory,
 * but still has it's config separate in the working directory.
 *
 *
 * @author (who to blame) Meeh
 * @version 0.2
 * @since 0.9.35
 */
public class PortableWorkingDir {

    private final static String PROP_BASE_DIR = "i2p.dir.base";
    private final static String PROP_WORKING_DIR = "i2p.dir.config";
    /** we do a couple of things differently if this is the username */
    private static final String PROP_WRAPPER_LOG = "wrapper.logfile";
    private static final String DEFAULT_WRAPPER_LOG = "wrapper.log";

    /**
     * Only call this once on router invocation.
     * Caller should store the return value for future reference.
     *
     * @param envProps environment properties
     */
    public static String getWorkingDir(Properties envProps) {
        String dir = null;
        if (envProps != null)
            dir = envProps.getProperty(PROP_WORKING_DIR);
        if (dir == null)
            dir = System.getProperty(PROP_WORKING_DIR);

        // where we are now
        String cwd = null;
        if (envProps != null)
            cwd = envProps.getProperty(PROP_BASE_DIR);
        if (cwd == null) {
            cwd = System.getProperty(PROP_BASE_DIR);
            if (cwd == null)
                cwd = System.getProperty("user.dir");
        }

        File dirf = new SecureDirectory(dir);

        // Check for a hosts.txt file, if it exists then I2P is there
        File oldDirf = new File(cwd);
        File test = new File(oldDirf, "hosts.txt");
        if (!test.exists()) {
            setupSystemOut(cwd);
            System.err.println("ERROR - Cannot find I2P installation in " + cwd +
                  " - Will probably be just a router with no apps or console at all!");
            // we are probably doomed...
            return cwd;
        }

        // Check for a router.keys file or logs dir, if either exists it's an old install,
        // and only migrate the data files if told to do so
        // (router.keys could be deleted later by a killkeys())

        if (!dirf.exists() && !dirf.mkdir()) {
            setupSystemOut(null);
            System.err.println("Wanted to use " + dirf.toString() + " for a working directory but could not create it");
            return cwd;
        }

        setupSystemOut(dirf.getAbsolutePath());

        return dirf.getAbsolutePath();
    }

    /**
     *  Redirect stdout and stderr to a wrapper.log file if there is no wrapper,
     *  unless system property I2P_DISABLE_OUTPUT_OVERRIDE is set.
     *
     *  If there is no -Dwrapper.log=/path/to/wrapper.log on the java command line
     *  to specify a log file, check for existence of wrapper.log in CWD,
     *  for backward compatibility in old installations (don't move it).
     *  Otherwise, use (system temp dir)/wrapper.log.
     *  Create if it doesn't exist, and append to it if it does.
     *  Put the location in the environment as an absolute path, so logs.jsp can find it.
     *
     *  @param dir if null, use Java temp dir; System property wrapper.logfile overrides
     *  @since 0.8.13
     */
    private static void setupSystemOut(String dir) {
        if (SystemVersion.hasWrapper())
            return;
        if (System.getProperty("I2P_DISABLE_OUTPUT_OVERRIDE") != null)
            return;
        String path = System.getProperty(PROP_WRAPPER_LOG);
        File logfile;
        if (path != null) {
            logfile = new File(path);
        } else {
            logfile = new File(DEFAULT_WRAPPER_LOG);
            if (!logfile.exists()) {
                if (dir == null)
                    dir = System.getProperty("java.io.tmpdir");
                logfile = new File(dir, DEFAULT_WRAPPER_LOG);
            }
        }
        System.setProperty(PROP_WRAPPER_LOG, logfile.getAbsolutePath());
        try {
            PrintStream ps = new PrintStream(new SecureFileOutputStream(logfile, true), true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    
}
