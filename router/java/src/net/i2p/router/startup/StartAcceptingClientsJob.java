package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;

import net.i2p.router.ClientManagerFacade;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.router.admin.AdminManager;
import net.i2p.util.Log;
import net.i2p.util.I2PThread;
import net.i2p.util.Clock;

public class StartAcceptingClientsJob extends JobImpl {
    private static Log _log = new Log(StartAcceptingClientsJob.class);
    
    public StartAcceptingClientsJob() { }
    
    public String getName() { return "Start Accepting Clients"; }
    
    public void runJob() {
	// start up the network database
	
	ClientManagerFacade.getInstance().startup();
	
	JobQueue.getInstance().addJob(new ReadConfigJob());
	JobQueue.getInstance().addJob(new RebuildRouterInfoJob());
	AdminManager.getInstance().startup();
	JobQueue.getInstance().allowParallelOperation();
	JobQueue.getInstance().addJob(new LoadClientAppsJob());
    }
    
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
	String parsed[] = LoadClientAppsJob.parseArgs(args);
	System.out.print("Parsed [" + args + "] into " + parsed.length + " elements: ");
	for (int i = 0; i < parsed.length; i++)
	    System.out.print("[" + parsed[i] + "] ");
	System.out.println();
    }
}

class LoadClientAppsJob extends JobImpl {
    private final static Log _log = new Log(LoadClientAppsJob.class);
    /** wait a minute before starting up client apps */
    private final static long STARTUP_DELAY = 60*1000;
    public LoadClientAppsJob() {
	super();
	getTiming().setStartAfter(STARTUP_DELAY + Clock.getInstance().now());
    }
    public void runJob() {
	int i = 0;
	while (true) {
	    String className = Router.getInstance().getConfigSetting("clientApp."+i+".main");
	    String clientName = Router.getInstance().getConfigSetting("clientApp."+i+".name");
	    String args = Router.getInstance().getConfigSetting("clientApp."+i+".args");
	    if (className == null) break;
	    
	    String argVal[] = parseArgs(args);
	    _log.info("Loading up the client application " + clientName + ": " + className + " " + args);
	    runClient(className, clientName, argVal);
	    i++;
	}
    }
    
    static String[] parseArgs(String args) {
	List argList = new ArrayList(4);
	if (args != null) {
	    char data[] = args.toCharArray();
	    StringBuffer buf = new StringBuffer(32);
	    boolean isQuoted = false;
	    for (int i = 0; i < data.length; i++) {
		switch (data[i]) {
		    case '\'':
		    case '\"':
			if (isQuoted) {
			    String str = buf.toString().trim();
			    if (str.length() > 0)
				argList.add(str);
			    buf = new StringBuffer(32);
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
			    buf = new StringBuffer(32);
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
    
    private void runClient(String className, String clientName, String args[]) {
	I2PThread t = new I2PThread(new RunApp(className, clientName, args));
	t.setName(clientName);
	t.setDaemon(true);
	t.start();
    }
    
    private final static class RunApp implements Runnable {
	private String _className;
	private String _appName;
	private String _args[];
	public RunApp(String className, String appName, String args[]) { 
	    _className = className; 
	    _appName = appName;
	    if (args == null)
		_args = new String[0];
	    else
		_args = args;
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
}
