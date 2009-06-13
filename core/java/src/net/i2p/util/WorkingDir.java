package net.i2p.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Properties;

import net.i2p.data.DataHelper;

/**
 * Get a working directory for i2p.
 *
 * For the location, first try the system property i2p.dir.config
 * Next try $HOME/.i2p on linux or %APPDATA%\I2P on Windows.
 *
 * If the dir exists, return it.
 * Otherwise, attempt to create it, and copy files from the base directory.
 * To successfully copy, the base install dir must be the system property i2p.dir.base
 * or else must be in $CWD.
 *
 * If I2P was run from the install directory in the past,
 * and migrateOldData = true, copy the
 * necessary data files (except i2psnark/) over to the new working directory.
 *
 * Otherwise, just copy over a limited number of files over.
 *
 * Do not ever copy or move the old i2psnark/ directory, as if the
 * old and new locations are on different file systems, this could
 * be quite slow.
 *
 * Modify some files while copying, see methods below.
 *
 * After migration, the router will run using the new directory.
 * The wrapper, however, must be stopped and restarted from the new script - until then,
 * it will continue to write to wrapper.log* in the old directory.
 *
 * @param whether to copy all data over from an existing install
 */
public class WorkingDir {

    private final static String PROP_BASE_DIR = "i2p.dir.base";
    private final static String PROP_WORKING_DIR = "i2p.dir.config";
    private final static String WORKING_DIR_DEFAULT_WINDOWS = "I2P";
    private final static String WORKING_DIR_DEFAULT = ".i2p";

    /**
     * Only call this once on router invocation.
     * Caller should store the return value for future reference.
     */
    public static String getWorkingDir(boolean migrateOldData) {
        String dir = System.getProperty(PROP_WORKING_DIR);
        boolean isWindows = System.getProperty("os.name").startsWith("Win");
        File dirf = null;
        if (dir != null) {
            dirf = new File(dir);
        } else {
            String home = System.getProperty("user.home");
            if (isWindows) {
                String appdata = System.getenv("APPDATA");
                if (appdata != null)
                    home = appdata;
                dirf = new File(home, WORKING_DIR_DEFAULT_WINDOWS);
            } else {
                dirf = new File(home, WORKING_DIR_DEFAULT);
            }
        }
        // where we are now
        String cwd = System.getProperty(PROP_BASE_DIR);
        if (cwd == null)
            cwd = System.getProperty("user.dir");
        // where we want to go
        String rv = dirf.getAbsolutePath();
        if (dirf.exists()) {
            if (dirf.isDirectory())
                return rv; // all is good, we found the user directory
            System.err.println("Wanted to use " + rv + " for a working directory but it is not a directory");
            return cwd;
        }
        if (!dirf.mkdir()) {
            System.err.println("Wanted to use " + rv + " for a working directory but could not create it");
            return cwd;
        }

        // Check for a hosts.txt file, if it exists then I2P is there
        File oldDirf = new File(cwd);
        File test = new File(oldDirf, "hosts.txt");
        if (!test.exists()) {
            System.err.println("ERROR - Cannot find I2P installation in " + cwd);
            return cwd;
        }

        // Check for a router.keys file, if it exists it's an old install,
        // and only migrate the data files if told to do so
        test = new File(oldDirf, "router.keys");
        boolean oldInstall = test.exists();
        migrateOldData &= oldInstall;

        // Do the copying
        if (migrateOldData)
            System.err.println("Migrating data files to new user directory " + rv);
        else
            System.err.println("Setting up new user directory " + rv);
        boolean success = migrate(MIGRATE_BASE, oldDirf, dirf);
        // this one must be after MIGRATE_BASE
        success &= migrateJettyXml(oldDirf, dirf);
        success &= migrateWrapperConfig(oldDirf, dirf);
        if (migrateOldData) {
            success &= migrate(MIGRATE_DATA, oldDirf, dirf);
            success &= migrateI2PTunnelKeys(oldDirf, dirf);
            success &= migrateSnark(oldDirf, dirf);
            // new installs will have updated scripts left in the install dir
            // don't bother migrating runplain.sh or i2prouter.bat
            if (!isWindows)
                success &= migrateI2prouter(oldDirf, dirf);
        } else if (!oldInstall) {
            // copy the default i2psnark.config over
            success &= migrate("i2psnark.config", oldDirf, dirf);
        }

        // Report success or failure
        if (success) {
            System.err.println("Successfully copied data files to new user directory " + rv);
            if (migrateOldData) {
                System.err.println("Libraries and other files remain in the old directory " + cwd + ", do not remove them.");
                System.err.println("You should manually move any non-standard files, such as additional eepsite directories and key files");
                System.err.println("After verifying that all is working, you may delete the following data files and directories in " +
                           cwd + ": " + MIGRATE_DATA.replace(',', ' ') + " i2psnark.config tmp work");
                if (System.getProperty("wrapper.version") != null)
                    System.err.println("Note that until you shutdown your router completely and restart, the wrapper will continue" +
                               " to log to the old wrapper logs in " + cwd);
                if (!isWindows)
                    System.err.println("From now on, you should now use the i2prouter" +
                               " script in the " + rv + " directory to start i2p." +
                               " You may copy or move this script elsewhere, you need not run it from that directory.");
            }
            return rv;
        } else {
            System.err.println("FAILED copy of some or all data files to new directory " + rv);
            System.err.println("Check logs for details");
            System.err.println("Continung to use data files in old directory " + cwd);
            return cwd;
        }
    }

    /**
     * files and directories from the base install to copy over
     * None of these should be included in i2pupdate.zip
     *
     * The user should not delete these in the old location, leave them as templates for new users
     */
    private static final String MIGRATE_BASE =
        // base install - dirs
        // We don't currently have a default addressbook/ in the base distribution,
        // but distros might put one in
        "addressbook,eepsite," +
        // base install - files
        // We don't currently have a default router.config or logger.config in the base distribution,
        // but distros might put one in
        "blocklist.txt,clients.config,hosts.txt,i2ptunnel.config,jetty-i2psnark.xml," +
        "logger.config,router.config,systray.config";

    /**
     * files and directories from an old single-directory installation to copy over  - NOT including snark
     * None of these should be included in i2pupdate.zip
     *
     * The user can be advised to delete these from the old location
     */
    private static final String MIGRATE_DATA =
        // post install - dirs
        // not required to copy - tmp/, work/
        // addressbook included in MIGRATE_BASE above
        "keyBackup,logs,netDb,peerProfiles," +
        // post install - files
        // not required to copy - prngseed.rnd
        // logger.config and router.config included in MIGRATE_BASE above
        "bob.config,privatehosts.txt,router.info,router.keys," +
        "sam.keys,susimail.config,userhosts.txt,webapps.config," +
        "wrapper.log,wrapper.log.1,wrapper.log.2";

    private static boolean migrate(String list, File olddir, File todir) {
        boolean rv = true;
        String files[] = list.split(",");
        for (int i = 0; i < files.length; i++) {
            File from = new File(olddir, files[i]);
            if (!copy(from, todir)) {
                System.err.println("Error copying " + from.getAbsolutePath());
                rv = false;
            }
        }
        return rv;
    }

    /**
     *  Copy over the i2psnark.config file with modifications
     */
    private static boolean migrateSnark(File olddir, File todir) {
        boolean rv = true;
        File oldSnark = new File(olddir, "i2psnark");
        File newSnark = new File(todir, "i2psnark");
        File oldSnarkConfig = new File(olddir, "i2psnark.config");
        File newSnarkConfig = new File(todir, "i2psnark.config");
        boolean hasData = oldSnark.exists();
        if (hasData) {
            File children[] = oldSnark.listFiles();
            hasData = children != null && children.length > 0;
        }
        if (oldSnarkConfig.exists()) {
            if (hasData) {
                // edit the snark config file to point to the old location, we aren't moving the data
                try {
                    Properties props = new Properties();
                    DataHelper.loadProps(props, oldSnarkConfig);
                    String dir = props.getProperty("i2psnark.dir");
                    if (dir == null)
                        dir = "i2psnark";
                    // change relative to absolute path
                    File f = new File(dir);
                    props.setProperty("i2psnark.dir", f.getAbsolutePath());
                    DataHelper.storeProps(props, newSnarkConfig);
                    System.err.println("Copied i2psnark.config with modifications");
                } catch (IOException ioe) {
                    System.err.println("FAILED copy i2psnark.config");
                    rv = false;
                }
            } else {
                // copy the i2psnark config file over
                copy(newSnarkConfig, todir);
                System.err.println("Copied i2psnark.config");
            }
        } else {
            if (hasData) {
                // data but no previous config file (unlikely) - make new config file
                try {
                    Properties props = new Properties();
                    File f = new File("i2psnark");
                    props.setProperty("i2psnark.dir", f.getAbsolutePath());
                    DataHelper.storeProps(props, newSnarkConfig);
                } catch (IOException ioe) {
                    // ignore
                }
            } // else no config and no data
        }
        if (hasData) {
          /*************
            // crude attempt to detect same filesystem
            if ((oldSnark.getAbsolutePath().startsWith("/home/") && newSnark.getAbsolutePath().startsWith("/home/")) ||
                (System.getProperty("os.name").toLowerCase.indexOf("windows") >= 0 &&
                 oldSnark.getAbsolutePath().substring(0,1).equals(newSnark.getAbsolutePath().substring(0,1) &&
                 oldSnark.getAbsolutePath().substring(1,2).equals(":\\") &&
                 newSnark.getAbsolutePath().substring(1,2).equals(":\\"))) {
                 // OK, apparently in same file system
                 // move everything
            }
          **************/
            System.err.println("NOT moving the i2psnark data directory " + oldSnark.getAbsolutePath() +
                   " to the new directory " + newSnark.getAbsolutePath() +
                   ". You may move the directory contents manually WHILE I2P IS NOT RUNNING," +
                   " and edit the file " + newSnarkConfig.getAbsolutePath() +
                   " to configure i2psnark to use a different location by editing the i2psnark.dir configuration to be" +
                   " i2psnark.dir=" + oldSnark.getAbsolutePath() +
                   " and restart, or you may leave the i2psnark directory in its old location.");
        }
        return true;
    }

    /**
     *  Copy over the i2prouter file with modifications
     *  The resulting script can be run from any location.
     */
    private static boolean migrateI2prouter(File olddir, File todir) {
        File oldFile = new File(olddir, "i2prouter");
        File newFile = new File(todir, "i2prouter");
        FileInputStream in = null;
        PrintWriter out = null;
        try {
            in = new FileInputStream(oldFile);
            out = new PrintWriter(new BufferedWriter(new FileWriter(newFile)));
            boolean firstTime = true;
            String s = null;
            while ((s = DataHelper.readLine(in)) != null) {
                if (s.equals("WRAPPER_CMD=\"./i2psvc\"")) {
                    // i2psvc in the old location
                    File f = new File("i2psvc");
                    s = "WRAPPER_CMD=\"" + f.getAbsolutePath() + "\"";
                } else if(s.equals("WRAPPER_CONF=\"wrapper.config\"")) {
                    // wrapper.config the new location
                    File f = new File(todir, "wrapper.config");
                    s = "WRAPPER_CONF=\"" + f.getAbsolutePath() + "\"";
                } else if(s.equals("PIDDIR=\".\"")) {
                    // i2p.pid in the new location
                    s = "PIDDIR=\"" + todir.getAbsolutePath() + "\"";
                }
                out.println(s);
                if (firstTime) {
                    // first line was #!/bin/sh, so had to wait until now
                    out.println("# Modified by I2P User dir migration script");
                    firstTime = false;
                }            
            }            
            System.err.println("Copied i2prouter with modifications");
            return true;
        } catch (IOException ioe) {
            if (in != null) {
                System.err.println("FAILED copy i2prouter");
                return false;
            }
            return true;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) out.close();
        }
    }

    /**
     *  Copy over the wrapper.config file with modifications
     */
    private static boolean migrateWrapperConfig(File olddir, File todir) {
        File oldFile = new File(olddir, "wrapper.config");
        File newFile = new File(todir, "wrapper.config");
        FileInputStream in = null;
        PrintWriter out = null;
        try {
            in = new FileInputStream(oldFile);
            out = new PrintWriter(new BufferedWriter(new FileWriter(newFile)));
            out.println("# Modified by I2P User dir migration script");
            String s = null;
            // Don't use replaceFirst because backslashes in the replacement string leads to havoc
            // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4689750
            // "Note that backslashes and dollar signs in the replacement string may cause the results
            // to be different than if it were being treated as a literal replacement string.
            // Dollar signs may be treated as references to captured subsequences as described above,
            // and backslashes are used to escape literal characters in the replacement string."
            while ((s = DataHelper.readLine(in)) != null) {
                if (s.startsWith("wrapper.java.classpath.")) {
                    // libraries in the old location
                    s = s.replace("=lib/", '=' + olddir.getAbsolutePath() + File.separatorChar + "lib" + File.separatorChar);
                } else if (s.startsWith("wrapper.java.library.path.")) {
                    // libraries in the old location
                    if (s.contains("=."))
                        s = s.replace("=.", '=' + olddir.getAbsolutePath());
                    else if (s.contains("=lib"))
                        s = s.replace("=lib", '=' + olddir.getAbsolutePath() + File.separatorChar + "lib");
                } else if (s.startsWith("wrapper.logfile=wrapper.log")) {
                    // wrapper logs in the new location
                    s = s.replace("=", '=' + todir.getAbsolutePath() + File.separatorChar);
                } else if (s.startsWith("wrapper.pidfile=i2p.pid")) {
                    // i2p.pid in the new location
                    s = s.replace("=", '=' + todir.getAbsolutePath() + File.separatorChar);
                }
                out.println(s);
            }
            System.err.println("Copied wrapper.config with modifications");
            return true;
        } catch (IOException ioe) {
            if (in != null) {
                System.err.println("FAILED copy wrapper.config");
                return false;
            }
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) out.close();
        }
    }

    /**
     *  Copy over the jetty.xml file with modifications
     *  It was already copied over once in migrate(), throw that out and
     *  do it again with modifications.
     */
    private static boolean migrateJettyXml(File olddir, File todir) {
        File eepsite1 = new File(olddir, "eepsite");
        File oldFile = new File(eepsite1, "jetty.xml");
        File eepsite2 = new File(todir, "eepsite");
        File newFile = new File(eepsite2, "jetty.xml");
        FileInputStream in = null;
        PrintWriter out = null;
        try {
            in = new FileInputStream(oldFile);
            out = new PrintWriter(new BufferedWriter(new FileWriter(newFile)));
            String s = null;
            while ((s = DataHelper.readLine(in)) != null) {
                if (s.indexOf("./eepsite/") >= 0) {
                    s = s.replace("./eepsite/", todir.getAbsolutePath() + File.separatorChar + "eepsite" + File.separatorChar);
                }
                out.println(s);
            }
            out.println("<!-- Modified by I2P User dir migration script -->");
            System.err.println("Copied jetty.xml with modifications");
            return true;
        } catch (IOException ioe) {
            if (in != null) {
                System.err.println("FAILED copy jetty.xml");
                return false;
            }
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) out.close();
        }
    }

    /**
     * Relatively recent default i2ptunnel key file name
     */
    private static boolean migrateI2PTunnelKeys(File olddir, File todir) {
        for (int i = 0; i < 100; i++) {
            copy(new File(olddir, "i2ptunnel" + i + "-privKeys.dat"), todir);
        }
        return true;
    }

    /**
     * Recursive copy a file or dir to a dir
     * 
     * @param src file or directory, need not exist
     * @param target the directory to copy to, will be created if it doesn't exist
     * @return true for success OR if src does not exist
     */
    public static final boolean copy(File src, File targetDir) {
        if (!src.exists())
            return true;
        if (!targetDir.exists()) {
            if (!targetDir.mkdir()) {
                System.err.println("FAILED copy " + src.getPath());
                return false;
            }
        }
        File targetFile = new File(targetDir, src.getName());
        if (!src.isDirectory())
            return copyFile(src, targetFile);
        File children[] = src.listFiles();
        if (children == null) {
            System.err.println("FAILED copy " + src.getPath());
            return false;
        }
        boolean rv = true;
        for (int i = 0; i < children.length; i++) {
            rv &= copy(children[i], targetFile);
        }
        return rv;
    }
    
    /**
     * @param src not a directory, must exist
     * @param dest not a directory, will be overwritten if existing
     * @@reurn true if it was copied successfully
     */
    public static boolean copyFile(File src, File dst) {
        if (!src.exists()) return false;
        boolean rv = true;

        byte buf[] = new byte[4096];
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            
            int read = 0;
            while ( (read = in.read(buf)) != -1)
                out.write(buf, 0, read);
            
            System.err.println("Copied " + src.getPath());
        } catch (IOException ioe) {
            System.err.println("FAILED copy " + src.getPath());
            rv = false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
        if (rv)
            dst.setLastModified(src.lastModified());
        return rv;
    }
}
