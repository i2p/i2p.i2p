package net.i2p.phttprelay;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.crypto.DSAEngine;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterIdentity;
import net.i2p.data.Signature;
import net.i2p.util.Clock;

/**
 * Handle poll requests for new messages - checking the poll request for a valid signature,
 * sending back all of the messages found, and after all messages are written out, delete
 * them from the local store.  If the signature fails, it sends back an HTTP 403 (UNAUTHORIZED).
 * If the target is not registered, it sends back an HTTP 404 (NOT FOUND) <p />
 *
 * This servlet should be set up in web.xml as follows:
 *
 *  <servlet>
 *   <servlet-name>Poll</servlet-name>
 *   <servlet-class>net.i2p.phttprelay.PollServlet</servlet-class>
 *   <init-param>
 *     <param-name>baseDir</param-name>
 *     <param-value>/usr/local/jetty/phttprelayDir</param-value>
 *   </init-param>
 *  </servlet>
 *
 * <servlet-mapping>
 *   <servlet-name>Poll</servlet-name>
 *   <url-pattern>/phttpPoll</url-pattern>
 * </servlet-mapping>
 *
 * baseDir is the directory under which registrants and their pending messages are stored
 *
 */
public class PollServlet extends PHTTPRelayServlet {    
    /* URL parameters on the check */
    
    /** H(routerIdent).toBase64() of the target to receive the message */
    public final static String PARAM_SEND_TARGET = "target"; 
    
    /** HTTP error code if the target is not known*/
    public final static int CODE_UNKNOWN = HttpServletResponse.SC_NOT_FOUND;
    /** HTTP error code if the signature failed */
    public final static int CODE_UNAUTHORIZED = HttpServletResponse.SC_UNAUTHORIZED;
    /** HTTP error code if everything is ok */
    public final static int CODE_OK = HttpServletResponse.SC_OK;
    
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
	byte data[] = getData(req);
	if (data == null) return;
	ByteArrayInputStream bais = new ByteArrayInputStream(data);
	String target = getTarget(bais);
	if (target == null) {
	    log("Target not specified");
	    resp.sendError(CODE_UNKNOWN);
	    return;
	} 
	
	if (!isKnown(target)) {
	    resp.sendError(CODE_UNKNOWN);
	    return;
	} 
	
	if (!isAuthorized(target, bais)) {
	    resp.sendError(CODE_UNAUTHORIZED);
	    return;
	} else {
	    log("Authorized access for target " + target);
	}
	 
	sendMessages(resp, target);
    }
 
    private byte[] getData(HttpServletRequest req) throws ServletException, IOException {
	ServletInputStream in = req.getInputStream();
	int len = req.getContentLength();
	byte data[] = new byte[len];
	int cur = 0;
	int read = DataHelper.read(in, data);
	if (read != len) {
	    log("Size read is incorrect [" + read + " instead of expected " + len + "]");
	    return null;
	} else {
	    log("Read data length: " + data.length + " in base64: " + Base64.encode(data));
	    return data;
	}
    }
    
    private String getTarget(InputStream in) throws IOException {
	StringBuffer buf = new StringBuffer(64);
	int numBytes = 0;
	int c = 0;
	while ( (c = in.read()) != -1) {
	    if (c == (int)'&') break;
	    buf.append((char)c);
	    numBytes++;
	    if (numBytes > 128) {
		log("Target didn't find the & after 128 bytes [" + buf.toString() + "]");
		return null;
	    }
	}
	if (buf.toString().indexOf("target=") != 0) {
	    log("Did not start with target= [" + buf.toString() + "]");
	    return null;
	}
	return buf.substring("target=".length());
    }
    
    private void sendMessages(HttpServletResponse resp, String target) throws IOException {
	log("Before lock " + target);
	LockManager.lockIdent(target);
	log("Locked " + target);
	try {
	    File identDir = getIdentDir(target);
	    expire(identDir);
	    File messageFiles[] = identDir.listFiles();
	    resp.setStatus(CODE_OK);
	    log("Sending back " + (messageFiles.length -1) + " messages");
	    ServletOutputStream out = resp.getOutputStream();
	    DataHelper.writeDate(out, new Date(Clock.getInstance().now()));
	    DataHelper.writeLong(out, 2, messageFiles.length -1);
	    for (int i = 0; i < messageFiles.length; i++) {
		if ("identity.dat".equals(messageFiles[i].getName())) {
		    // skip
		} else {
		    log("Message file " + messageFiles[i].getName() + " is " + messageFiles[i].length() + " bytes");
		    DataHelper.writeLong(out, 4, messageFiles[i].length());
		    writeFile(out, messageFiles[i]);
		    boolean deleted = messageFiles[i].delete();
		    if (!deleted) {
			log("!!!Error removing message file " + messageFiles[i].getAbsolutePath() + " - please delete!");
		    }
		}
	    }
	    out.flush();
	    out.close();
	} catch (DataFormatException dfe) {
	    log("Error sending message", dfe);
	} finally {
	    LockManager.unlockIdent(target);
	    log("Unlocked " + target);
	}
    }
    
    private final static long EXPIRE_DELAY = 60*1000; // expire messages every minute
    
    private void expire(File identDir) throws IOException {
	File files[] = identDir.listFiles();
	long now = System.currentTimeMillis();
	for (int i = 0 ; i < files.length; i++) {
	    if ("identity.dat".equals(files[i].getName())) {
		continue;
	    }
	    if (files[i].lastModified() + EXPIRE_DELAY < now) {
		log("Expiring " + files[i].getAbsolutePath());
		files[i].delete();
	    }
	}
    }
    
    private void writeFile(ServletOutputStream out, File file) throws IOException {
	FileInputStream fis = new FileInputStream(file);
	try {
	    byte buf[] = new byte[4096];
	    while (true) {
		int read = DataHelper.read(fis, buf);
		if (read > 0)
		    out.write(buf, 0, read);
		else
		    break;
	    }
	} finally {
	    fis.close();
	}
    }
    
    
    private boolean isKnown(String target) throws IOException {
	File identDir = getIdentDir(target);
	if (identDir.exists()) {
	    File identFile = new File(identDir, "identity.dat");
	    if (identFile.exists()) {
		// known and valid (maybe we need to check the file format... naw, fuck it
		return true;
	    } else {
		return false;
	    }
	} else {
	    return false;
	}
    }
    
    private boolean isAuthorized(String target, InputStream in) throws IOException {
	RouterIdentity ident = null;
	try {
	    ident = getRouterIdentity(target);
	} catch (DataFormatException dfe) {
	    log("Identity was not valid", dfe);
	}
	
	if (ident == null) {
	    log("Identity not registered");
	    return false;
	}
	
	try {
	    long val = DataHelper.readLong(in, 4);
	    Signature sig = new Signature();
	    sig.readBytes(in);
	    ByteArrayOutputStream baos = new ByteArrayOutputStream();
	    DataHelper.writeLong(baos, 4, val);
	    if (DSAEngine.getInstance().verifySignature(sig, baos.toByteArray(), ident.getSigningPublicKey())) {
		return true;
	    } else {
		log("Signature does NOT match");
		return false;
	    }
	} catch (DataFormatException dfe) {
	    log("Format error reading the nonce and signature", dfe);
	    return false;
	}
    }
    
    private RouterIdentity getRouterIdentity(String target) throws IOException, DataFormatException {
	File identDir = getIdentDir(target);
	if (identDir.exists()) {
	    File identFile = new File(identDir, "identity.dat");
	    if (identFile.exists()) {
		// known and valid (maybe we need to check the file format... naw, fuck it
		RouterIdentity ident = new RouterIdentity();
		ident.readBytes(new FileInputStream(identFile));
		return ident;
	    } else {
		return null;
	    }
	} else {
	    return null;
	}
    }
}
