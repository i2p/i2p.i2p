package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

import java.io.File;
import java.util.List;

import net.i2p.router.RouterContext;

/**
 *  Migrate the clients.config and jetty.xml files
 *  from Jetty 5/6 to Jetty 7.
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
 *  Does NOT preserve port number, thread counts, etc.
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
    private static final String JETTY_TEMPLATE_DIR = "eepsite-jetty7";
    private static final String JETTY_TEMPLATE_PKGDIR = "eepsite";
    private static final String BASE_CONTEXT = "contexts/base-context.xml";
    private static final String CGI_CONTEXT = "contexts/cgi-context.xml";
    
    /**
     *  For each entry in apps, if the main class is an old Jetty class,
     *  migrate it to the new Jetty class, and update the Jetty config files.
     */
    public static void migrate(RouterContext ctx, List<ClientAppConfig> apps) {
        boolean shouldSave = false;
        for (int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = apps.get(i);
            if (!(app.className.equals(OLD_CLASS) || app.className.equals(OLD_CLASS_6)))
                continue;
            String client = "client application " + i + " [" + app.clientName +
                            "] from Jetty 5/6 " + app.className +
                            " to Jetty 7 " + NEW_CLASS;
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
            String xml = args[0];
            File xmlFile = new File(xml);
            if (!xmlFile.isAbsolute())
                xmlFile = new File(ctx.getAppDir(), xml);
            if (!xmlFile.exists()) {
                System.err.println("WARNING: XML file " + xmlFile +
                               " not found, cannot migrate " + client);
                continue;
            }
            File eepsite = xmlFile.getParentFile();
            boolean ok = backupFile(xmlFile);
            if (!ok) {
                System.err.println("WARNING: Failed to backup up XML file " + xmlFile +
                               ", cannot migrate " + client);
                continue;
            }
            File baseEep = new File(ctx.getBaseDir(), JETTY_TEMPLATE_DIR);
            // in packages, or perhaps on an uninstall/reinstall, the files are in eepsite/
            if (!baseEep.exists())
                baseEep = new File(ctx.getBaseDir(), JETTY_TEMPLATE_PKGDIR);
            if (baseEep.equals(eepsite)) {
                // non-split directory yet not an upgrade? shouldn't happen
                System.err.println("Eepsite in non-split directory " + eepsite +
                               ", cannot migrate " + client);
                continue;
            }
            // jetty.xml existed before in jetty 5 version, so check this new file
            // and if it doesn't exist we can't continue
            File baseContext = new File(baseEep, BASE_CONTEXT);
            if (!baseContext.exists()) {
                System.err.println("WARNING: Cannot find new XML file template " + baseContext +
                               ", cannot migrate " + client);
                continue;
            }
            String newPath = eepsite.getAbsolutePath() + File.separatorChar;
            ok = WorkingDir.migrateJettyXml(baseEep, eepsite, "jetty.xml", "./eepsite/", newPath);
            if (!ok) {
                System.err.println("WARNING: Failed to modify XML file " + xmlFile +
                               ", cannot migrate " + client);
                continue;
            }
            // now we're committed, so don't check any more failure codes
            backupAndMigrateFile(baseEep, eepsite, "jetty-ssl.xml", "./eepsite/", newPath);
            (new File(eepsite, "contexts")).mkdir();
            // ContextProvider scanner only looks for files ending in .xml so we can
            // back up to the same directory
            backupAndMigrateFile(baseEep, eepsite, BASE_CONTEXT, "./eepsite/", newPath);
            backupAndMigrateFile(baseEep, eepsite, CGI_CONTEXT, "./eepsite/", newPath);
            backupAndCopyFile(baseEep, eepsite, "jetty-rewrite.xml");
            (new File(eepsite, "etc")).mkdir();
            // realm.properties: No change from 6 to 7
            File to = new File(eepsite, "etc/realm.properties");
            if (!to.exists())
                WorkingDir.copyFile(new File(baseEep, "etc/realm.properties"), to);
            backupAndCopyFile(baseEep, eepsite, "etc/webdefault.xml");
            app.className = NEW_CLASS;
            shouldSave = true;
            System.err.println("WARNING: Migrated " + client + '\n' +
                               "Check the following files in " + eepsite +
                               ": jetty.xml, " + BASE_CONTEXT + ", and " + CGI_CONTEXT + "\n" +
                               "Your old jetty.xml was backed up." + '\n' +
                               "If you modified your jetty.xml to change ports, thread limits, etc, you MUST\n" +
                               "edit it to change them again. Your port was reset to 7658.");
        }
        if (shouldSave) {
            File cfgFile = ClientAppConfig.configFile(ctx);
            boolean ok = backupFile(cfgFile);
            if (ok) {
                ClientAppConfig.writeClientAppConfig(ctx, apps);
                System.err.println("WARNING: Migrated clients config file " + cfgFile +
                               " from Jetty 5/6 " + OLD_CLASS + '/' + OLD_CLASS_6 +
                               " to Jetty 7 " + NEW_CLASS);
            }
        }
    }

    /** do we have Jetty 7? */
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
        if (!from.exists())
            return true;
        File to = new File(from.getAbsolutePath() + BACKUP_SUFFIX);
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
