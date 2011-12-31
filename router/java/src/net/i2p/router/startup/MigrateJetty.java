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
 *  from Jetty 5 to Jetty 6.
 *
 *  For each client for class org.mortbay.jetty.Server:
 *<pre>
 *  Let $D be the dir that jetty.xml is in (usually ~/.i2p/eepsite)
 *  Saves $D/jetty.xml to $D/jetty5.xml
 *  Copies $I2P/eepsite-jetty6/jetty.xml to $D/jetty.xml, edited for $D
 *  Copies $I2P/eepsite-jetty6/jetty-ssl.xml to $D/jetty-ssl.xml, edited for $D
 *  Copies $I2P/eepsite-jetty6/context/base-context.xml to $D/jetty.xml, edited for $D
 *  Copies $I2P/eepsite-jetty6/context/cgi-context.xml to $D/jetty.xml, edited for $D
 *  Copies $I2P/eepsite-jetty6/etc/* to $D/etc
 *  Changes main class in clients.config
 *</pre>
 *  Copies clients.config to clients.config.backup
 *  Saves new clients.config
 *
 *  Does NOT preserve port number, thread counts, etc.
 *
 *  @since Jetty 6
 */
abstract class MigrateJetty {
    private static boolean _wasChecked;
    private static boolean _hasJetty6;

    private static final String OLD_CLASS = "org.mortbay.jetty.Server";
    private static final String NEW_CLASS = "org.mortbay.start.Main";
    private static final String BACKUP = "jetty5.xml";
    private static final String JETTY6_TEMPLATE_DIR = "eepsite-jetty6";
    private static final String BASE_CONTEXT = "contexts/base-context.xml";
    private static final String CGI_CONTEXT = "contexts/cgi-context.xml";
    
    public static void migrate(RouterContext ctx, List<ClientAppConfig> apps) {
        boolean shouldSave = false;
        for (int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = apps.get(i);
            if (!app.className.equals(OLD_CLASS))
                continue;
            String client = "client application " + i + " [" + app.clientName +
                            "] from Jetty 5 " + OLD_CLASS +
                            " to Jetty 6 " + NEW_CLASS;
            if (!hasJetty6()) {
                System.err.println("WARNING: Jetty 6 unavailable, cannot migrate " + client);
                continue;
            }
            String xml = app.args;
            if (xml == null)
                continue;
            File xmlFile = new File(xml);
            if (!xmlFile.isAbsolute())
                xmlFile = new File(ctx.getAppDir(), xml);
            if (!xmlFile.exists()) {
                System.err.println("WARNING: XML file " + xmlFile +
                               " not found, cannot migrate " + client);
                continue;
            }
            File eepsite = xmlFile.getParentFile();
            File backup = new File(eepsite, BACKUP);
            if (backup.exists())
                backup = new File(eepsite, BACKUP + ctx.random().nextInt());
            boolean ok = WorkingDir.copyFile(xmlFile, backup);
            if (!ok) {
                System.err.println("WARNING: Failed to copy XML file " + xmlFile + " to " + backup +
                               ", cannot migrate " + client);
                continue;
            }
            File baseEep = new File(ctx.getBaseDir(), JETTY6_TEMPLATE_DIR);
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
            WorkingDir.migrateJettyXml(baseEep, eepsite, "jetty-ssl.xml", "./eepsite/", newPath);
            (new File(eepsite, "contexts")).mkdir();
            WorkingDir.migrateJettyXml(baseEep, eepsite, BASE_CONTEXT, "./eepsite/", newPath);
            WorkingDir.migrateJettyXml(baseEep, eepsite, CGI_CONTEXT, "./eepsite/", newPath);
            (new File(eepsite, "etc")).mkdir();
            File to = new File(eepsite, "etc/realm.properties");
            if (!to.exists())
                WorkingDir.copyFile(new File(baseEep, "etc/realm.properties"), to);
            to = new File(eepsite, "etc/webdefault.xml");
            if (!to.exists())
                WorkingDir.copyFile(new File(baseEep, "etc/webdefault.xml"), to);
            app.className = NEW_CLASS;
            shouldSave = true;
            System.err.println("WARNING: Migrated " + client + '\n' +
                               "Check the following files in " + eepsite +
                               ": jetty.xml, " + BASE_CONTEXT + ", and " + CGI_CONTEXT + "\n" +
                               "Your old jetty.xml was saved as " + backup + '\n' +
                               "If you modified your jetty.xml to change ports, thread limits, etc, you MUST\n" +
                               "edit it to change them again. Your port was reset to 7658.");
        }
        if (shouldSave) {
            File cfgFile = ClientAppConfig.configFile(ctx);
            File backup = new File(cfgFile.getAbsolutePath() + ".jetty5");
            if (backup.exists())
                backup = new File(cfgFile.getAbsolutePath() + ctx.random().nextInt());
            boolean ok = WorkingDir.copyFile(cfgFile, backup);
            if (ok) {
                ClientAppConfig.writeClientAppConfig(ctx, apps);
                System.err.println("WARNING: Migrated clients config file " + cfgFile +
                               " from Jetty 5 " + OLD_CLASS +
                               " to Jetty 6 " + NEW_CLASS + "\n" +
                               "Your old clients config file was saved as " + backup);
            }
        }
    }

    private static boolean hasJetty6() {
        if (!_wasChecked) {
            try {
                LoadClientAppsJob.testClient(NEW_CLASS, null);
                _hasJetty6 = true;
            } catch (ClassNotFoundException cnfe) {}
            _wasChecked = true;
        }
        return _hasJetty6;
    }
}
