package net.i2p.router.web.helpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.ClassLoader;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataHelper;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.PluginStarter;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.SystemVersion;

/**
 *  Dump info on jars and wars
 *
 *  @since 0.8.13
 */
public class FileDumpHelper extends HelperBase {

    private static final boolean isWindows = SystemVersion.isWindows();
    public String getFileSummary() {
        StringBuilder buf = new StringBuilder(16*1024);
        buf.append("<table id=\"jardump\">\n<tr><th>File</th><th>Size</th><th>Date</th><th>SHA 256</th><th>Revision</th>" +
                   "<th>JDK</th><th>Built</th><th>By</th><th>Mods</th></tr>\n");

        // jars added in wrapper.config
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        if (loader instanceof URLClassLoader) {
            // through Java 8, not available in Java 9
            URLClassLoader urlClassLoader = (URLClassLoader) loader;
            URL[] urls = urlClassLoader.getURLs();
            List<File> flist = new ArrayList<File>();
            for (int i = 0; i < urls.length; i++) {
                String p = urls[i].toString();
                if (p.startsWith("file:") && p.endsWith(".jar")) {
                    p = p.substring(5);
                    if (!(p.startsWith(_context.getBaseDir().getAbsolutePath()) ||
                          p.startsWith(_context.getConfigDir().getAbsolutePath()))) {
                        flist.add(new File(p));
                    }
                }
            }
            Collections.sort(flist);
            for (File f : flist) {
                dumpFile(buf, f);
            }
        }

        // our jars
        File dir = new File(_context.getBaseDir(), "lib");
        buf.append("<tr><th class=\"subheading routerfiles\" colspan=\"9\"><b>Router Jar Files:</b> <code>");
        buf.append(dir.getAbsolutePath());
        buf.append("</code></th></tr>\n");
        dumpDir(buf, dir, ".jar");

        // our wars
        dir = new File(_context.getBaseDir(), "webapps");
        buf.append("<tr><th class=\"subheading routerfiles\" colspan=\"9\"><b>Router War Files:</b> <code>");
        buf.append(dir.getAbsolutePath());
        buf.append("</code></th></tr>\n");
        dumpDir(buf, dir, ".war");

        // plugins
        File pluginDir = new File(_context.getConfigDir(), PluginStarter.PLUGIN_DIR);
        buf.append("<tr><th class=\"subheading pluginfiles\" colspan=\"9\"><b>I2P Plugins:</b> <code>");
        buf.append(pluginDir.getAbsolutePath());
        buf.append("</code></th></tr>");
        File[] files = pluginDir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (int i = 0; i < files.length; i++) {
                dir = new File(files[i], "lib");
                buf.append("<tr><th class=\"subheading pluginfiles\" colspan=\"9\"><b>Plugin File Location:</b> <code>");
                buf.append(dir.getAbsolutePath());
                buf.append("</code></th></tr>");
                dumpDir(buf, dir, ".jar");
                dir = new File(files[i], "console/webapps");
                dumpDir(buf, dir, ".war");
            }
        }

        buf.append("</table>");
        return buf.toString();
    }

    private static void dumpDir(StringBuilder buf, File dir, String suffix) {
        File[] files = dir.listFiles(new FileSuffixFilter(suffix));
        if (files == null)
            return;
        Arrays.sort(files);
        for (int i = 0; i < files.length; i++) {
            dumpFile(buf, files[i]);
        }
    }

    private static void dumpFile(StringBuilder buf, File f) {
        buf.append("<tr><td><b title=\"").append(f.getAbsolutePath()).append("\">").append(f.getName()).append("</b></td>" +
                   "<td align=\"right\">").append(f.length()).append("</td>" +
                   "<td>");
        long mod = f.lastModified();
        if (mod > 0)
            buf.append((new Date(mod)).toString());
        else
            buf.append("<font color=\"red\">Not found</font>");
        buf.append("</td><td align=\"center\">");
        if (mod > 0 && !FileUtil.verifyZip(f))
            buf.append("<font color=\"red\">CORRUPT</font><br>");
        byte[] hash = sha256(f);
        if (hash != null) {
            byte[] hh = new byte[16];
            System.arraycopy(hash, 0, hh, 0, 16);
            buf.append("<span class=\"sha256\"><tt>");
            String p1 = DataHelper.toHexString(hh);
            for (int i = p1.length(); i < 32; i++) {
                buf.append('0');
            }
            buf.append(p1).append("</tt><br>");
            System.arraycopy(hash, 16, hh, 0, 16);
            buf.append("<tt>").append(DataHelper.toHexString(hh)).append("</tt></span>");
        }
        Attributes att = attributes(f);
        if (att == null)
            att = new Attributes();
        buf.append("<td align=\"center\">");
        String iv = getAtt(att, "Implementation-Version");
        if (iv != null)
            buf.append("<b>").append(iv).append("</b>");
        String s = getAtt(att, "Base-Revision");
        if (s != null && s.length() > 20) {
            if (iv != null)
                buf.append("<br>");
            // fix and uncomment if a reliable viewmtn host appears
            //buf.append("<a href=\"http://killyourtv.i2p/viewmtn/revision/info/").append(s)
            //   .append("\">");
            buf.append("<span class=\"revision\"><tt>").append(s.substring(0, 20)).append("</tt>" +
                       "<br>" +
                       "<tt>").append(s.substring(20)).append("</tt></span>");
            //buf.append("</tt>");
        }
        buf.append("</td><td>");
        s = getAtt(att, "Created-By");
        if (s != null)
            buf.append(s);
        buf.append("</td><td>");
        s = getAtt(att, "Build-Date");
        if (s != null)
            buf.append(s);
        buf.append("</td><td align=\"center\">");
        s = getAtt(att, "Built-By");
        if (s != null)
            buf.append(s);
        buf.append("</td><td>");
        s = getAtt(att, "Workspace-Changes");
        if (s != null) {
            // Encase each mod in a span so we can single click select individual mods
            buf.append("<font color=\"red\"><span class=\"unsignedmod\">")
               .append(s.replace(",", "</span></font><hr><font color=\"red\"><span class=\"unsignedmod\">"))
               .append("</span></font>");
        }
        buf.append("</td></tr>\n");
    }

    private static byte[] sha256(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            MessageDigest md = SHA256Generator.getDigestInstance();
            byte[] b = new byte[4096];
            int cnt = 0;
            while ((cnt = in.read(b)) >= 0) {
                md.update(b, 0, cnt);
            }
            return md.digest();
        } catch (IOException ioe) {
            //ioe.printStackTrace();
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException e) {}
        }
    }

    /**
     * @return null if not found
     * @since pkg private since 0.9.35 for LogsHelper
     */
    static Attributes attributes(File f) {
        InputStream in = null;
        try {
            in = (new URL("jar:file:" + f.getAbsolutePath() + "!/META-INF/MANIFEST.MF")).openStream();
            Manifest man = new Manifest(in);
            return man.getMainAttributes();
        } catch (IOException ioe) {
            //ioe.printStackTrace();
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException e) {}
        }
    }

    /**
     * @param atts non-null
     * @return HTML stripped, or null if not found
     * @since pkg private since 0.9.35 for LogsHelper
     */
    static String getAtt(Attributes atts, String s) {
        String rv = atts.getValue(s);
        if (rv != null)
            rv = DataHelper.stripHTML(rv);
        return rv;
    }
}
