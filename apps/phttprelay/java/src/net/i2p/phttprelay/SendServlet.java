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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Accept messages for PHTTP relaying, allowing the Polling HTTP (PHTTP) 
 * transport for I2P to bridge past firewalls, NATs, and proxy servers.  This
 * delivers them into the queue, returning HTTP 201 (created) if the queue is
 * known, as well as the URL at which requests can be made to check the delivery
 * status of the message.  If the queue is not known, HTTP 410 (resource gone) is
 * sent back.  <p />
 *
 * This servlet should be set up in web.xml as follows:
 *
 *  <servlet>
 *   <servlet-name>Send</servlet-name>
 *   <servlet-class>net.i2p.phttprelay.SendServlet</servlet-class>
 *   <init-param>
 *     <param-name>baseDir</param-name>
 *     <param-value>/usr/local/jetty/phttprelayDir</param-value>
 *   </init-param>
 *   <init-param>
 *     <param-name>checkPath</param-name>
 *     <param-value>phttpCheckStatus</param-value>
 *   </init-param>
 *   <init-param>
 *     <param-name>maxMessagesPerIdent</param-name>
 *     <param-value>100</param-value>
 *   </init-param>
 *  </servlet>
 *
 * <servlet-mapping>
 *   <servlet-name>Send</servlet-name>
 *   <url-pattern>/phttpSend</url-pattern>
 * </servlet-mapping>
 *
 * baseDir is the directory under which registrants and their pending messages are stored
 * checkPath is the path under the current host that requests for the status of delivery should be sent
 * maxMessagesPerIdent is the maximum number of outstanding messages per peer being relayed
 *
 * The checkPath must not start with / as they are translated ala http://host:port/[path]
 */
public class SendServlet extends PHTTPRelayServlet {
    private String _checkPath;
    private int _maxMessagesPerIdent;
    
    /* config params */
    public final static String PARAM_CHECK_PATH = "checkPath";
    public final static String PARAM_MAX_MESSAGES_PER_IDENT = "maxMessagesPerIdent";
    
    /* URL parameters on the send */
    
    /** H(routerIdent).toBase64() of the target to receive the message */
    public final static String PARAM_SEND_TARGET = "target"; 
    /** # ms to wait for the message to be delivered before failing it */
    public final static String PARAM_SEND_TIMEOUTMS = "timeoutMs";
    /** # bytes to be sent in the message */
    public final static String PARAM_SEND_DATA_LENGTH = "dataLength";
    /** sending router's time in ms */
    public final static String PARAM_SEND_TIME = "localTime";
    
    /** msgId parameter to access the check path servlet with (along side PARAM_SEND_TARGET) */
    public final static String PARAM_MSG_ID = "msgId";

    
    /* key=val keys sent back on registration */
    public final static String PROP_CHECK_URL = "statusCheckURL";
    public final static String PROP_STATUS = "status";
    public final static String STATUS_OK = "accepted";
    public final static String STATUS_UNKNOWN = "unknown";
    private final static String STATUS_CLOCKSKEW = "clockSkew_"; /** prefix for (local-remote) */
    
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
	ServletInputStream in = req.getInputStream();
	try {
	    int contentLen = req.getContentLength();
	    String firstLine = getFirstLine(in, contentLen);
	    if (firstLine == null) {
		return;
	    }
	    Map params = getParameters(firstLine);
	    String target = (String)params.get(PARAM_SEND_TARGET);
	    String timeoutStr = (String)params.get(PARAM_SEND_TIMEOUTMS);
	    String lenStr = (String)params.get(PARAM_SEND_DATA_LENGTH);
	    String remoteTimeStr = (String)params.get(PARAM_SEND_TIME);
	    long skew = 0;
	    try { 
		long remTime = Long.parseLong(remoteTimeStr);
		skew = System.currentTimeMillis() - remTime;
	    } catch (Throwable t) {
		skew = Long.MAX_VALUE;
		log("*ERROR could not parse the remote time from [" + remoteTimeStr + "]");
	    }

	    log("Target [" + target + "] timeout [" + timeoutStr + "] length [" + lenStr + "] skew [" + skew + "]");

	    if ( (skew > CLOCK_FUDGE_FACTOR) || (skew < 0 - CLOCK_FUDGE_FACTOR) ) {
		log("Attempt to send by a skewed router: skew = " + skew + "ms (local-remote)");
		failSkewed(req, resp, skew);
	    }
	    
	    if (!isValidTarget(target)) {
		log("Attempt to send to an invalid target [" + target + "]");
		fail(req, resp, "Unknown or invalid target");
		return;
	    }

	    long len = -1;
	    try {
		len = Long.parseLong(lenStr);
	    } catch (Throwable t) {
		log("Unable to parse length parameter [" + PARAM_SEND_DATA_LENGTH + "] (" + lenStr + ")");
		fail(req, resp, "Invalid length parameter");
		return;
	    }

	    int msgId = saveFile(in, resp, target, len);
	    if (msgId >= 0) {
		sendSuccess(req, resp, target, msgId);
	    } else {
		fail(req, resp, "Unable to queue up the message for delivery");
	    }
	} finally {
	    try { in.close(); } catch (IOException ioe) {}
	}
    }
    
    
    private String getFirstLine(ServletInputStream in, int len) throws ServletException, IOException {
	StringBuffer buf = new StringBuffer(128);
	int numBytes = 0;
	int c = 0;
	while ( (c = in.read()) != -1) {
	    if (c == (int)'\n') break;
	    buf.append((char)c);
	    numBytes++;
	    if (numBytes > 512) {
		log("First line is > 512 bytes [" + buf.toString() + "]");
		return null;
	    }
	}
	log("First line: " + buf.toString());
	return buf.toString();
    }
    
    private static Map getParameters(String line) {
	//StringTokenizer tok = new StringTokenizer(line, "&=", true);
	Map params = new HashMap();
	while (line != null) {
	    String key = null;
	    String val = null;
	    int firstAmp = line.indexOf('&');
	    int firstEq = line.indexOf('=');
	    if (firstAmp > 0) {
		key = line.substring(0, firstEq);
		val = line.substring(firstEq+1, firstAmp);
		line = line.substring(firstAmp+1);
		params.put(key, val);
	    } else {
		line = null;
	    }
	}
	return params;
    }
    
    private boolean isValidTarget(String target) throws IOException {
	File identDir = getIdentDir(target);
	if (identDir.exists()) {
	    File identFile = new File(identDir, "identity.dat");
	    if (identFile.exists()) {
		// known and valid (maybe we need to check the file format... naw, fuck it
		String files[] = identDir.list();
		// we skip 1 because of identity.dat
		if (files.length -1 > _maxMessagesPerIdent) {
		    log("Too many messages pending for " + target + ": " + (files.length-1));
		    return false;
		} else {
		    return true;
		}
	    } else {
		log("Ident directory exists, but identity does not... corrupt for " + target);
		return false;
	    }
	} else {
	    log("Unknown ident " + target);
	    return false;
	}
    }
    
    private int saveFile(InputStream in, HttpServletResponse resp, String target, long len) throws IOException {
	File identDir = getIdentDir(target);
	if (!identDir.exists()) return -1;
	try {
	    LockManager.lockIdent(target);
	    int i = 0;
	    while (true) {
		File curFile = new File(identDir, "msg" + i + ".dat");
		if (!curFile.exists()) {
		    boolean ok = writeFile(curFile, in, len);
		    if (ok)
			return i;
		    else
			return -1;
		}
		i++;
		continue;
	    }
	} finally {
	    LockManager.unlockIdent(target);
	}
    }
    
    private boolean writeFile(File file, InputStream in, long len) throws IOException {
	long remaining = len;
	FileOutputStream fos = null;
	try {
	    fos = new FileOutputStream(file);
	    byte buf[] = new byte[4096];
	    while (remaining > 0) {
		int read = in.read(buf);
		if (read == -1)
		    break;
		remaining -= read;
		if (read > 0)
		    fos.write(buf, 0, read);
	    }
	} finally {
	    if (fos != null) { 
		try { fos.close(); } catch (IOException ioe) {}
	    }
	    if (remaining != 0) {
		log("Invalid remaining bytes [" + remaining + " out of " + len + "] - perhaps message was cancelled partway through delivery?  deleting " + file.getAbsolutePath());
		boolean deleted = file.delete();
		if (!deleted)
		    log("!!!Error deleting temporary file " + file.getAbsolutePath());
		return false;
	    }
	}
	return true;
    }
    
    private void sendSuccess(HttpServletRequest req, HttpServletResponse resp, String target, int msgId) throws IOException {
	ServletOutputStream out = resp.getOutputStream();
	StringBuffer buf = new StringBuffer();
	buf.append(PROP_STATUS).append('=').append(STATUS_OK).append('\n');
	buf.append(PROP_CHECK_URL).append('=').append(buildURL(req, _checkPath));
	buf.append('?');
	buf.append(PARAM_SEND_TARGET).append('=').append(target).append("&");
	buf.append(PARAM_MSG_ID).append('=').append(msgId).append("\n");
	out.write(buf.toString().getBytes());
	out.flush();
    }
    
    private void fail(HttpServletRequest req, HttpServletResponse resp, String err) throws IOException {
	ServletOutputStream out = resp.getOutputStream();
	StringBuffer buf = new StringBuffer();
	buf.append(PROP_STATUS).append('=').append(STATUS_UNKNOWN).append('\n');
	out.write(buf.toString().getBytes());
	out.flush();
    }
    
    private void failSkewed(HttpServletRequest req, HttpServletResponse resp, long skew) throws IOException {
	ServletOutputStream out = resp.getOutputStream();
	StringBuffer buf = new StringBuffer();
	buf.append(PROP_STATUS).append('=').append(STATUS_CLOCKSKEW).append(skew).append('\n');
	out.write(buf.toString().getBytes());
	out.flush();
    }
    
    public void init(ServletConfig config) throws ServletException {
	super.init(config);
	
	String checkPath = config.getInitParameter(PARAM_CHECK_PATH);
	if (checkPath == null)
	    throw new ServletException("Check status path for the sending servlet required [" + PARAM_CHECK_PATH + "]");
	else
	    _checkPath = checkPath;
	
	String maxMessagesPerIdentStr = config.getInitParameter(PARAM_MAX_MESSAGES_PER_IDENT);
	if (maxMessagesPerIdentStr == null)
	    throw new ServletException("Max messages per ident for the sending servlet required [" + PARAM_MAX_MESSAGES_PER_IDENT + "]");
	try {
	    _maxMessagesPerIdent = Integer.parseInt(maxMessagesPerIdentStr);
	} catch (Throwable t) {
	    throw new ServletException("Valid max messages per ident for the sending servlet required [" + PARAM_MAX_MESSAGES_PER_IDENT + "]");
	}
    }
    
    public static void main(String args[]) {
	String line = "target=pp0ARjQiB~IKC-0FsMUsPEMrwR3gxVBZGRYfEr1IzHI=&timeoutMs=52068&dataLength=2691&";
	Map props = getParameters(line);
	for (java.util.Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
	    String key = (String)iter.next();
	    String val = (String)props.get(key);
	    System.out.println("[" + key + "] = [" + val + "]");
	}
    }
}
