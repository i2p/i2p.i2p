package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;
import net.i2p.util.PortMapper;
import net.i2p.util.SecureDirectory;

import org.eclipse.jetty.ee.WebAppClassLoading;
import org.eclipse.jetty.ee8.webapp.WebAppContext;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;


/**
 *  Add, start or stop a webapp.
 *  Add to the webapp classpath if specified in webapps.config.
 *
 *  Sadly, setting Class-Path in MANIFEST.MF doesn't work for jetty wars.
 *  See WebAppConfiguration for more information.
 *  but let's just do it in webapps.config.
 *
 *  No, wac.addClassPath() does not work. For more info see:
 *
 *  http://servlets.com/archive/servlet/ReadMsg?msgId=511113&amp;listName=jetty-support
 *
 *  @since 0.7.12
 *  @author zzz
 */
public class WebAppStarter {

    private static final Map<String, Long> warModTimes = new ConcurrentHashMap<String, Long>();
    static final Map<String, String> INIT_PARAMS = new HashMap<String, String>(4);
    static final String PARAM_PLUGIN_NAME = "net.i2p.router.web.WebAppStarter.PLUGIN_NAME";

    // There are 4 additional jars that are required to do the Servlet 3.0 annotation scanning.
    // The following 4 classes were the first to get thrown as not found, for each jar.
    // So use them to see if we have the 4 jars.
    // jetty-annotations.jar
    private static final String CLASS_ANNOT = "org.eclipse.jetty.annotations.AnnotationConfiguration";
    // jetty-plus.jar
    private static final String CLASS_ANNOT2 = "org.eclipse.jetty.plus.annotation.LifeCycleCallback";
    // asm.jar
    private static final String CLASS_ANNOT3 = "org.objectweb.asm.Type";
    // javax-annotations-api.jar
    private static final String CLASS_ANNOT4 = "javax.annotation.security.RunAs";

    private static final String CLASS_CONFIG = "org.eclipse.jetty.ee8.webapp.JettyWebXmlConfiguration";

    private static final boolean HAS_ANNOTATION_CLASSES;
    private static final Set<String> BUILTINS = new HashSet<String>(8);

    static {
        //_log = ContextHelper.getContext(null).logManager().getLog(WebAppStarter.class); ;
        // see DefaultServlet javadocs
        String pfx = "org.eclipse.jetty.servlet.Default.";
        INIT_PARAMS.put(pfx + "cacheControl", "max-age=86400");
        INIT_PARAMS.put(pfx + "dirAllowed", "false");

        boolean found = false;
        try {
            Class<?> cls = Class.forName(CLASS_ANNOT, false, ClassLoader.getSystemClassLoader());
            cls = Class.forName(CLASS_ANNOT2, false, ClassLoader.getSystemClassLoader());
            cls = Class.forName(CLASS_ANNOT3, false, ClassLoader.getSystemClassLoader());
            cls = Class.forName(CLASS_ANNOT4, false, ClassLoader.getSystemClassLoader());
            found = true;
        } catch (Exception e) {}
        HAS_ANNOTATION_CLASSES = found;

        // don't scan these wars
        BUILTINS.addAll(Arrays.asList(new String[] {"i2psnark", "i2ptunnel", "imagegen", "jsonrpc",
                                                    "routerconsole", "susidns", "susimail"} ));
    }


    /**
     *  Adds and starts.
     *  Prior to 0.9.28, was not guaranteed to throw on failure.
     *  Not for routerconsole.war, it's started in RouterConsoleRunner.
     *  Not for plugins, use 5-arg method.
     *
     *  As of 0.9.34, the appName will be registered with the PortMapper.
     *
     *  @throws Exception just about anything, caller would be wise to catch Throwable
     *  @since public since 0.9.33, was package private
     */
    public static void startWebApp(RouterContext ctx, ContextHandlerCollection server,
                            String appName, String warPath) throws Exception {
        startWebApp(ctx, server, appName, warPath, null);
    }

    /**
     *  Adds and starts.
     *  Not for routerconsole.war, it's started in RouterConsoleRunner.
     *
     *  The appName will be registered with the PortMapper.
     *
     *  @param pluginName may be null, will look for console/webapps.config in that plugin
     *  @throws Exception just about anything, caller would be wise to catch Throwable
     *  @since 0.9.53 added pluginName param
     */
    public static void startWebApp(RouterContext ctx, ContextHandlerCollection server,
                                   String appName, String warPath, String pluginName) throws Exception {
         File tmpdir = new SecureDirectory(ctx.getTempDir(), "jetty-work-" + appName + ctx.random().nextInt());
         WebAppContext wac = addWebApp(ctx, server, appName, warPath, tmpdir);      
         //_log.debug("Loading war from: " + warPath);
         LocaleWebAppHandler.setInitParams(wac, INIT_PARAMS);
         // save plugin name so WebAppConfiguration can find it
         if (pluginName != null)
             wac.setInitParameter(PARAM_PLUGIN_NAME, pluginName);
         // default false, set to true so we get good logging,
         // and the caller will know it failed
         wac.setThrowUnavailableOnStartupException(true);
         wac.start();
         // Doesn't have to be right, just for presence indication
         int port = ctx.portMapper().getPort(PortMapper.SVC_CONSOLE, PortMapper.DEFAULT_CONSOLE_PORT);
         String host = ctx.portMapper().getActualHost(PortMapper.SVC_CONSOLE, "127.0.0.1");
         ctx.portMapper().register(appName, host, port);
    }

    /**
     *  add but don't start
     *  This is used only by RouterConsoleRunner, which adds all the webapps first
     *  and then starts all at once.
     */
    static WebAppContext addWebApp(RouterContext ctx, ContextHandlerCollection server,
                                   String appName, String warPath, File tmpdir) throws IOException {

        // Jetty will happily load one context on top of another without stopping
        // the first one, so we remove any previous one here
        try {
            stopWebApp(ctx, appName);
        } catch (Throwable t) {}

        // To avoid ZipErrors from JarURLConnetion caching,
        // (used by Jetty JarResource and JarFileResource)
        // copy the war to a new directory if it is newer than the one we loaded originally.
        // Yes, URLConnection has a setDefaultUseCaches() method, but it's hard to get to
        // because it's non-static and the class is abstract, and we don't really want to
        // set the default to false for everything.
        long newmod = (new File(warPath)).lastModified();
        if (newmod <= 0)
            throw new IOException("Web app " + warPath + " does not exist");
        Long oldmod = warModTimes.get(warPath);
        if (oldmod == null) {
            warModTimes.put(warPath, Long.valueOf(newmod));
        } else if (oldmod.longValue() < newmod) {
            // copy war to temporary directory
            File warTmpDir = new SecureDirectory(ctx.getTempDir(), "war-copy-" + appName + ctx.random().nextInt());
            warTmpDir.mkdir();
            String tmpPath = (new File(warTmpDir, appName + ".war")).getAbsolutePath();
            if (!FileUtil.copy(warPath, tmpPath, true))
                throw new IOException("Web app failed copy from " + warPath + " to " + tmpPath);
            warPath = tmpPath;
        }

        WebAppContext wac = new WebAppContext(warPath, "/"+ appName);
        tmpdir.mkdir();
        wac.setTempDirectory(tmpdir);
        // all the JSPs are precompiled, no need to extract
        // UNLESS it's a plugin and we want to scan it for annotations.
        // We do not use Servlet 3.0 for any built-in wars.
        // Jetty bug - annotation scanning fails unless we extract the war:
        // org.eclipse.jetty.server.Server: Skipping scan on invalid file jar:file:/home/.../i2p/webapps/routerconsole.war!/WEB-INF/classes/net/i2p/router/web/servlets/CodedIconRendererServlet.class
        // See AnnotationParser.isValidClassFileName()
        // Server must be at DEBUG level to see what's happening
        boolean scanAnnotations = HAS_ANNOTATION_CLASSES && !BUILTINS.contains(appName);
        //System.out.println("Scanning " + appName + " for annotations? " + scanAnnotations);
        wac.setExtractWAR(scanAnnotations);

        // this does the passwords...
        RouterConsoleRunner.initialize(ctx, wac);
        setWebAppConfiguration(wac, scanAnnotations);
        server.addHandler(wac);
        server.mapContexts();
        return wac;
    }

    /**
     *  @param scanAnnotations Should we check for Servlet 3.0 annotations?
     *                         The war MUST be set to extract (due to Jetty bug),
     *                         and annotation classes MUST be available
     *  @since Jetty 9
     */
    static void setWebAppConfiguration(WebAppContext wac, boolean scanAnnotations) {
        // see WebAppConfiguration for info
        String[] classNames = wac.getConfigurationClasses();
        // In Jetty 9, it doesn't set the defaults if we've already added one, but the
        // defaults aren't set yet when we call the above. So we have to get the defaults.
        // Without the default configuration, the web.xml isn't read, and the webapp
        // won't respond to any requests, even though it appears to be running.
        // See WebAppContext.loadConfigurations() in source
        if (classNames.length == 0) {
            //classNames = wac.getDefaultConfigurationClasses();
            // These are the defaults as documented in WebAppContext
            classNames = new String[] { "org.eclipse.jetty.ee8.webapp.WebXMLConfiguration", "org.eclipse.jetty.ee8.webapp.JettyWebXMLConfiguration" };
        }
        List<String> newClassNames = new ArrayList<String>(Arrays.asList(classNames));
        for (String name : newClassNames) {
             // fix for Jetty 9.4 ticket #2385
             WebAppClassLoading.addHiddenClasses(wac, name);
        }
        // https://www.eclipse.org/jetty/documentation/current/using-annotations.html
        // https://www.eclipse.org/jetty/documentation/9.4.x/using-annotations-embedded.html
        if (scanAnnotations) {
            if (!newClassNames.contains(CLASS_ANNOT)) {
                int idx = newClassNames.indexOf(CLASS_CONFIG);
                if (idx >= 0)
                    newClassNames.add(idx, CLASS_ANNOT);
                else
                    newClassNames.add(CLASS_ANNOT);
            }
        }
        newClassNames.add(WebAppConfiguration.class.getName());
        wac.setConfigurationClasses(newClassNames.toArray(new String[newClassNames.size()]));
    }

    /**
     *  Stop it and remove the context.
     *  Throws just about anything, caller would be wise to catch Throwable
     *
     *  Warning, this will NOT work during shutdown, because
     *  the console is already unregistered.
     *
     *  @since public since 0.9.33, was package private
     */
    public static void stopWebApp(RouterContext ctx, String appName) {
        ContextHandler wac = getWebApp(ctx, appName);
        if (wac == null)
            return;
        ctx.portMapper().unregister(appName);
        try {
            // not graceful is default in Jetty 6?
            wac.stop();
        } catch (Exception ie) {}
        ContextHandlerCollection server = getConsoleServer(ctx);
        if (server == null)
            return;
        try {
            server.removeHandler(wac);
            server.mapContexts();
        } catch (IllegalStateException ise) {}
    }

    /**
     *  Stop it and remove the context.
     *  Throws just about anything, caller would be wise to catch Throwable
     *  @since 0.9.41
     */
    static void stopWebApp(RouterContext ctx, Server s, String appName) {
        ContextHandlerCollection server = getConsoleServer(s);
        if (server == null)
            return;
        ContextHandler wac = getWebApp(server, appName);
        if (wac == null)
            return;
        ctx.portMapper().unregister(appName);
        try {
            // not graceful is default in Jetty 6?
            wac.stop();
        } catch (Exception ie) {}
        try {
            server.removeHandler(wac);
            server.mapContexts();
        } catch (IllegalStateException ise) {}
    }

    /**
     *  As of 0.9.34, the appName will be registered with the PortMapper,
     *  and PortMapper.isRegistered() will be more efficient than this.
     *
     *  Warning, this will NOT work during shutdown, because
     *  the console is already unregistered.
     *
     *  @since public since 0.9.33; was package private
     */
    public static boolean isWebAppRunning(I2PAppContext ctx, String appName) {
        ContextHandler wac = getWebApp(ctx, appName);
        if (wac == null)
            return false;
        return wac.isStarted();
    }
    
    /**
     *  @since 0.9.41
     */
    static boolean isWebAppRunning(Server s, String appName) {
        ContextHandler wac = getWebApp(s, appName);
        if (wac == null)
            return false;
        return wac.isStarted();
    }
    
    /**
     *  Warning, this will NOT work during shutdown, because
     *  the console is already unregistered.
     *
     *  @since Jetty 6
     */
    static ContextHandler getWebApp(I2PAppContext ctx, String appName) {
        ContextHandlerCollection server = getConsoleServer(ctx);
        if (server == null)
            return null;
        return getWebApp(server, appName);
    }
    
    /**
     *  @since 0.9.41
     */
    static ContextHandler getWebApp(Server s, String appName) {
        ContextHandlerCollection server = getConsoleServer(s);
        if (server == null)
            return null;
        return getWebApp(server, appName);
    }
    
    /**
     *  @since 0.9.41
     */
    private static ContextHandler getWebApp(ContextHandlerCollection server, String appName) {
        List<Handler> handlers = server.getHandlers();
        if (handlers == null || handlers.isEmpty())
            return null;
        String path = '/'+ appName;
        for (Handler h : handlers) {
            if (!(h instanceof ContextHandler))
                continue;
            ContextHandler ch = (ContextHandler) h;
            if (path.equals(ch.getContextPath()))
                return ch;
        }
        return null;
    }

    /**
     *  See comments in ConfigClientsHandler
     *
     *  Warning, this will NOT work during shutdown, because
     *  the console is already unregistered.
     *
     *  @since public since 0.9.33, was package private
     */
    public static ContextHandlerCollection getConsoleServer(I2PAppContext ctx) {
        Server s = RouterConsoleRunner.getConsoleServer(ctx);
        if (s == null)
            return null;
        return getConsoleServer(s);
    }

    /**
     *  @since 0.9.41
     */
    private static ContextHandlerCollection getConsoleServer(Server s) {
        ContextHandlerCollection h = s.getDescendant(ContextHandlerCollection.class);
        return h;
    }
}
