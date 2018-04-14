package net.i2p.router.startup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.app.RouterApp;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Run any client applications specified in clients.config.  If any clientApp
 * contains the config property ".onBoot=true" it'll be launched immediately, otherwise
 * it'll get queued up for starting 2 minutes later.
 *
 */
public class LoadClientAppsJob extends JobImpl {
    private final Log _log;
    private static boolean _loaded = false;
    
    public LoadClientAppsJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(LoadClientAppsJob.class);
    }

    public void runJob() {
        synchronized (LoadClientAppsJob.class) {
            if (_loaded) return;
            _loaded = true;
        }
        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(getContext());
        if (apps.isEmpty()) {
            _log.logAlways(Log.WARN, "Warning - No client apps or router console configured - we are just a router");
            System.err.println("Warning - No client apps or router console configured - we are just a router");
            return;
        }
        for(int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = apps.get(i);
            if (app.disabled) {
                if ("net.i2p.router.web.RouterConsoleRunner".equals(app.className)) {
                    String s = "Warning - Router console is disabled. To enable,\n edit the file " +
                               ClientAppConfig.configFile(getContext()) +
                               ",\n change the line \"clientApp." + i + ".startOnLoad=false\"" +
                               " to \"clientApp." + i + ".startOnLoad=true\",\n and restart.";
                    _log.logAlways(Log.WARN, s);
                    System.err.println(s);
                }
                continue;
            }
            String argVal[] = parseArgs(app.args);
            if (app.delay <= 0) {
                // run this guy now
                runClient(app.className, app.clientName, argVal, getContext(), _log);
            } else {
                // wait before firing it up
                DelayedRunClient drc = new DelayedRunClient(getContext().simpleTimer2(), getContext(), app.className,
                                                            app.clientName, argVal);
                drc.schedule(app.delay);
            }
        }
    }

    /**
     *  Public for router console only, not for use by others, subject to change
     */
    public static class DelayedRunClient extends SimpleTimer2.TimedEvent {
        private final RouterContext _ctx;
        private final String _className;
        private final String _clientName;
        private final String _args[];
        private final Log _log;
        private final ThreadGroup _threadGroup;
        private final ClassLoader _cl;

        /** caller MUST call schedule() */
        public DelayedRunClient(SimpleTimer2 pool, RouterContext enclosingContext, String className,
                                String clientName, String args[]) {
            this(pool, enclosingContext, className, clientName, args, null, null);
        }
        
        /** caller MUST call schedule() */
        public DelayedRunClient(SimpleTimer2 pool, RouterContext enclosingContext, String className, String clientName,
                                String args[], ThreadGroup threadGroup, ClassLoader cl) {
            super(pool);
            _ctx = enclosingContext;
            _className = className;
            _clientName = clientName;
            _args = args;
            _log = enclosingContext.logManager().getLog(LoadClientAppsJob.class);
            _threadGroup = threadGroup;
            _cl = cl;
        }

        public void timeReached() {
            runClient(_className, _clientName, _args, _ctx, _log, _threadGroup, _cl);
        }
    }
    
    /**
     *  Parse arg string into an array of args.
     *  Spaces or tabs separate args.
     *  Args may be single- or double-quoted if they contain spaces or tabs.
     *  There is no provision for escaping quotes.
     *  A quoted string may not contain a quote of any kind.
     *
     *  @param args may be null
     *  @return non-null, 0-length if args is null
     */
    public static String[] parseArgs(String args) {
        List<String> argList = new ArrayList<String>(4);
        if (args != null) {
            StringBuilder buf = new StringBuilder(32);
            boolean isQuoted = false;
            for (int i = 0; i < args.length(); i++) {
                char c = args.charAt(i);
                switch (c) {
                    case '\'':
                    case '"':
                        if (isQuoted) {
                            String str = buf.toString().trim();
                            if (str.length() > 0)
                                argList.add(str);
                            buf.setLength(0);
                        }
                        isQuoted = !isQuoted;
                        break;
                    case ' ':
                    case '\t':
                        // whitespace - if we're in a quoted section, keep this as part of the quote,
                        // otherwise use it as a delim
                        if (isQuoted) {
                            buf.append(c);
                        } else {
                            String str = buf.toString().trim();
                            if (str.length() > 0)
                                argList.add(str);
                            buf.setLength(0);
                        }
                        break;
                    default:
                        buf.append(c);
                        break;
                }
            }
            if (buf.length() > 0) {
                String str = buf.toString().trim();
                if (str.length() > 0)
                    argList.add(str);
            }
        }
        String rv[] = new String[argList.size()];
        for (int i = 0; i < argList.size(); i++) {
            rv[i] = argList.get(i);
        }
        return rv;
    }

    /**
     *  Use to test if the class is present,
     *  to propagate an error back to the user,
     *  since runClient() runs in a separate thread.
     *
     *  @param cl can be null
     *  @since 0.7.13
     */
    public static void testClient(String className, ClassLoader cl) throws ClassNotFoundException {
        if (cl == null)
            cl = ClassLoader.getSystemClassLoader();
        Class.forName(className, false, cl);
    }

    /**
     *  Run client in this thread.
     *  Used for plugin sub-clients only. Does not register with the ClientAppManager.
     *
     *  @param clientName can be null
     *  @param args can be null
     *  @throws Exception just about anything, caller would be wise to catch Throwable
     *  @since 0.7.13
     */
    public static void runClientInline(String className, String clientName, String args[], Log log) throws Exception {
        runClientInline(className, clientName, args, log, null);
    }

    /**
     *  Run client in this thread.
     *  Used for plugin sub-clients only. Does not register with the ClientAppManager.
     *
     *  @param clientName can be null
     *  @param args can be null
     *  @param cl can be null
     *  @throws Exception just about anything, caller would be wise to catch Throwable
     *  @since 0.7.14
     */
    public static void runClientInline(String className, String clientName, String args[],
                                       Log log, ClassLoader cl) throws Exception {
        if (log.shouldLog(Log.INFO))
            log.info("Loading up the client application " + clientName + ": " + className + " " + Arrays.toString(args));
        if (args == null)
            args = new String[0];
        Class<?> cls = Class.forName(className, true, cl);
        Method method = cls.getMethod("main", String[].class);
        method.invoke(cls, new Object[] { args });
    }

    /**
     *  Run client in a new thread.
     *
     *  @param clientName can be null
     *  @param args can be null
     */
    public static void runClient(String className, String clientName, String args[], RouterContext ctx, Log log) {
        runClient(className, clientName, args, ctx, log, null, null);
    }
    
    /**
     *  Run client in a new thread.
     *
     *  @param clientName can be null
     *  @param args can be null
     *  @param threadGroup can be null
     *  @param cl can be null
     *  @since 0.7.13
     */
    public static void runClient(String className, String clientName, String args[], RouterContext ctx, Log log,
                                 ThreadGroup threadGroup, ClassLoader cl) {
        if (log.shouldLog(Log.INFO))
            log.info("Loading up the client application " + clientName + ": " + className + " " + Arrays.toString(args));
        I2PThread t;
        if (threadGroup != null)
            t = new I2PThread(threadGroup, new RunApp(className, clientName, args, ctx, log, cl));
        else
            t = new I2PThread(new RunApp(className, clientName, args, ctx, log, cl));
        if (clientName == null) 
            clientName = className + " client";
        t.setName(clientName);
        t.setDaemon(true);
        if (cl != null)
            t.setContextClassLoader(cl);
        t.start();
    }

    private final static class RunApp implements Runnable {
        private final String _className;
        private final String _appName;
        private final String _args[];
        private final RouterContext _ctx;
        private final Log _log;
        private final ClassLoader _cl;

        public RunApp(String className, String appName, String args[], RouterContext ctx, Log log, ClassLoader cl) { 
            _className = className; 
            _appName = appName;
            if (args == null)
                _args = new String[0];
            else
                _args = args;
            _ctx = ctx;
            _log = log;
            if (cl == null)
                _cl = ClassLoader.getSystemClassLoader();
            else
                _cl = cl;
        }

        public void run() {
            try {
                Class<?> cls = Class.forName(_className, true, _cl);
                if (isRouterApp(cls)) {
                    Constructor<?> con = cls.getConstructor(RouterContext.class, ClientAppManager.class, String[].class);
                    RouterAppManager mgr = _ctx.routerAppManager();
                    Object[] conArgs = new Object[] {_ctx, _ctx.clientAppManager(), _args};
                    RouterApp app = (RouterApp) con.newInstance(conArgs);
                    mgr.addAndStart(app, _args);
                } else if (isClientApp(cls)) {
                    Constructor<?> con = cls.getConstructor(I2PAppContext.class, ClientAppManager.class, String[].class);
                    RouterAppManager mgr = _ctx.routerAppManager();
                    Object[] conArgs = new Object[] {_ctx, _ctx.clientAppManager(), _args};
                    ClientApp app = (ClientApp) con.newInstance(conArgs);
                    mgr.addAndStart(app, _args);
                } else {
                    Method method = cls.getMethod("main", String[].class);
                    method.invoke(cls, new Object[] { _args });
                }
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error starting up the client class " + _className, t);
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Done running client application " + _appName);
        }

        private static boolean isRouterApp(Class<?> cls) {
            return isInterface(cls, RouterApp.class);
        }

        private static boolean isClientApp(Class<?> cls) {
            return isInterface(cls, ClientApp.class);
        }

        private static boolean isInterface(Class<?> cls, Class<?> intfc) {
            try {
                Class<?>[] intfcs = cls.getInterfaces();
                for (int i = 0; i < intfcs.length; i++) {
                    if (intfcs[i] == intfc)
                        return true;
                }
            } catch (Throwable t) {}
            return false;
        }
    }

    public String getName() { return "Load up any client applications"; }
    
/****
    public static void main(String args[]) {
        test(null);
        test("hi how are you?");
        test("hi how are you? ");
        test(" hi how are you? ");
        test(" hi how are \"y\"ou? ");
        test("-nogui -e \"config localhost 17654\" -e \"httpclient 4544\"");
        test("-nogui -e 'config localhost 17654' -e 'httpclient 4544'");
    }
    private static void test(String args) {
        String parsed[] = parseArgs(args);
        System.out.print("Parsed [" + args + "] into " + parsed.length + " elements: ");
        for (int i = 0; i < parsed.length; i++)
            System.out.print("[" + parsed[i] + "] ");
        System.out.println();
    }
****/
}
