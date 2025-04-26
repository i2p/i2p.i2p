package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

import java.io.File;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.VersionComparator;

/**
 *  Second migration, as of 2.9.0:
 *<p>
 *  Migrate all the jetty*.xml files to change
 *  Ref id= to Ref refid= because dup ids is a fatal error.
 *  Also migrate the old configure.dtd to configure_9_3.dtd.
 *  Reference: https://github.com/jetty/jetty.project/issues/12881
 *</p>
 *
 *  First migration, as of 0.9.30:
 *<p>
 *  Migrate the clients.config and jetty.xml files
 *  from Jetty 5/6 to Jetty 7/8.
 *  Also migrate jetty.xml from Jetty 7/8 to Jetty 9.
 *
 *  For each client for class org.mortbay.jetty.Server:
 *<pre>
 *  Let $D be the dir that jetty.xml is in (usually ~/.i2p/eepsite)
 *  Saves $D/jetty.xml to $D/jetty6.xml
 *  Copies $I2P/eepsite-jetty7/jetty.xml to $D/jetty.xml, edited for $D
 *  Copies $I2P/eepsite-jetty7/jetty-ssl.xml to $D/jetty-ssl.xml, edited for $D
 *  Copies $I2P/eepsite-jetty7/jetty-rewrite.xml to $D/jetty-rewrite.xml
 *  Copies $I2P/eepsite-jetty7/context/base-context.xml to $D/jetty.xml, edited for $D
 *  Copies $I2P/eepsite-jetty7/context/cgi-context.xml to $D/jetty.xml, edited for $D
 *  Copies $I2P/eepsite-jetty7/etc/* to $D/etc
 *  Changes main class in clients.config
 *</pre>
 *  Copies clients.config to clients.config.jetty6;
 *  Saves new clients.config.
 *
 *  Does NOT preserve port number, thread counts, etc. in the migration to 7/8.
 *  DOES preserve everything in the migration to 9.
 *</p>
 *
 *  @since Jetty 6
 */
abstract class MigrateJetty {
    private static boolean _wasChecked;
    private static boolean _hasLatestJetty;

    private static final String OLD_CLASS = "org.mortbay.jetty.Server";
    private static final String OLD_CLASS_6 = "org.mortbay.start.Main";
    private static final String NEW_CLASS = "net.i2p.jetty.JettyStart";
    private static final String TEST_CLASS = "org.eclipse.jetty.server.Server";
    private static final String BACKUP_SUFFIX = ".jetty6";
    private static final String BACKUP_SUFFIX_8 = ".jetty8";
    private static final String BACKUP_SUFFIX_9 = ".jetty9-id";
    private static final String JETTY_TEMPLATE_DIR = "eepsite-jetty9";
    private static final String JETTY_TEMPLATE_PKGDIR = "eepsite";
    private static final String BASE_CONTEXT = "contexts/base-context.xml";
    private static final String CGI_CONTEXT = "contexts/cgi-context.xml";
    private static final String PROP_JETTY9_MIGRATED = "router.startup.jetty9.migrated";
    private static final String PROP_JETTY9_MIGRATED_2 = "router.startup.jetty-ids.migrated";
    
    /**
     *  For each entry in apps, if the main class is an old Jetty class,
     *  migrate it to the new Jetty class, and update the Jetty config files.
     */
    public static void migrate(RouterContext ctx, List<ClientAppConfig> apps) {
        if (ctx.getBooleanProperty(PROP_JETTY9_MIGRATED_2))
            return;
        String installed = ctx.getProperty("router.firstVersion");
        if (installed != null && VersionComparator.comp(installed, "2.9.0") >= 0) {
            ctx.router().saveConfig(PROP_JETTY9_MIGRATED_2, "true");
            return;
        }
        boolean migrated1 = ctx.getBooleanProperty(PROP_JETTY9_MIGRATED);
        if (!migrated1 && installed != null && VersionComparator.comp(installed, "0.9.30") >= 0) {
            ctx.router().saveConfig(PROP_JETTY9_MIGRATED, "true");
            migrated1 = true;
        }
        boolean migration2success = false;
        for (int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = apps.get(i);
            String client;
            String backupSuffix;
            if (migrated1) {
                if (app.className.equals(NEW_CLASS)) {
                    client = "client application " + i + " [" + app.clientName +
                             "] to fix DTDs and duplicate ids";
                    backupSuffix = BACKUP_SUFFIX_9;
                } else {
                    continue;
                }
            } else {
                if (app.className.equals(NEW_CLASS)) {
                    client = "client application " + i + " [" + app.clientName +
                             "] from Jetty 7/8 to Jetty 9";
                    backupSuffix = BACKUP_SUFFIX_8;
                } else if (app.className.equals(OLD_CLASS) || app.className.equals(OLD_CLASS_6)) {
                    client = "client application " + i + " [" + app.clientName +
                             "] from Jetty 5/6 " + app.className +
                             " to Jetty 9 " + NEW_CLASS;
                    backupSuffix = BACKUP_SUFFIX;
                } else {
                    continue;
                }
            }
            if (!hasLatestJetty()) {
                System.err.println("WARNING: Jetty 7 unavailable, cannot migrate " + client);
                continue;
            }
            if (app.args == null)
                continue;
            // remove quotes
            String args[] = LoadClientAppsJob.parseArgs(app.args);
            if (args.length == 0)
                continue;

            if (!migrated1) {
                // migration from 0.9.29 or earlier (2017-02-27) straight to 2.9.0 or later
                System.err.println("WARNING: Unable to migrate " + client +
                                   ", delete client or uninstall and reinstall I2P");
                app.disabled = true;
                continue;

            }

            System.err.println("Migrating " + client);

            // migration 2 below here

            // Note that JettyStart automatically copies and adds jetty-gzip.xml
            // to the command line, not in the arg list here,
            // but it does not contain anything we need to fix.
            for (String xml : args) {
                if (!xml.endsWith(".xml"))
                    continue;
                File xmlFile = new File(xml);
                if (!xmlFile.isAbsolute())
                    xmlFile = new File(ctx.getAppDir(), xml);
                if (!xmlFile.exists()) {
                    System.err.println("WARNING: XML file " + xmlFile +
                                       " not found, cannot migrate " + client);
                    continue;
                }
                boolean ok = backupFile(xmlFile, backupSuffix);
                if (!ok) {
                    System.err.println("WARNING: Failed to backup up XML file " + xmlFile +
                                       ", cannot migrate " + client);
                    continue;
                }
                File tmpFile = new File(xmlFile + ".tmp");
                try {
                    WorkingDir.migrateFileXML(xmlFile, tmpFile,
                                              "<Ref id=", "<Ref refid=",
                                              "/jetty/configure.dtd", "/jetty/configure_9_3.dtd");
                    ok = FileUtil.rename(tmpFile, xmlFile);
                    if (!ok)
                        throw new IOException();
                } catch (IOException ioe) {
                    System.err.println("WARNING: Failed to migrate XML file " + xmlFile +
                                       ", cannot migrate " + client);
                    continue;
                }
                migration2success = true;
            }
            System.err.println("Migrated " + client);
        }

        if (migration2success)
            ctx.router().saveConfig(PROP_JETTY9_MIGRATED_2, "true");
    }

    /** do we have Jetty 7/8/9? */
    private static boolean hasLatestJetty() {
        if (!_wasChecked) {
            try {
                LoadClientAppsJob.testClient(TEST_CLASS, null);
                _hasLatestJetty = true;
            } catch (ClassNotFoundException cnfe) {}
            _wasChecked = true;
        }
        return _hasLatestJetty;
    }

    /**
     *  Backup a file
     *  @return success
     *  @since Jetty 7
     */
    private static boolean backupFile(File from) {
        return backupFile(from, BACKUP_SUFFIX);
    }

    /**
     *  Backup a file with given suffix
     *  @return success
     *  @since Jetty 9
     */
    private static boolean backupFile(File from, String suffix) {
        if (!from.exists())
            return true;
        File to = new File(from.getAbsolutePath() + suffix);
        if (to.exists())
            to = new File(to.getAbsolutePath() + "." + System.currentTimeMillis());
        boolean rv = WorkingDir.copyFile(from, to);
        if (rv)
            System.err.println("Backed up file " + from + " to " + to);
        else
            System.err.println("WARNING: Failed to back up file " + from + " to " + to);
        return rv;
    }

    /**
     *  Backup a file and migrate new XML
     *  @return success
     *  @since Jetty 7
     */
    private static boolean backupAndMigrateFile(File templateDir, File toDir, String filename, String fromString, String toString) {
        File to = new File(toDir, filename);
        boolean rv = backupFile(to);
        boolean rv2 = WorkingDir.migrateJettyXml(templateDir, toDir, filename, fromString, toString);
        return rv && rv2;
    }

    /**
     *  Backup a file and copy new
     *  @return success
     *  @since Jetty 7
     */
    private static boolean backupAndCopyFile(File templateDir, File toDir, String filename) {
        File to = new File(toDir, filename);
        boolean rv = backupFile(to);
        File from = new File(templateDir, filename);
        boolean rv2 = WorkingDir.copyFile(from, to);
        return rv && rv2;
    }
}
