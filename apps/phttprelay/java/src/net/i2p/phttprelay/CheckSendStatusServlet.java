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

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Check the status of previous message delivery, returning either pending or
 * unknown, where pending means that particular message ID for that particular
 * target is still on the server, and unknown means it has either not been created
 * or it has been sent successfully.  It does this by sending HTTP 204 (NO CONTENT) 
 * for pending, and HTTP 404 (NOT FOUND) for unknown. <p />
 *
 * This servlet should be set up in web.xml as follows:
 *
 *  <servlet>
 *   <servlet-name>CheckSendStatus</servlet-name>
 *   <servlet-class>net.i2p.phttprelay.CheckSendStatusServlet</servlet-class>
 *   <init-param>
 *     <param-name>baseDir</param-name>
 *     <param-value>/usr/local/jetty/phttprelayDir</param-value>
 *   </init-param>
 *  </servlet>
 *
 * <servlet-mapping>
 *   <servlet-name>CheckSendStatus</servlet-name>
 *   <url-pattern>/phttpCheckSendStatus</url-pattern>
 * </servlet-mapping>
 *
 * baseDir is the directory under which registrants and their pending messages are stored
 *
 */
public class CheckSendStatusServlet extends PHTTPRelayServlet {
    /* URL parameters on the check */
    
    /** H(routerIdent).toBase64() of the target to receive the message */
    public final static String PARAM_SEND_TARGET = "target"; 
    /** msgId parameter */
    public final static String PARAM_MSG_ID = "msgId";

    public final static String PROP_STATUS = "status";
    public final static String STATUS_PENDING = "pending";
    public final static String STATUS_UNKNOWN = "unknown";
    
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
	String target = req.getParameter(PARAM_SEND_TARGET);
	String msgIdStr = req.getParameter(PARAM_MSG_ID);
	
	log("Checking status of [" + target + "] message [" + msgIdStr + "]");
	if (!isKnownMessage(target, msgIdStr)) {
	    log("Not known - its not pending");
	    notPending(req, resp);
	    return;
	} else {
	    log("Known - its still pending");
	    pending(req, resp);
	    return;
	}
    }
    
    private boolean isKnownMessage(String target, String msgId) throws IOException {
	if ( (target == null) || (target.trim().length() <= 0) ) return false;
	if ( (msgId == null) || (msgId.trim().length() <= 0) ) return false;
	File identDir = getIdentDir(target);
	if (identDir.exists()) {
	    File identFile = new File(identDir, "identity.dat");
	    if (identFile.exists()) {
		// known and valid (maybe we need to check the file format... naw, fuck it
		File msgFile = new File(identDir, "msg" + msgId + ".dat");
		if (msgFile.exists()) 
		    return true;
		else
		    return false;
	    } else {
		return false;
	    }
	} else {
	    return false;
	}
    }
    
    private void pending(HttpServletRequest req, HttpServletResponse resp) throws IOException {
	resp.setStatus(HttpServletResponse.SC_OK);
	ServletOutputStream out = resp.getOutputStream();
	StringBuffer buf = new StringBuffer();
	buf.append(PROP_STATUS).append('=').append(STATUS_PENDING).append('\n');
	out.write(buf.toString().getBytes());
	out.flush();
	out.close();
    }
    
    private void notPending(HttpServletRequest req, HttpServletResponse resp) throws IOException {
	resp.setStatus(HttpServletResponse.SC_OK);
	ServletOutputStream out = resp.getOutputStream();
	StringBuffer buf = new StringBuffer();
	buf.append(PROP_STATUS).append('=').append(STATUS_UNKNOWN).append('\n');
	out.write(buf.toString().getBytes());
	out.flush();
	out.close();
    }
}
