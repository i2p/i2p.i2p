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
 *</p>
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
 *<p>
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

    private static final String NEW_CLASS = "net.i2p.jetty.JettyStart";
    private static final String TEST_CLASS = "org.eclipse.jetty.util.component.Environment";
    private static final String BACKUP_SUFFIX_9 = ".jetty9-id";
    private static final String BACKUP_SUFFIX_9_2 = ".jetty93-save";
    private static final String JETTY_TEMPLATE_DIR = "eepsite-jetty9";
    private static final String JETTY_TEMPLATE_PKGDIR = "eepsite";
    private static final String BASE_CONTEXT = "contexts/base-context.xml";
    private static final String CGI_CONTEXT = "contexts/cgi-context.xml";
    private static final String PROP_JETTY9_MIGRATED = "router.startup.jetty9.migrated";
    private static final String PROP_JETTY9_MIGRATED_2 = "router.startup.jetty-ids.migrated";
    private static final String PROP_JETTY12_MIGRATED = "router.startup.jetty12.migrated";
    
    /**
     *  For each entry in apps, if the main class is an old Jetty class,
     *  migrate it to the new Jetty class, and update the Jetty config files.
     */
    public static void migrate(RouterContext ctx, List<ClientAppConfig> apps) {
        if (ctx.getBooleanProperty(PROP_JETTY12_MIGRATED))
            return;
        String installed = ctx.getProperty("router.firstVersion");
        if (installed != null && VersionComparator.comp(installed, "2.11.0") >= 0) {
            ctx.router().saveConfig(PROP_JETTY12_MIGRATED, "true");
            return;
        }
        boolean migrated2 = ctx.getBooleanProperty(PROP_JETTY9_MIGRATED_2);
        if (!migrated2 && installed != null && VersionComparator.comp(installed, "2.9.0") >= 0) {
            ctx.router().saveConfig(PROP_JETTY9_MIGRATED_2, "true");
            migrated2 = true;
        }
        boolean migrated1 = ctx.getBooleanProperty(PROP_JETTY9_MIGRATED);
        if (!migrated1 && installed != null && VersionComparator.comp(installed, "0.9.30") >= 0) {
            ctx.router().saveConfig(PROP_JETTY9_MIGRATED, "true");
            migrated1 = true;
        }
        boolean migration2success = false;
        boolean migration3success = false;
        for (int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = apps.get(i);
            String client = "client application " + i + " [" + app.clientName +
                            "] from Jetty 9 to Jetty 12";
            String backupSuffix;
            if (migrated1) {
                if (!app.className.equals(NEW_CLASS)) {
                    continue;
                }
            } else {
                // migration from 0.9.29 or earlier (2017-02-27) straight to 2.11.0 or later
                System.err.println("WARNING: Unable to migrate " + client +
                                   ", delete client or uninstall and reinstall I2P");
                app.disabled = true;
                continue;
            }
            if (!hasLatestJetty()) {
                System.err.println("WARNING: Jetty 12 unavailable, cannot migrate " + client);
                continue;
            }
            if (app.args == null)
                continue;
            // remove quotes
            String args[] = LoadClientAppsJob.parseArgs(app.args);
            if (args.length == 0)
                continue;

            System.err.println("Migrating " + client);

            // migration 2 below here

            // Note that JettyStart automatically copies and adds jetty-gzip.xml
            // to the command line, not in the arg list here,
            // but it does not contain anything we need to fix.
            if (!migrated2) {
                backupSuffix = BACKUP_SUFFIX_9;
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
                        ioe.printStackTrace();
                        continue;
                    }
                    migration2success = true;
                }
            }

            // migration 3 below here

            if (true) {
                backupSuffix = BACKUP_SUFFIX_9_2;
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
                                                  "/jetty/configure_9_3.dtd", "/jetty/configure_10_0.dtd",
                                                  "This configuration supports Jetty 9.", "This configuration supports Jetty 12");
                        ok = FileUtil.rename(tmpFile, xmlFile);
                        if (!ok)
                            throw new IOException();

                        if (xmlFile.getName().equals("jetty-ssl.xml")) {
                            System.err.println("WARNING: SSL migration to Jetty 12 is not yet implemented.");
                            System.err.println("Cannot fully migrate " + client);
                            System.err.println("Remove jetty-ssl.xml from the command line for the client");
                            System.err.println("See http://zzz.i2p/topics/3702 for help on migrating SSL");
                            continue;
                        }

                        migrate9to12XML(xmlFile, tmpFile);
                        ok = FileUtil.rename(tmpFile, xmlFile);
                        if (!ok)
                            throw new IOException();

                    } catch (IOException ioe) {
                        System.err.println("WARNING: Failed to migrate XML file " + xmlFile +
                                           ", cannot migrate " + client);
                        ioe.printStackTrace();
                        continue;
                    }
                    migration3success = true;
                }
                // jetty-gzip.xml
                File xmlFile = new File(args[0]);
                if (!xmlFile.isAbsolute())
                    xmlFile = new File(ctx.getAppDir(), args[0]);
                File base = xmlFile.getParentFile();
                xmlFile = new File(base, "jetty-gzip.xml");
                if (xmlFile.exists()) {
                    boolean ok = backupFile(xmlFile, backupSuffix);
                    if (ok)
                        ok = WorkingDir.copyFile(new File(ctx.getBaseDir(), "eepsite-jetty9.3/jetty-gzip.xml"), xmlFile);
                    if (ok)
                        System.err.println("Modified " + xmlFile);
                    else
                        System.err.println("WARNING: Failed to backup up XML file " + xmlFile +
                                           ", cannot migrate " + client);
                }
                // contexts/base-context.xml
                xmlFile = new File(base, "contexts/base-context.xml");
                if (xmlFile.exists()) {
                    try {
                        boolean ok = backupFile(xmlFile, backupSuffix);
                        if (ok) {
                            File tmpFile = new File(xmlFile + ".tmp");
                            migrateBaseContextXML(xmlFile, tmpFile);
                            ok = FileUtil.rename(tmpFile, xmlFile);
                            if (!ok)
                                throw new IOException();
                            System.err.println("Modified " + xmlFile);
                        }
                    } catch (IOException ioe) {
                        System.err.println("WARNING: Failed to migrate XML file " + xmlFile +
                                           ", cannot migrate " + client);
                        ioe.printStackTrace();
                    }
                }
                // contexts/cgi-context.xml
                xmlFile = new File(base, "contexts/cgi-context.xml");
                if (xmlFile.exists()) {
                    File save = new File(xmlFile + BACKUP_SUFFIX_9_2);
                    FileUtil.rename(xmlFile, save);
                    System.err.println("WARNING: CGI not supported on Jetty 12 and has been disabled.");
                    System.err.println(xmlFile + " moved to " + save);
                    System.err.println("See http://zzz.i2p/topics/3701 for help on migrating to FCGI if required");
                }
            }

            System.err.println("Migrated " + client);
        }

        if (!migrated2 && migration2success)
            ctx.router().saveConfig(PROP_JETTY9_MIGRATED_2, "true");
        if (migration3success)
            ctx.router().saveConfig(PROP_JETTY12_MIGRATED, "true");
    }

    /** do we have Jetty 12? */
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

    private static final String M1 = "<Set name=\"handler\"";
    private static final String R1 =
        "     <Set name=\"defaultHandler\">\n" +
        "       <New id=\"DefaultHandler\" class=\"org.eclipse.jetty.server.handler.DefaultHandler\">\n" +
        "         <Set name=\"showContexts\">false</Set>\n" +
        "       </New>\n" +
        "     </Set>\n" +
        "     <Set name=\"handler\">\n" +
        "       <New id=\"Contexts\" class=\"org.eclipse.jetty.server.handler.ContextHandlerCollection\"/>\n" +
        "     </Set>\n";

    private static final String R2 =
        "    <!-- Setup ee8 environment -->\n" +
        "    <!-- First call needed to initialize the class and prevent NPE -->\n" +
        "    <Call class=\"org.eclipse.jetty.util.component.Environment\" name=\"get\" >\n" +
        "        <Arg>foo</Arg>\n" +
        "    </Call>\n" +
        "    <New id=\"EBuilder\" class=\"org.eclipse.jetty.xml.EnvironmentBuilder\" >\n" +
        "      <Arg>ee8</Arg>\n" +
        "    </New>\n" +
        "    <Ref refid=\"EBuilder\">\n" +
        "      <Call id=\"Environment\" name=\"build\" />\n" +
        "    </Ref>\n" +
        "    <Ref refid=\"Environment\">\n" +
        "      <Call class=\"org.eclipse.jetty.util.Attributes\" name=\"setAttribute\">\n" +
        "        <Arg>contextHandlerClass</Arg>\n" +
        "        <Arg>org.eclipse.jetty.ee8.webapp.WebAppContext</Arg>\n" +
        "      </Call>\n" +
        "    </Ref>\n" +
        "    <Call class=\"org.eclipse.jetty.util.component.Environment\" name=\"set\" >\n" +
        "      <Arg>\n" +
        "        <Ref refid=\"Environment\"/>\n" +
        "      </Arg>\n" +
        "    </Call>\n";

    private static final String D1 = "<Call name=\"setContextAttribute\"";

    private static final String M3 = "<New class=\"org.eclipse.jetty.deploy.providers.WebAppProvider\"";
    private static final String R3 =
        "          <New class=\"org.eclipse.jetty.deploy.providers.ContextProvider\">\n" +
        "            <Set name=\"EnvironmentName\">ee8</Set>\n" +
        "            <Set name=\"parentLoaderPriority\">true</Set>\n" +
        "            <Set name=\"configurationClasses\" property=\"jetty.deploy.configurationClasses\" />\n";

    private static final String M4 = "<New id=\"WebAppProvider\" class=\"org.eclipse.jetty.deploy.providers.WebAppProvider\"";
    private static final String R4 =
        "          <New id=\"WebAppProvider\" class=\"org.eclipse.jetty.deploy.providers.ContextProvider\">\n" +
        "            <Set name=\"EnvironmentName\">ee8</Set>\n" +
        "            <Set name=\"parentLoaderPriority\">true</Set>\n" +
        "            <Set name=\"configurationClasses\" property=\"jetty.deploy.configurationClasses\" />\n";

    private static final String M5 = "<Set name=\"parentLoaderPriority\">false</Set>";
    private static final String R5 = "<Set name=\"parentLoaderPriority\">true</Set>";

    private static final String D2 = "<Ref refid=\"RequestLog\">";

    /**
     *  Copy over a XML file with modifications.
     *  Will overwrite any existing newFile.
     *
     *  @throws IOException on all errors
     *  @since 0.9.68
     */
    private static void migrate9to12XML(File oldFile, File newFile) throws IOException {
        FileInputStream in = null;
        PrintWriter out = null;
        try {
            in = new FileInputStream(oldFile);
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(newFile), "UTF-8")));
            String s = null;
            while ((s = DataHelper.readLine(in)) != null) {
                // readLine() doesn't strip \r
                if (s.endsWith("\r"))
                    s = s.substring(0, s.length() - 1);
                if (s.contains(M1)) {
                    // strip to second </Set>
                    String t;
                    int i = 0;
                    while ((t = DataHelper.readLine(in)) != null) {
                        if (t.contains("</Set") && i++ > 0)
                            break;
                    }
                    out.println(R1);
                    out.println(R2);
                } else if(s.contains(M3)) {
                    // strip to line before <Set name="monitoredDirName">
                    String t;
                    while ((t = DataHelper.readLine(in)) != null) {
                        if (t.contains("\"monitoredDirName\""))
                            break;
                    }
                    out.println(R3);
                    out.println(t);
                } else if(s.contains(M4)) {
                    // strip to line before <Set name="monitoredDirName">
                    String t;
                    while ((t = DataHelper.readLine(in)) != null) {
                        if (t.contains("\"monitoredDirName\""))
                            break;
                    }
                    out.println(R4);
                    out.println(t);
                } else if(s.contains(M5)) {
                    out.println(R5);
                } else if(s.contains(D1)) {
                    // strip to </Call>
                    String t;
                    while ((t = DataHelper.readLine(in)) != null) {
                        if (t.contains("</Call"))
                            break;
                    }
                } else if(s.contains(D2)) {
                    // strip this and </Ref>, keep lines between
                    String t;
                    while ((t = DataHelper.readLine(in)) != null) {
                        if (t.contains("</Ref"))
                            break;
                        out.println(t);
                    }
                } else {
                    out.println(s);
                }
            }
            out.println("<!-- Modified by I2P Jetty 12 migration script -->");
            System.err.println("Copied " + oldFile + " with modifications");
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) out.close();
        }
    }

    private static final String M10 = "jetty/configure.dtd";
    private static final String R10 = "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"http://www.eclipse.org/jetty/configure_10_0.dtd\">";

    private static final String M11 = "org.eclipse.jetty.servlet.ServletContextHandler";
    private static final String R11 = "<Configure class=\"org.eclipse.jetty.ee8.servlet.ServletContextHandler\">";

    private static final String M12 = "<Set name=\"resourceBase\"";
    private static final String R12 =
           "  <Set name=\"baseResourceAsString\"><Ref refid=\"baseroot\" /></Set>\n" +
           "  <Call name=\"setErrorHandler\">\n" +
           "    <Arg>\n" +
           "      <New class=\"net.i2p.servlet.I2PErrorHandler\">\n" +
           "        <Arg><Ref refid=\"baseroot\" /></Arg>\n" +
           "      </New>\n" +
           "    </Arg>\n" +
           "  </Call>";

    private static final String M13 = "<Call name=\"setMimeTypes\"";

    private static final String M14 = "org.eclipse.jetty.servlet.DefaultServlet";
    private static final String R14 = "net.i2p.servlet.I2PDefaultServlet";


    /**
     *  Copy over a XML file with modifications.
     *  Will overwrite any existing newFile.
     *
     *  @throws IOException on all errors
     *  @since 0.9.68
     */
    private static void migrateBaseContextXML(File oldFile, File newFile) throws IOException {
        FileInputStream in = null;
        PrintWriter out = null;
        try {
            in = new FileInputStream(oldFile);
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(newFile), "UTF-8")));
            String s = null;
            while ((s = DataHelper.readLine(in)) != null) {
                // readLine() doesn't strip \r
                if (s.endsWith("\r"))
                    s = s.substring(0, s.length() - 1);
                if (s.contains(M10)) {
                    out.println(R10);
                } else if (s.contains(M11)) {
                    out.println(R11);
                } else if (s.contains(M12)) {
                    int gt = s.indexOf('>');
                    int lt = s.lastIndexOf('<');
                    if (gt >= 0 && lt >= 0 && lt > gt) {
                        String rb = s.substring(gt + 1, lt).trim();
                        out.println("  <New id=\"baseroot\" class=\"java.lang.String\">");
                        out.println("    <Arg>" + rb + "</Arg>");
                        out.println("  </New>");
                        out.println(R12);
                    }
                } else if (s.contains(M13)) {
                    // strip to matching </Call>
                    int i = 1;
                    String t;
                    while ((t = DataHelper.readLine(in)) != null) {
                        if (t.contains("<Call"))
                            i++;
                        if (t.contains("</Call"))
                            i--;
                        if (i == 0)
                            break;
                    }
                } else if (s.contains(M14)) {
                    out.println(s.replace(M14, R14));
                } else {
                    out.println(s);
                }
            }
            out.println("<!-- Modified by I2P Jetty 12 migration script -->");
            System.err.println("Copied " + oldFile + " with modifications");
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) out.close();
        }
    }
}
