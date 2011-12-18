package net.i2p.router.startup;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

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
        List apps = ClientAppConfig.getClientApps(getContext());
        if (apps.isEmpty()) {
            _log.error("Warning - No client apps or router console configured - we are just a router");
            System.err.println("Warning - No client apps or router console configured - we are just a router");
            return;
        }
        for(int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = (ClientAppConfig) apps.get(i);
            if (app.disabled)
                continue;
            String argVal[] = parseArgs(app.args);
            if (app.delay <= 0) {
                // run this guy now
                runClient(app.className, app.clientName, argVal, _log);
            } else {
                // wait before firing it up
                getContext().jobQueue().addJob(new DelayedRunClient(getContext(), app.className, app.clientName, argVal, app.delay));
            }
        }
    }

    public static class DelayedRunClient extends JobImpl {
        private final String _className;
        private final String _clientName;
        private final String _args[];
        private final Log _log;
        private final ThreadGroup _threadGroup;
        private final ClassLoader _cl;

        public DelayedRunClient(RouterContext enclosingContext, String className, String clientName, String args[], long delay) {
            this(enclosingContext, className, clientName, args, delay, null, null);
        }
        
        public DelayedRunClient(RouterContext enclosingContext, String className, String clientName, String args[],
                                long delay, ThreadGroup threadGroup, ClassLoader cl) {
            super(enclosingContext);
            _className = className;
            _clientName = clientName;
            _args = args;
            _log = enclosingContext.logManager().getLog(LoadClientAppsJob.class);
            _threadGroup = threadGroup;
            _cl = cl;
            getTiming().setStartAfter(getContext().clock().now() + delay);
        }
        public String getName() { return "Delayed client job"; }
        public void runJob() {
            runClient(_className, _clientName, _args, _log, _threadGroup, _cl);
        }
    }
    
    public static String[] parseArgs(String args) {
        List argList = new ArrayList(4);
        if (args != null) {
            char data[] = args.toCharArray();
            StringBuilder buf = new StringBuilder(32);
            boolean isQuoted = false;
            for (int i = 0; i < data.length; i++) {
                switch (data[i]) {
                    case '\'':
                    case '\"':
                        if (isQuoted) {
                            String str = buf.toString().trim();
                            if (str.length() > 0)
                                argList.add(str);
                            buf = new StringBuilder(32);
                        }
                        isQuoted = !isQuoted;
                        break;
                    case ' ':
                    case '\t':
                        // whitespace - if we're in a quoted section, keep this as part of the quote,
                        // otherwise use it as a delim
                        if (isQuoted) {
                            buf.append(data[i]);
                        } else {
                            String str = buf.toString().trim();
                            if (str.length() > 0)
                                argList.add(str);
                            buf = new StringBuilder(32);
                        }
                        break;
                    default:
                        buf.append(data[i]);
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
        for (int i = 0; i < argList.size(); i++) 
            rv[i] = (String)argList.get(i);
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
     *
     *  @param clientName can be null
     *  @param args can be null
     *  @throws just about anything, caller would be wise to catch Throwable
     *  @since 0.7.13
     */
    public static void runClientInline(String className, String clientName, String args[], Log log) throws Exception {
        runClientInline(className, clientName, args, log, null);
    }

    /**
     *  Run client in this thread.
     *
     *  @param clientName can be null
     *  @param args can be null
     *  @param cl can be null
     *  @throws just about anything, caller would be wise to catch Throwable
     *  @since 0.7.14
     */
    public static void runClientInline(String className, String clientName, String args[],
                                       Log log, ClassLoader cl) throws Exception {
        if (log.shouldLog(Log.INFO))
            log.info("Loading up the client application " + clientName + ": " + className + " " + Arrays.toString(args));
        if (args == null)
            args = new String[0];
        Class cls = Class.forName(className, true, cl);
        Method method = cls.getMethod("main", new Class[] { String[].class });
        method.invoke(cls, new Object[] { args });
    }

    /**
     *  Run client in a new thread.
     *
     *  @param clientName can be null
     *  @param args can be null
     */
    public static void runClient(String className, String clientName, String args[], Log log) {
        runClient(className, clientName, args, log, null, null);
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
    public static void runClient(String className, String clientName, String args[], Log log,
                                 ThreadGroup threadGroup, ClassLoader cl) {
        if (log.shouldLog(Log.INFO))
            log.info("Loading up the client application " + clientName + ": " + className + " " + Arrays.toString(args));
        I2PThread t;
        if (threadGroup != null)
            t = new I2PThread(threadGroup, new RunApp(className, clientName, args, log, cl));
        else
            t = new I2PThread(new RunApp(className, clientName, args, log, cl));
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
        private final Log _log;
        private final ClassLoader _cl;

        public RunApp(String className, String appName, String args[], Log log, ClassLoader cl) { 
            _className = className; 
            _appName = appName;
            if (args == null)
                _args = new String[0];
            else
                _args = args;
            _log = log;
            if (cl == null)
                _cl = ClassLoader.getSystemClassLoader();
            else
                _cl = cl;
        }

        public void run() {
            try {
                Class cls = Class.forName(_className, true, _cl);
                Method method = cls.getMethod("main", new Class[] { String[].class });
                method.invoke(cls, new Object[] { _args });
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error starting up the client class " + _className, t);
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Done running client application " + _appName);
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
