package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;
import net.i2p.util.PortMapper;
import net.i2p.util.SecureDirectory;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.webapp.WebAppContext;


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
    //static private Log _log;

    static {
        //_log = ContextHelper.getContext(null).logManager().getLog(WebAppStarter.class); ;
        // see DefaultServlet javadocs
        String pfx = "org.eclipse.jetty.servlet.Default.";
        INIT_PARAMS.put(pfx + "cacheControl", "max-age=86400");
        INIT_PARAMS.put(pfx + "dirAllowed", "false");
    }


    /**
     *  Adds and starts.
     *  Prior to 0.9.28, was not guaranteed to throw on failure.
     *  Not for routerconsole.war, it's started in RouterConsoleRunner.
     *
     *  As of 0.9.34, the appName will be registered with the PortMapper.
     *
     *  @throws Exception just about anything, caller would be wise to catch Throwable
     *  @since public since 0.9.33, was package private
     */
    public static void startWebApp(RouterContext ctx, ContextHandlerCollection server,
                            String appName, String warPath) throws Exception {
         File tmpdir = new SecureDirectory(ctx.getTempDir(), "jetty-work-" + appName + ctx.random().nextInt());
         WebAppContext wac = addWebApp(ctx, server, appName, warPath, tmpdir);      
         //_log.debug("Loading war from: " + warPath);
         LocaleWebAppHandler.setInitParams(wac, INIT_PARAMS);
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
        wac.setExtractWAR(false);

        // this does the passwords...
        RouterConsoleRunner.initialize(ctx, wac);
        setWebAppConfiguration(wac);
        server.addHandler(wac);
        server.mapContexts();
        return wac;
    }

    /**
     *  @since Jetty 9
     */
    static void setWebAppConfiguration(WebAppContext wac) {
        // see WebAppConfiguration for info
        String[] classNames = wac.getConfigurationClasses();
        // In Jetty 9, it doesn't set the defaults if we've already added one, but the
        // defaults aren't set yet when we call the above. So we have to get the defaults.
        // Without the default configuration, the web.xml isn't read, and the webapp
        // won't respond to any requests, even though it appears to be running.
        // See WebAppContext.loadConfigurations() in source
        if (classNames.length == 0)
            classNames = wac.getDefaultConfigurationClasses();
        String[] newClassNames = new String[classNames.length + 1];
        for (int j = 0; j < classNames.length; j++)
             newClassNames[j] = classNames[j];
        newClassNames[classNames.length] = WebAppConfiguration.class.getName();
        wac.setConfigurationClasses(newClassNames);
    }

    /**
     *  Stop it and remove the context.
     *  Throws just about anything, caller would be wise to catch Throwable
     *  @since public since 0.9.33, was package private
     */
    public static void stopWebApp(RouterContext ctx, String appName) {
        ContextHandler wac = getWebApp(appName);
        if (wac == null)
            return;
        ctx.portMapper().unregister(appName);
        try {
            // not graceful is default in Jetty 6?
            wac.stop();
        } catch (Exception ie) {}
        ContextHandlerCollection server = getConsoleServer();
        if (server == null)
            return;
        try {
            server.removeHandler(wac);
            server.mapContexts();
        } catch (IllegalStateException ise) {}
    }

    /**
     *  As of 0.9.34, the appName will be registered with the PortMapper,
     *  and PortMapper.isRegistered() will be more efficient than this.
     *
     *  @since public since 0.9.33; was package private
     */
    public static boolean isWebAppRunning(String appName) {
        ContextHandler wac = getWebApp(appName);
        if (wac == null)
            return false;
        return wac.isStarted();
    }
    
    /** @since Jetty 6 */
    static ContextHandler getWebApp(String appName) {
        ContextHandlerCollection server = getConsoleServer();
        if (server == null)
            return null;
        Handler handlers[] = server.getHandlers();
        if (handlers == null)
            return null;
        String path = '/'+ appName;
        for (int i = 0; i < handlers.length; i++) {
            if (!(handlers[i] instanceof ContextHandler))
                continue;
            ContextHandler ch = (ContextHandler) handlers[i];
            if (path.equals(ch.getContextPath()))
                return ch;
        }
        return null;
    }

    /**
     *  See comments in ConfigClientsHandler
     *  @since public since 0.9.33, was package private
     */
    public static ContextHandlerCollection getConsoleServer() {
        Server s = RouterConsoleRunner.getConsoleServer();
        if (s == null)
            return null;
        Handler h = s.getChildHandlerByClass(ContextHandlerCollection.class);
        if (h == null)
            return null;
        return (ContextHandlerCollection) h;
    }
}
