package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;

public class GenerateStatusConsoleJob extends JobImpl {
    private final static Log _log = new Log(GenerateStatusConsoleJob.class);
    
    private final static long REGENERATE_DELAY_MS = 60*1000; // once per minute update the console
    public final static String CONFIG_CONSOLE_LOCATION = "routerConsoleFile";
    public final static String DEFAULT_CONSOLE_LOCATION = "routerConsole.html";
    
    public final static String PARAM_GENERATE_CONFIG_CONSOLE = "router.generateConsole";
    public final static boolean DEFAULT_GENERATE_CONFIG_CONSOLE = true;
    
    private boolean shouldGenerateConsole() {
	String str = Router.getInstance().getConfigSetting(PARAM_GENERATE_CONFIG_CONSOLE);
	if ( (str == null) || (str.trim().length() <= 0) )
	    return DEFAULT_GENERATE_CONFIG_CONSOLE;
	if (Boolean.TRUE.toString().equalsIgnoreCase(str))
	    return true;
	else
	    return false;
    }
    
    public String getName() { return "Generate Status Console"; }
    public void runJob() { 
	if (shouldGenerateConsole()) {
	    String consoleHTML = Router.getInstance().renderStatusHTML();
	    writeConsole(consoleHTML);
	}
	requeue(REGENERATE_DELAY_MS);
    }
    
    private void writeConsole(String html) {
	String loc = Router.getInstance().getConfigSetting(CONFIG_CONSOLE_LOCATION);
	if (loc == null)
	    loc = DEFAULT_CONSOLE_LOCATION;
	
	FileOutputStream fos = null;
	try {
	    fos = new FileOutputStream(loc);
	    fos.write(html.getBytes());
	    fos.flush();
	} catch (IOException ioe) {
	    _log.error("Error writing out the console", ioe);
	} finally {
	    if (fos != null) try { fos.close(); } catch (IOException ioe) {} 
	}
    }
    
}
