package net.i2p.phttprelay;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

import net.i2p.util.Log;

abstract class PHTTPRelayServlet extends HttpServlet {
    private Log _log = new Log(getClass());
    protected String _baseDir;

    /* config params */
    /*public final static String PARAM_BASEDIR = "baseDir";*/
    public final static String ENV_BASEDIR = "phttpRelay.baseDir";
    
    /** match the clock fudge factor on the router, rather than importing the entire router cvs module */
    public final static long CLOCK_FUDGE_FACTOR = 1*60*1000;
       
    protected String buildURL(HttpServletRequest req, String path) {
	StringBuffer buf = new StringBuffer();
	buf.append(req.getScheme()).append("://");
	buf.append(req.getServerName()).append(":").append(req.getServerPort());
	buf.append(req.getContextPath());
	buf.append(path);
	log("URL built: " + buf.toString());
	return buf.toString();
    }
    
    protected File getIdentDir(String target) throws IOException {
	if ( (_baseDir == null) || (target == null) ) throw new IOException("dir not specified to deal with");
	File baseDir = new File(_baseDir);
	if (!baseDir.exists()) {
	    boolean created = baseDir.mkdirs();
	    log("Creating PHTTP Relay Base Directory: " + baseDir.getAbsolutePath() + " - ok? " + created);
	}
	File identDir = new File(baseDir, target);
	log("Ident dir: " + identDir.getAbsolutePath());
	return identDir;
    }
    
    public void init(ServletConfig config) throws ServletException {
	super.init(config);
	String dir = System.getProperty(ENV_BASEDIR);
	if (dir == null) {
	    _log.warn("Base directory for the polling http relay system not in the environment [" + ENV_BASEDIR +"]");
	    _log.warn("Setting the base directory to ./relayDir for " + getServletName());
	    _baseDir = ".relayDir";
	} else {
	    _baseDir = dir;
	    log("Loaded up " + getServletName() + " with base directory " + _baseDir);
	}
    }
    
    public void log(String msg) {
	_log.debug(msg);
    }
    public void log(String msg, Throwable t) {
	_log.debug(msg, t);
    }
}
