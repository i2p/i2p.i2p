package net.i2p.router.web;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.util.FileSuffixFilter;

import org.apache.tomcat.SimpleInstanceManager;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;


/**
 *  Add to the webapp classpath as specified in webapps.config.
 *  This allows us to reference classes that are not in the classpath
 *  specified in wrapper.config, since old installations have
 *  individual jars and not lib/*.jar specified in wrapper.config.
 *
 *  A sample line in webapps.config is:
 *     webapps.appname.classpath=foo.jar,$I2P/lib/bar.jar
 *  Unless $I2P is specified the path will be relative to $I2P/lib for
 *  webapps in the installation and appDir/plugins/appname/lib for plugins.
 *
 *  Sadly, setting Class-Path in MANIFEST.MF doesn't work for jetty wars.
 *  We could look there ourselves, or look for another properties file in the war,
 *  but let's just do it in webapps.config.
 *
 *  No, wac.addClassPath() does not work. For more info see:
 *
 *  http://servlets.com/archive/servlet/ReadMsg?msgId=511113&amp;listName=jetty-support
 *
 *  @since 0.7.12
 *  @author zzz
 */
public class WebAppConfiguration implements Configuration {

    private static final String CLASSPATH = ".classpath";

    /**
     *  This was the interface in Jetty 5, in Jetty 6 was configureClassLoader(),
     *  now it's configure()
     */
    private void configureClassPath(WebAppContext wac) throws Exception {
        String ctxPath = wac.getContextPath();
        //System.err.println("Configure Class Path " + ctxPath);
        if (ctxPath.equals("/"))
            return;
        String appName = ctxPath.substring(1);

/****
        if (ctxPath.equals("/susimail")) {
            // allow certain Jetty classes, restricted as of Jetty 7
            // See http://wiki.eclipse.org/Jetty/Reference/Jetty_Classloading
            //System.err.println("Allowing Jetty utils in classpath for " + appName);
            //System.err.println("System classes before: " + Arrays.toString(wac.getSystemClasses()));
            //System.err.println("Server classes before: " + Arrays.toString(wac.getServerClasses()));
            wac.addSystemClass("org.eclipse.jetty.http.");
            wac.addSystemClass("org.eclipse.jetty.io.");
            wac.addSystemClass("org.eclipse.jetty.util.");
            // org.eclipse.jetty.webapp.ClasspathPattern looks in-order, and
            // WebAppContext doesn't provide a remove method, so we must
            // convert to a list, remove the wildcard entry, add ours, then
            // add the wildcard back, then reset.
            List<String> classes = new ArrayList<String>(16);
            classes.addAll(Arrays.asList(wac.getServerClasses()));
            classes.remove("org.eclipse.jetty.");
            classes.add("-org.eclipse.jetty.http.");
            classes.add("-org.eclipse.jetty.io.");
            classes.add("-org.eclipse.jetty.util.");
            classes.add("org.eclipse.jetty.");
            wac.setServerClasses(classes.toArray(new String[classes.size()]));
            //System.err.println("System classes after:  " + Arrays.toString(wac.getSystemClasses()));
            //System.err.println("Server classes after:  " + Arrays.toString(wac.getServerClasses()));
        }
****/

        I2PAppContext i2pContext = I2PAppContext.getGlobalContext();
        File libDir = new File(i2pContext.getBaseDir(), "lib");
        // FIXME this only works if war is the same name as the plugin
        File pluginDir = new File(i2pContext.getConfigDir(),
                                        PluginStarter.PLUGIN_DIR + ctxPath);

        File dir = libDir;
        String cp;
/****
        if (ctxPath.equals("/susimail")) {
            // Ticket #957... don't know why...
            // Only really required if started manually, but we don't know that from here
            cp = "jetty-util.jar";
****/
        if (ctxPath.equals("/susidns")) {
            // Old installs don't have this in their wrapper.config classpath
            cp = "addressbook.jar";
        } else if (pluginDir.exists()) {
            File consoleDir = new File(pluginDir, "console");
            Properties props = RouterConsoleRunner.webAppProperties(consoleDir.getAbsolutePath());
            cp = props.getProperty(RouterConsoleRunner.PREFIX + appName + CLASSPATH);
            dir = pluginDir;
        } else {
            Properties props = RouterConsoleRunner.webAppProperties(i2pContext);
            cp = props.getProperty(RouterConsoleRunner.PREFIX + appName + CLASSPATH);
        }
        if (cp == null)
            return;
        StringTokenizer tok = new StringTokenizer(cp, " ,");
        StringBuilder buf = new StringBuilder();
        Set<URI> systemCP = getSystemClassPath(i2pContext);
        while (tok.hasMoreTokens()) {
            if (buf.length() > 0)
                buf.append(',');
            String elem = tok.nextToken().trim();
            String path;
            if (elem.startsWith("$I2P"))
                path = i2pContext.getBaseDir().getAbsolutePath() + elem.substring(4);
            else if (elem.startsWith("$PLUGIN"))
                path = dir.getAbsolutePath() + elem.substring(7);
            else
                path = dir.getAbsolutePath() + '/' + elem;
            // As of Jetty 6, we can't add dups to the class path, or
            // else it screws up statics
            // This is not a complete solution because the Windows no-wrapper classpath is set
            // by the launchi2p.jar (i2p.exe) manifest and is not detected below.
            // TODO: Add a classpath to the command line in i2pstandalone.xml?
            File jfile = new File(path);
            File jdir = jfile.getParentFile();
            if (systemCP.contains(jfile.toURI()) ||
                (jdir != null && systemCP.contains(jdir.toURI()))) {
                //System.err.println("Not adding " + path + " to classpath for " + appName + ", already in system classpath");
                // Ticket #957... don't know why...
                if (!ctxPath.equals("/susimail"))
                    continue;
            }
            //System.err.println("Adding " + path + " to classpath for " + appName);
            buf.append(path);
        }
        if (buf.length() <= 0)
            return;
        ClassLoader cl = wac.getClassLoader();
        if (cl != null && cl instanceof WebAppClassLoader) {
            WebAppClassLoader wacl = (WebAppClassLoader) cl;
            wacl.addClassPath(buf.toString());
        } else {
            // This was not working because the WebAppClassLoader already exists
            // and it calls getExtraClasspath in its constructor
            // Not sure why WACL already exists...
            wac.setExtraClasspath(buf.toString());
        }
    }

    /**
     * Convert URL to URI so there's no blocking equals(),
     * not that there's really any hostnames in here,
     * but keep findbugs happy.
     * @since 0.9
     */
    private static Set<URI> getSystemClassPath(I2PAppContext ctx) {
        Set<URI> rv = new HashSet<URI>(32);
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        if (loader instanceof URLClassLoader) {
            // through Java 8, not available in Java 9
            URLClassLoader urlClassLoader = (URLClassLoader) loader;
            URL urls[] = urlClassLoader.getURLs();
            for (int i = 0; i < urls.length; i++) {
                try {
                    rv.add(urls[i].toURI());
                } catch (URISyntaxException use) {}
            }
        } else {
            // Java 9 - assume everything in lib/ is in the classpath
            // except addressbook.jar
            File libDir = new File(ctx.getBaseDir(), "lib");
            File[] files = libDir.listFiles(new FileSuffixFilter(".jar"));
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    String name = files[i].getName();
                    if (!name.equals("addressbook.jar"))
                        rv.add(files[i].toURI());
                }
            }
        }
        return rv;
    }

    /** @since Jetty 7 */
    public void deconfigure(WebAppContext context) {}

    /** @since Jetty 7 */
    public void configure(WebAppContext context) throws Exception {
        configureClassPath(context);
        // do we just need one, in the ContextHandlerCollection, or one for each?
        // http://stackoverflow.com/questions/17529936/issues-while-using-jetty-embedded-to-handle-jsp-jasperexception-unable-to-com
        // https://github.com/jetty-project/embedded-jetty-jsp/blob/master/src/main/java/org/eclipse/jetty/demo/Main.java
        context.getServletContext().setAttribute("org.apache.tomcat.InstanceManager", new SimpleInstanceManager());
    }

    /** @since Jetty 7 */
    public void cloneConfigure(WebAppContext template, WebAppContext context) {
        // no state, nothing to be done
    }

    /** @since Jetty 7 */
    public void destroy(WebAppContext context) {}

    /** @since Jetty 7 */
    public void preConfigure(WebAppContext context) {}

    /** @since Jetty 7 */
    public void postConfigure(WebAppContext context) {}
}
