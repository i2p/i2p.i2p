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
import java.util.Date;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterIdentity;
import net.i2p.util.Clock;

/**
 * Accept registrations for PHTTP relaying, allowing the Polling HTTP (PHTTP) 
 * transport for I2P to bridge past firewalls, NATs, and proxy servers. <p />
 *
 * This servlet should be set up in web.xml as follows:
 *
 *  <servlet>
 *   <servlet-name>Register</servlet-name>
 *   <servlet-class>net.i2p.phttprelay.RegisterServlet</servlet-class>
 *   <init-param>
 *     <param-name>baseDir</param-name>
 *     <param-value>/usr/local/jetty/phttprelayDir</param-value>
 *   </init-param>
 *   <init-param>
 *     <param-name>pollPath</param-name>
 *     <param-value>phttpPoll</param-value>
 *   </init-param>
 *   <init-param>
 *     <param-name>sendPath</param-name>
 *     <param-value>phttpSend</param-value>
 *   </init-param>
 *  </servlet>
 *
 * <servlet-mapping>
 *   <servlet-name>Register</servlet-name>
 *   <url-pattern>/phttpRegister</url-pattern>
 * </servlet-mapping>
 *
 * baseDir is the directory under which registrants and their pending messages are stored
 * pollPath is the path under the current host that requests polling for messages should be sent
 * sendPath is the path under the current host that requests submitting messages should be sent
 *
 * The pollPath and sendPath must not start with / as they are translated ala http://host:port/[path]
 */
public class RegisterServlet extends PHTTPRelayServlet {
    private String _pollPath;
    private String _sendPath;

    /* config params */
    public final static String PARAM_POLL_PATH = "pollPath";
    public final static String PARAM_SEND_PATH = "sendPath";

    /* key=val keys sent back on registration */
    public final static String PROP_STATUS = "status";
    public final static String PROP_POLL_URL = "pollURL";
    public final static String PROP_SEND_URL = "sendURL";
    public final static String PROP_TIME_OFFSET = "timeOffset"; // ms (local-remote)

    /* values for the PROP_STATUS */
    public final static String STATUS_FAILED = "failed";
    public final static String STATUS_REGISTERED = "registered";

    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ServletInputStream in = req.getInputStream();
        RouterIdentity ident = new RouterIdentity();
        try {
            Date remoteTime = DataHelper.readDate(in);
            long skew = getSkew(remoteTime);
            ident.readBytes(in);
            boolean ok = registerIdent(ident);
            sendURLs(req, resp, skew, ok);
        } catch (DataFormatException dfe) {
            log("Invalid format for router identity posted", dfe);
        } finally {
            in.close();
        }
    }

    private long getSkew(Date remoteDate) {
        if (remoteDate == null) {
            log("*ERROR: remote date was null");
            return Long.MAX_VALUE;
        } else {
            long diff = Clock.getInstance().now() - remoteDate.getTime();
            return diff;
        }
    }

    private boolean registerIdent(RouterIdentity ident) throws DataFormatException, IOException {
        File identDir = getIdentDir(ident.getHash().toBase64());
        boolean created = identDir.mkdirs();
        File identFile = new File(identDir, "identity.dat");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(identFile);
            ident.writeBytes(fos);
        } finally {
            if (fos != null) try {
                fos.close();
            } catch (IOException ioe) {
            }
        }
        log("Identity registered into " + identFile.getAbsolutePath());
        return true;
    }

    private void sendURLs(HttpServletRequest req, HttpServletResponse resp, long skew, boolean ok) throws IOException {
        ServletOutputStream out = resp.getOutputStream();

        log("*Debug: clock skew of " + skew + "ms (local-remote)");

        StringBuffer buf = new StringBuffer();
        if (ok) {
            buf.append(PROP_POLL_URL).append("=").append(buildURL(req, _pollPath)).append("\n");
            buf.append(PROP_SEND_URL).append("=").append(buildURL(req, _sendPath)).append("\n");
            buf.append(PROP_TIME_OFFSET).append("=").append(skew).append("\n");
            buf.append(PROP_STATUS).append("=").append(STATUS_REGISTERED).append("\n");
        } else {
            buf.append(PROP_TIME_OFFSET).append("=").append(skew).append("\n");
            buf.append(PROP_STATUS).append("=").append(STATUS_FAILED).append("\n");
        }
        out.write(buf.toString().getBytes());
        out.close();
    }

    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        String pollPath = config.getInitParameter(PARAM_POLL_PATH);
        if (pollPath == null)
            throw new ServletException("Polling path for the registration servlet required [" + PARAM_POLL_PATH + "]");
        else
            _pollPath = pollPath;
        String sendPath = config.getInitParameter(PARAM_SEND_PATH);
        if (sendPath == null)
            throw new ServletException("Sending path for the registration servlet required [" + PARAM_SEND_PATH + "]");
        else
            _sendPath = sendPath;
    }
}