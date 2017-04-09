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
    private static final String JETTY_TEMPLATE_DIR = "eepsite-jetty9";
    private static final String JETTY_TEMPLATE_PKGDIR = "eepsite";
    private static final String BASE_CONTEXT = "contexts/base-context.xml";
    private static final String CGI_CONTEXT = "contexts/cgi-context.xml";
    private static final String PROP_JETTY9_MIGRATED = "router.startup.jetty9.migrated";
    
    /**
     *  For each entry in apps, if the main class is an old Jetty class,
     *  migrate it to the new Jetty class, and update the Jetty config files.
     */
    public static void migrate(RouterContext ctx, List<ClientAppConfig> apps) {
        if (ctx.getBooleanProperty(PROP_JETTY9_MIGRATED))
            return;
        String installed = ctx.getProperty("router.firstVersion");
        if (installed != null && VersionComparator.comp(installed, "0.9.30") >= 0) {
            ctx.router().saveConfig(PROP_JETTY9_MIGRATED, "true");
            return;
        }
        boolean shouldSave = false;
        boolean jetty9success = false;
        for (int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = apps.get(i);
            String client;
            String backupSuffix;
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
            boolean ok = backupFile(xmlFile, backupSuffix);
            if (!ok) {
                System.err.println("WARNING: Failed to backup up XML file " + xmlFile +
                               ", cannot migrate " + client);
                continue;
            }
            if (app.className.equals(NEW_CLASS)) {
                // Do the migration of 8 to 9, handle additional command-line xml files too
                for (int j = 0; j < args.length; j++) {
                    if (j > 0) {
                        // probably jetty-ssl.xml
                        xmlFile = new File(args[j]);
                        ok = backupFile(xmlFile, backupSuffix);
                        if (!ok) {
                            System.err.println("WARNING: Failed to backup up XML file " + xmlFile +
                                               ", cannot migrate " + client);
                            continue;
                        }
                    }
                    boolean ok9 = migrateToJetty9(xmlFile);
                    if (ok9) {
                        System.err.println("WARNING: Migrated " + client + ".\n" +
                                           "Check the " + xmlFile.getName() + " file in " + eepsite + ".\n" +
                                           "Your old " + xmlFile.getName() + " file was backed up to " + xmlFile.getAbsolutePath() + BACKUP_SUFFIX_8);
                        jetty9success = true;
                    }
                }
                continue;
            }

            // Below here is migration of 5/6 to 9

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
                               " to Jetty 9 " + NEW_CLASS);
            }
        }
        if (jetty9success)
            ctx.router().saveConfig(PROP_JETTY9_MIGRATED, "true");
    }

    /**
     *  Migrate a jetty.xml file to Jetty 9.
     *  Unlike above, where we just migrate the new install file over for Jetty 9,
     *  here we modify the xml file in-place to preserve settings where possible.
     *
     *  @return success
     *  @since Jetty 9
     */
    private static boolean migrateToJetty9(File xmlFile) {
        if (xmlFile.getName().equals("jetty-jmx.xml")) {
            // This is lazy but nobody's using jmx, not worth the trouble
            System.err.println("ERROR: Migration  of " + xmlFile +
                               " file is not supported. Copy new file from $I2P/eepsite-jetty9/jetty-jmx.xml");
            return false;
        }
        // we don't re-migrate from the template, we just add the
        // necessary args for the QueuedThreadPool constructor in-place
        // and fixup the renamed set call
        boolean modified = false;
        File eepsite = xmlFile.getParentFile();
        File newFile = new File(eepsite, xmlFile.getName() + System.currentTimeMillis() + ".tmp");
        FileInputStream in = null;
        PrintWriter out = null;
        try {
            in = new FileInputStream(xmlFile);
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(newFile), "UTF-8")));
            String s;
            boolean foundQTP = false;
            boolean foundSTP = false;
            boolean foundETP = false;
            boolean foundSCC = false;
            boolean foundHC = false;
            boolean foundSSCC = false;
            while ((s = DataHelper.readLine(in)) != null) {
                // readLine() doesn't strip \r
                if (s.endsWith("\r"))
                    s = s.substring(0, s.length() - 1);
                if (s.contains("Modified by I2P migration script for Jetty 9.") ||
                    s.contains("This configuration supports Jetty 9.") ||
                    s.contains("http://www.eclipse.org/jetty/configure_9_0.dtd")) {
                    if (!modified)
                        break;
                    // else we've modified it twice?
                } else if (s.contains("org.eclipse.jetty.util.thread.QueuedThreadPool")) {
                    foundQTP = true;
                } else if (foundQTP) {
                    if (!(s.contains("Modified by") || s.contains("<Arg type=\"int\">"))) {
                        out.println("        <!-- Modified by I2P migration script for Jetty 9. Do not remove this line -->");
                        out.println("        <Arg type=\"int\">20</Arg>     <!-- maxThreads, overridden below -->");
                        out.println("        <Arg type=\"int\">3</Arg>      <!-- minThreads, overridden below -->");
                        out.println("        <Arg type=\"int\">60000</Arg>  <!-- maxIdleTimeMs, overridden below -->");
                        modified = true;
                    }
                    foundQTP = false;
                }
                if (s.contains("<Set name=\"maxIdleTimeMs\">")) {
                    // <Set name="maxIdleTimeMs">60000</Set>
                    s = s.replace("<Set name=\"maxIdleTimeMs\">", "<Set name=\"idleTimeout\">");
                    modified = true;
                } else if (s.contains("<Set name=\"ThreadPool\">")) {
                    // <Set name="ThreadPool">, must be changed to constructor arg
                    out.println("    <!-- Modified by I2P migration script for Jetty 9. Do not remove this line -->");
                    s = s.replace("<Set name=\"ThreadPool\">", "<Arg>");
                    foundSTP = true;
                    modified = true;
                } else if (foundSTP && !foundETP && s.contains("</Set>") && !s.contains("<Set")) {
                    // </Set> (close of <Set name="ThreadPool">)
                    // All the lines above have <Set>...</Set> on the same line, if they don't, this will break.
                    s = s.replace("</Set>", "</Arg>");
                    foundETP = true;
                } else if (s.contains("org.eclipse.jetty.server.nio.SelectChannelConnector")) {
                    s = s.replace("org.eclipse.jetty.server.nio.SelectChannelConnector", "org.eclipse.jetty.server.ServerConnector");
                    out.println("          <!-- Modified by I2P migration script for Jetty 9. Do not remove this line -->");
                    out.println(s);
                    out.println("            <Arg><Ref id=\"Server\" /></Arg>");
                    out.println("            <Arg type=\"int\">1</Arg>     <!-- number of acceptors -->");
                    out.println("            <Arg type=\"int\">0</Arg>     <!-- default number of selectors -->");
                    out.println("            <Arg>");
                    out.println("              <Array type=\"org.eclipse.jetty.server.ConnectionFactory\">    <!-- varargs so we need an array -->");
                    out.println("                <Item>");
                    out.println("                  <New class=\"org.eclipse.jetty.server.HttpConnectionFactory\">");
                    out.println("                    <Arg>");
                    out.println("                      <New class=\"org.eclipse.jetty.server.HttpConfiguration\">");
                    out.println("                        <Set name=\"sendServerVersion\">false</Set>");
                    out.println("                        <Set name=\"sendDateHeader\">true</Set>");
                    out.println("                      </New>");
                    out.println("                    </Arg>");
                    out.println("                  </New>");
                    out.println("                </Item>");
                    out.println("              </Array>");
                    out.println("            </Arg>");
                    modified = true;
                    continue;
             // SSL starts here
                } else if (s.contains("org.eclipse.jetty.http.ssl.SslContextFactory")) {
                    s = s.replace("org.eclipse.jetty.http.ssl.SslContextFactory", "org.eclipse.jetty.util.ssl.SslContextFactory");
                    out.println("  <!-- Modified by I2P migration script for Jetty 9. Do not remove this line -->");
                    out.println(s);
                    // don't try to migrate from below, just generate a new list
                    out.println("    <Set name=\"ExcludeCipherSuites\">");
                    out.println("      <Array type=\"java.lang.String\">");
                    for (String ss : I2PSSLSocketFactory.EXCLUDE_CIPHERS) {
                        out.println("        <Item>" + ss + "</Item>");
                    }
                    out.println("      </Array>");
                    out.println("    </Set>");
                    out.println("    <Set name=\"ExcludeProtocols\">");
                    out.println("      <Array type=\"java.lang.String\">");
                    for (String ss : I2PSSLSocketFactory.EXCLUDE_PROTOCOLS) {
                        out.println("        <Item>" + ss + "</Item>");
                    }
                    out.println("      </Array>");
                    out.println("    </Set>");
                    modified = true;
                    continue;
                } else if (s.contains("org.eclipse.jetty.server.ssl.SslSelectChannelConnector")) {
                    s = s.replace("org.eclipse.jetty.server.ssl.SslSelectChannelConnector", "org.eclipse.jetty.server.ServerConnector");
                    out.println("      <!-- Modified by I2P migration script for Jetty 9. Do not remove this line -->");
                    out.println(s);
                    out.println("        <Arg><Ref id=\"Server\" /></Arg>");
                    out.println("        <Arg type=\"int\">1</Arg>     <!-- number of acceptors -->");
                    out.println("        <Arg type=\"int\">0</Arg>     <!-- default number of selectors -->");
                    out.println("        <Arg>");
                    out.println("           <Array type=\"org.eclipse.jetty.server.ConnectionFactory\">    <!-- varargs so we need an array -->");
                    out.println("              <Item>");
                    out.println("                <New class=\"org.eclipse.jetty.server.SslConnectionFactory\">");
                    out.println("                  <Arg><Ref id=\"sslContextFactory\" /></Arg>");
                    out.println("                  <Arg>http/1.1</Arg>");
                    out.println("                </New>");
                    out.println("              </Item>");
                    out.println("              <Item>");
                    out.println("                <New class=\"org.eclipse.jetty.server.HttpConnectionFactory\">");
                    out.println("                  <Arg>");
                    out.println("                    <New class=\"org.eclipse.jetty.server.HttpConfiguration\">");
                    out.println("                      <Set name=\"sendServerVersion\">false</Set>");
                    out.println("                      <Set name=\"sendDateHeader\">true</Set>");
                    out.println("                    </New>");
                    out.println("                  </Arg>");
                    out.println("                </New>");
                    out.println("              </Item>");
                    out.println("            </Array>");
                    out.println("        </Arg>");
                    foundSSCC = true;
                    modified = true;
                    continue;
                } else if (foundSSCC && s.contains("<Set name=\"ExcludeCipherSuites\">")) {
                    // delete the old ExcludeCipherSuites in this section
                    do {
                        s = DataHelper.readLine(in);
                    } while(s != null && !s.contains("</Set>"));
                    modified = true;
                    continue;
                } else if (foundSSCC &&
                           s.contains("<Ref id=\"sslContextFactory\"")) {
                    // delete old one in this section, replaced above
                    modified = true;
                    continue;
                } else if (s.contains("<Set name=\"KeyStore\">")) {
                    s = s.replace("<Set name=\"KeyStore\">", "<Set name=\"KeyStorePath\">");
                    modified = true;
                } else if (s.contains("<Set name=\"TrustStore\">")) {
                    s = s.replace("<Set name=\"TrustStore\">", "<Set name=\"TrustStorePath\">");
                    modified = true;
             // SSL ends here
                } else if (s.contains("class=\"org.eclipse.jetty.deploy.providers.ContextProvider\">")) {
                    // WebAppProvider now also does what ContextProvider used to do
                    out.println("        <!-- Modified by I2P migration script for Jetty 9. Do not remove this line -->");
                    s = s.replace("class=\"org.eclipse.jetty.deploy.providers.ContextProvider\">", "class=\"org.eclipse.jetty.deploy.providers.WebAppProvider\">");
                    modified = true;
                } else if (s.contains("<Set name=\"maxIdleTime\">")) {
                    s = s.replace("<Set name=\"maxIdleTime\">", "<Set name=\"idleTimeout\">");
                    modified = true;
                } else if (s.contains("<Set name=\"gracefulShutdown\">")) {
                    s = s.replace("<Set name=\"gracefulShutdown\">", "<Set name=\"stopTimeout\">");
                    modified = true;
                } else if (s.contains("org.eclipse.jetty.server.HttpConfiguration")) {
                    foundHC = true;
                } else if (!foundHC &&
                           (s.contains("<Set name=\"sendServerVersion\">") ||
                            s.contains("<Set name=\"sendDateHeader\">"))) {
                    // old ones for Server, not in HTTPConfiguration section, delete
                    modified = true;
                    continue;
                } else if (s.contains("<Set name=\"Acceptors\">") ||
                           s.contains("<Set name=\"acceptors\">") ||
                           s.contains("<Set name=\"statsOn\">") ||
                           s.contains("<Set name=\"confidentialPort\">") ||
                           s.contains("<Set name=\"lowResourcesConnections\">") ||
                           s.contains("<Set name=\"lowResourcesMaxIdleTime\">") ||
                           s.contains("<Set name=\"useDirectBuffers\">")) {
                    // delete
                    modified = true;
                    continue;
                }
                out.println(s);
            }
        } catch (IOException ioe) {
            if (in != null) {
                System.err.println("FAILED migration of " + xmlFile + ": " + ioe);
            }
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) out.close();
        }
        if (modified) {
            return FileUtil.rename(newFile, xmlFile);
        } else {
            newFile.delete();
            return true;
        }
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
