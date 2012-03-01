package net.i2p.router.web;

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
import net.i2p.util.FileUtil;

/**
 *  Dump info on jars and wars
 *
 *  @since 0.8.13
 */
public class FileDumpHelper extends HelperBase {
    
    public String getFileSummary() {
        StringBuilder buf = new StringBuilder(16*1024);
        buf.append("<table><tr><th>File</th><th>Size</th><th>Date</th><th>SHA 256</th><th>Revision</th>" +
                   "<th>JDK</th><th>Built</th><th>By</th><th>Mods</th></tr>");

        // jars added in wrapper.config
        URLClassLoader urlClassLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        URL[] urls = urlClassLoader.getURLs();
        List<File> flist = new ArrayList();
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

        // our jars
        File dir = new File(_context.getBaseDir(), "lib");
        dumpDir(buf, dir, ".jar");

        // our wars
        dir = new File(_context.getBaseDir(), "webapps");
        dumpDir(buf, dir, ".war");

        // plugins
        File pluginDir = new File(_context.getConfigDir(), PluginUpdateHandler.PLUGIN_DIR);
        File[] files = pluginDir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (int i = 0; i < files.length; i++) {
                dir = new File(files[i], "lib");
                dumpDir(buf, dir, ".jar");
                dir = new File(files[i], "console/webapps");
                dumpDir(buf, dir, ".war");
            }
        }

        buf.append("</table>");
        return buf.toString();
    }

    private static void dumpDir(StringBuilder buf, File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        Arrays.sort(files);
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().endsWith(suffix))
                dumpFile(buf, files[i]);
        }
    }

    private static void dumpFile(StringBuilder buf, File f) {
        buf.append("<tr><td><b>").append(f.getAbsolutePath()).append("</b></td>" +
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
            buf.append("<tt>");
            String p1 = DataHelper.toHexString(hh);
            for (int i = p1.length(); i < 32; i++) {
                buf.append('0');
            }
            buf.append(p1).append("</tt><br>");
            System.arraycopy(hash, 16, hh, 0, 16);
            buf.append("<tt>").append(DataHelper.toHexString(hh)).append("</tt>");
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
            buf.append("<a href=\"http://stats.i2p/cgi-bin/viewmtn/revision/info/").append(s)
               .append("\">" +
                       "<tt>").append(s.substring(0, 20)).append("</tt>" +
                       "<br>" +
                       "<tt>").append(s.substring(20)).append("</tt></a>");
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
        buf.append("</td><td><font color=\"red\">");
        s = getAtt(att, "Workspace-Changes");
        if (s != null)
            buf.append(s.replace(",", "<br>"));
        buf.append("</font></td>");
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

    private static Attributes attributes(File f) {
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

    private static String getAtt(Attributes atts, String s) {
        String rv = atts.getValue(s);
        if (rv != null)
            rv = DataHelper.stripHTML(rv);
        return rv;
    }
}
