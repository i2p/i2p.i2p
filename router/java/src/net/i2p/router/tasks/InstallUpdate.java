package net.i2p.router.tasks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.util.FileUtil;
import net.i2p.util.SystemVersion;

/**
 *  If the i2pupdate.zip file is present,
 *  unzip it and JVM exit.
 *
 *  @since 0.9.20 moved from Router.java
 */
public class InstallUpdate {

    private static final String DELETE_FILE = "deletelist.txt";
    
    /**
     * Context must be available.
     * Unzip update file found in the router dir OR base dir, to the base dir
     *
     * If successful, will call exit() and never return.
     *
     * If we can't write to the base dir, write message to System.out and return.
     * Note: _log not available here.
     */
    public static void installUpdates(Router r) {
        RouterContext context = r.getContext();
        File updateFile = new File(context.getRouterDir(), Router.UPDATE_FILE);
        boolean exists = updateFile.exists();
        if (!exists) {
            updateFile = new File(context.getBaseDir(), Router.UPDATE_FILE);
            exists = updateFile.exists();
        }
        if (exists) {
            // do a simple permissions test, if it fails leave the file in place and don't restart
            File test = new File(context.getBaseDir(), "history.txt");
            if ((test.exists() && !test.canWrite()) || (!context.getBaseDir().canWrite())) {
                System.out.println("ERROR: No write permissions on " + context.getBaseDir() +
                                   " to extract software update file");
                // carry on
                return;
            }
            System.out.println("INFO: Update file exists [" + Router.UPDATE_FILE + "] - installing");
            // verify the whole thing first
            // we could remember this fails, and not bother restarting, but who cares...
            boolean ok = FileUtil.verifyZip(updateFile);
            if (ok) {
                // This may be useful someday. First added in 0.8.2
                // Moved above the extract so we don't NCDFE
                Map<String, String> config = new HashMap<String, String>(4);
                config.put("router.updateLastInstalled", "" + System.currentTimeMillis());
                // Set the last version to the current version, since 0.8.13
                config.put("router.previousVersion", RouterVersion.VERSION);
                config.put("router.previousFullVersion", RouterVersion.FULL_VERSION);
                r.saveConfig(config, null);
                ok = FileUtil.extractZip(updateFile, context.getBaseDir());
            }

            // Very important - we have now trashed our jars.
            // After this point, do not use any new I2P classes, or they will fail to load
            // and we will die with NCDFE.
            // Ideally, do not use I2P classes at all, new or not.
            try {
                if (ok) {
                    // We do this here so we may delete old jars before we restart
                    deleteListedFiles(context);
                    System.out.println("INFO: Update installed");
                } else {
                    System.out.println("ERROR: Update failed!");
                }
                if (!ok) {
                    // we can't leave the file in place or we'll continually restart, so rename it
                    File bad = new File(context.getRouterDir(), "BAD-" + Router.UPDATE_FILE);
                    boolean renamed = updateFile.renameTo(bad);
                    if (renamed) {
                        System.out.println("Moved update file to " + bad.getAbsolutePath());
                    } else {
                        System.out.println("Deleting file " + updateFile.getAbsolutePath());
                        ok = true;  // so it will be deleted
                    }
                }
                if (ok) {
                    boolean deleted = updateFile.delete();
                    if (!deleted) {
                        System.out.println("ERROR: Unable to delete the update file!");
                        updateFile.deleteOnExit();
                    }
                }
                // exit whether ok or not
                if (context.hasWrapper())
                    System.out.println("INFO: Restarting after update");
                else
                    System.out.println("WARNING: Exiting after update, restart I2P");
            } catch (Throwable t) {
                // hide the NCDFE
                // hopefully the update file got deleted or we will loop
            }
            System.exit(Router.EXIT_HARD_RESTART);
        } else {
            deleteJbigiFiles(context);
            // It was here starting in 0.8.12 so it could be used the very first time
            // Now moved up so it is usually run only after an update
            // But the first time before jetty 6 it will run here...
            // Here we can't remove jars
            deleteListedFiles(context);
        }
    }

    /**
     *  Remove extracted libjbigi.so and libjcpuid.so files if we have a newer jbigi.jar,
     *  so the new ones will be extracted.
     *  We do this after the restart, not after the extract, because it's safer, and
     *  because people may upgrade their jbigi.jar file manually.
     *
     *  Copied from NativeBigInteger, which we can't access here or the
     *  libs will get loaded.
     */
    private static void deleteJbigiFiles(RouterContext context) {
        boolean isX86 = SystemVersion.isX86();
        String osName = System.getProperty("os.name").toLowerCase(Locale.US);
        boolean isWin = SystemVersion.isWindows();
        boolean isMac = SystemVersion.isMac();
        // only do this on these OSes
        boolean goodOS = isWin || isMac ||
                         osName.contains("linux") || osName.contains("freebsd");

        File jbigiJar = new File(context.getBaseDir(), "lib/jbigi.jar");
        if (goodOS && jbigiJar.exists()) {
            String libPrefix = (isWin ? "" : "lib");
            String libSuffix = (isWin ? ".dll" : isMac ? ".jnilib" : ".so");

            if (isX86) {
                File jcpuidLib = new File(context.getBaseDir(), libPrefix + "jcpuid" + libSuffix);
                if (jcpuidLib.canWrite() && jbigiJar.lastModified() > jcpuidLib.lastModified()) {
                    String path = jcpuidLib.getAbsolutePath();
                    boolean success = FileUtil.copy(path, path + ".bak", true, true);
                    if (success) {
                        boolean success2 = jcpuidLib.delete();
                        if (success2) {
                            System.out.println("New jbigi.jar detected, moved jcpuid library to " +
                                               path + ".bak");
                            System.out.println("Check logs for successful installation of new library");
                        }
                    }
                }
            }

            if (isX86 || SystemVersion.isARM()) {
                File jbigiLib = new File(context.getBaseDir(), libPrefix + "jbigi" + libSuffix);
                if (jbigiLib.canWrite() && jbigiJar.lastModified() > jbigiLib.lastModified()) {
                    String path = jbigiLib.getAbsolutePath();
                    boolean success = FileUtil.copy(path, path + ".bak", true, true);
                    if (success) {
                        boolean success2 = jbigiLib.delete();
                        if (success2) {
                            System.out.println("New jbigi.jar detected, moved jbigi library to " +
                                               path + ".bak");
                            System.out.println("Check logs for successful installation of new library");
                        }
                    }
                }
            }
        }
    }
    
    /**
     *  Delete all files listed in the delete file.
     *  Format: One file name per line, comment lines start with '#'.
     *  All file names must be relative to $I2P, absolute file names not allowed.
     *  We probably can't remove old jars this way.
     *  Fails silently.
     *  Use no new I2P classes here so it may be called after zip extraction.
     *  @since 0.8.12
     */
    private static void deleteListedFiles(RouterContext context) {
        File deleteFile = new File(context.getBaseDir(), DELETE_FILE);
        if (!deleteFile.exists())
            return;
        // this is similar to FileUtil.readTextFile() but we can't use any I2P classes here
        FileInputStream fis = null;
        BufferedReader in = null;
        try {
            fis = new FileInputStream(deleteFile);
            in = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            String line;
            while ( (line = in.readLine()) != null) {
                String fl = line.trim();
                if (fl.contains("..") || fl.startsWith("#") || fl.length() == 0)
                    continue;
                File df = new File(fl);
                if (df.isAbsolute())
                    continue;
                df = new File(context.getBaseDir(), fl);
                if (df.exists() && df.isFile()) {
                    if (df.delete())
                        System.out.println("INFO: File [" + fl + "] deleted");
                }
            }
        } catch (IOException ioe) {
        } finally {
            if (in != null) try { in.close(); } catch(IOException ioe) {}
            if (deleteFile.delete()) {
                //System.out.println("INFO: File [" + DELETE_FILE + "] deleted");
            }
        }
    }
}

