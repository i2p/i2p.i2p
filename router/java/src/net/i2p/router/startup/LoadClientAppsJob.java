package net.i2p.router.startup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Run any client applications specified in the router.config.  If any clientApp
 * contains the config property ".onBoot=true" it'll be launched immediately, otherwise
 * it'll get queued up for starting 2 minutes later.
 *
 */
public class LoadClientAppsJob extends JobImpl {
    private Log _log;
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
        if (apps.size() <= 0) {
            _log.error("Warning - No client apps or router console configured - we are just a router");
            System.err.println("Warning - No client apps or router console configured - we are just a router");
            return;
        }
        for(int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = (ClientAppConfig) apps.get(i);
            if (app.disabled)
                continue;
            String argVal[] = parseArgs(app.args);
            if (app.delay == 0) {
                // run this guy now
                runClient(app.className, app.clientName, argVal, _log);
            } else {
                // wait before firing it up
                getContext().jobQueue().addJob(new DelayedRunClient(getContext(), app.className, app.clientName, argVal, app.delay));
            }
        }
    }
    private class DelayedRunClient extends JobImpl {
        private String _className;
        private String _clientName;
        private String _args[];
        public DelayedRunClient(RouterContext enclosingContext, String className, String clientName, String args[], long delay) {
            super(enclosingContext);
            _className = className;
            _clientName = clientName;
            _args = args;
            getTiming().setStartAfter(LoadClientAppsJob.this.getContext().clock().now() + delay);
        }
        public String getName() { return "Delayed client job"; }
        public void runJob() {
            runClient(_className, _clientName, _args, _log);
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
                        } else {
                            isQuoted = true;
                        }
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

    public static void runClient(String className, String clientName, String args[], Log log) {
        log.info("Loading up the client application " + clientName + ": " + className + " " + args);
        I2PThread t = new I2PThread(new RunApp(className, clientName, args, log));
        if (clientName == null) 
            clientName = className + " client";
        t.setName(clientName);
        t.setDaemon(true);
        t.start();
    }

    private final static class RunApp implements Runnable {
        private String _className;
        private String _appName;
        private String _args[];
        private Log _log;
        public RunApp(String className, String appName, String args[], Log log) { 
            _className = className; 
            _appName = appName;
            if (args == null)
                _args = new String[0];
            else
                _args = args;
            _log = log;
        }
        public void run() {
            try {
                Class cls = Class.forName(_className);
                Method method = cls.getMethod("main", new Class[] { String[].class });
                method.invoke(cls, new Object[] { _args });
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error starting up the client class " + _className, t);
            }
            _log.info("Done running client application " + _appName);
        }
    }

    public String getName() { return "Load up any client applications"; }
    
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
}
