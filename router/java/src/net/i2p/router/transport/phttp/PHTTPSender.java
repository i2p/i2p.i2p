package net.i2p.router.transport.phttp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.Iterator;

import net.i2p.data.RouterAddress;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

class PHTTPSender {
    private Log _log;
    private RouterContext _context;
    private PHTTPTransport _transport;
    private volatile long _sendId = 0;
    
    public final static long RECHECK_DELAY = 1000; // 1 sec
    public final static long HARD_TIMEOUT = 30*1000; // no timeouts > 30 seconds
    
    /** H(routerIdent).toBase64() of the target to receive the message */
    public final static String PARAM_SEND_TARGET = "target";
    /** # ms to wait for the message to be delivered before failing it */
    public final static String PARAM_SEND_TIMEOUTMS = "timeoutMs";
    /** # bytes to be sent in the message */
    public final static String PARAM_SEND_DATA_LENGTH = "dataLength";
    /** local time in ms */
    public final static String PARAM_SEND_TIME = "localTime";
    
    private final static String PROP_STATUS = "status";
    private final static String STATUS_OK = "accepted";
    private final static String STATUS_PENDING = "pending";
    private final static String STATUS_CLOCKSKEW = "clockSkew_"; /** prefix for (remote-local) */
    
    /** HTTP error code if the target is known and accepting messages */
    public final static int CODE_OK = 201; // created
    /** HTTP error code if the target is not known or is not accepting messages */
    public final static int CODE_FAIL = 410; // gone
    
    /* the URL to check to see when the message is delivered */
    public final static String PROP_CHECK_URL = "statusCheckURL";
    
    /** HTTP error code if the message was sent completely */
    public final static int CODE_NOT_PENDING = 410; // gone
    /** HTTP error code if the message is still pending */
    public final static int CODE_PENDING = 204; // ok, but no content
    
    public PHTTPSender(RouterContext context, PHTTPTransport transport) {
        _context = context;
        _log = context.logManager().getLog(PHTTPSender.class);
        _transport = transport;
    }
    
    public void send(OutNetMessage msg) {
        _log.debug("Sending message " + msg.getMessage().getClass().getName() + " to " + msg.getTarget().getIdentity().getHash().toBase64());
        Thread t = new I2PThread(new Send(msg));
        t.setName("PHTTP Sender " + (_sendId++));
        t.setDaemon(true);
        t.start();
    }
    
    class Send implements Runnable {
        private OutNetMessage _msg;
        public Send(OutNetMessage msg) {
            _msg = msg;
        }
        public void run() {
            boolean ok = false;
            try {
                ok = doSend(_msg);
            } catch (IOException ioe) {
                _log.error("Error sending the message", ioe);
            }
            _transport.afterSend(_msg, ok);
        }
    }
    
    private boolean doSend(OutNetMessage msg) throws IOException {
        long delay = _context.bandwidthLimiter().calculateDelayOutbound(msg.getTarget().getIdentity(), (int)msg.getMessageSize());
        _log.debug("Delaying [" + delay + "ms]");
        try { Thread.sleep(delay); } catch (InterruptedException ie) {}
        _log.debug("Continuing with sending");
        // now send
        URL sendURL = getURL(msg);
        if (sendURL == null) {
            _log.debug("No URL to send");
            return false;
        } else {
            _log.debug("Sending to " + sendURL.toExternalForm());
            HttpURLConnection con = (HttpURLConnection)sendURL.openConnection();
            // send the info
            con.setRequestMethod("POST");
            con.setUseCaches(false);
            con.setDoOutput(true);
            con.setDoInput(true);
            
            byte data[] = getData(msg);
            if (data == null) return false;
            
            _context.bandwidthLimiter().delayOutbound(msg.getTarget().getIdentity(), data.length+512); // HTTP overhead
            
            con.setRequestProperty("Content-length", ""+data.length);
            OutputStream out = con.getOutputStream();
            out.write(data);
            out.flush();
            _log.debug("Data sent, before reading");
            
            // fetch the results
            String checkURL = getCheckURL(con);
            if (checkURL != null) {
                _log.debug("Message sent");
                return checkDelivery(checkURL, msg);
            } else {
                _log.warn("Target not known or unable to send to " + msg.getTarget().getIdentity().getHash().toBase64());
                return false;
            }
        }
    }
    
    private String getCheckURL(HttpURLConnection con) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String statusLine  = reader.readLine();
        if (statusLine == null) {
            _log.error("Null response line when checking URL");
            return null;
        }
        boolean statusOk = false;
        if (!statusLine.startsWith(PROP_STATUS)) {
            _log.warn("Response does not begin with status [" + statusLine + "]");
            return null;
        } else {
            String statVal = statusLine.substring(PROP_STATUS.length() + 1);
            statusOk = STATUS_OK.equals(statVal);
            
            if (!statusOk) {
                _log.info("Status was not ok for sending [" + statVal + "]");
                return null;
            }
        }
        
        String checkURL = reader.readLine();
        if (!checkURL.startsWith(PROP_CHECK_URL)) {
            _log.warn("Incorrect OK response: " + checkURL);
            return null;
        } else {
            String checkURLStr = checkURL.substring(PROP_CHECK_URL.length()+1);
            _log.debug("Check URL = [" + checkURLStr + "]");
            return checkURLStr;
        }
    }
    
    private boolean checkDelivery(String checkURLStr, OutNetMessage msg) {
        long now = _context.clock().now();
        long expiration = msg.getExpiration();
        if (expiration <= now)
            expiration = now + HARD_TIMEOUT;
        
        _log.debug("Check delivery [expiration = " + new Date(expiration) + "]");
        try {
            URL checkStatusURL = new URL(checkURLStr);
            long delay = RECHECK_DELAY;
            do {
                _context.bandwidthLimiter().delayOutbound(msg.getTarget().getIdentity(), 512); // HTTP overhead
                _context.bandwidthLimiter().delayInbound(msg.getTarget().getIdentity(), 512); // HTTP overhead
                
                _log.debug("Checking delivery at " + checkURLStr);
                HttpURLConnection con = (HttpURLConnection)checkStatusURL.openConnection();
                con.setRequestMethod("GET");
                //con.setInstanceFollowRedirects(false); // kaffe doesn't support this (yet)
                con.setDoInput(true);
                con.setDoOutput(false);
                con.setUseCaches(false);
                con.connect();
                
                boolean isPending = getIsPending(con);
                if (!isPending) {
                    _log.info("Check delivery successful for message " + msg.getMessage().getClass().getName());
                    return true;
                }
                
                if (now + delay > expiration)
                    delay = expiration - now - 30; // 30 = kludgy # for the next 4 statements
                _log.debug("Still pending (wait " + delay + "ms)");
                Thread.sleep(delay);
                //delay += RECHECK_DELAY;
                
                now = _context.clock().now();
            } while (now < expiration);
            _log.warn("Timeout for checking delivery to " + checkURLStr + " for message " + msg.getMessage().getClass().getName());
        } catch (Throwable t) {
            _log.debug("Error checking for delivery", t);
        }
        return false;
    }
    
    private boolean getIsPending(HttpURLConnection con) throws IOException {
        int len = con.getContentLength();
        int rc = con.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String statusLine  = reader.readLine();
        if (statusLine == null) {
            _log.warn("Server didn't send back a status line [len = " + len + ", rc = " + rc + "]");
            return false;
        }
        boolean statusPending = false;
        if (!statusLine.startsWith(PROP_STATUS)) {
            _log.warn("Response does not begin with status [" + statusLine + "]");
            return false;
        } else {
            String statVal = statusLine.substring(PROP_STATUS.length() + 1);
            statusPending = STATUS_PENDING.equals(statVal);
            if (statVal.startsWith(STATUS_CLOCKSKEW)) {
                long skew = Long.MAX_VALUE;
                String skewStr = statVal.substring(STATUS_CLOCKSKEW.length()+1);
                try {
                    skew = Long.parseLong(skewStr);
                } catch (Throwable t) {
                    _log.error("Unable to decode the clock skew [" + skewStr + "]");
                    skew = Long.MAX_VALUE;
                }
                _log.error("Clock skew talking with phttp relay: " + skew + "ms (remote-local)");
            }
            return statusPending;
        }
    }
    
    private byte[] getData(OutNetMessage msg) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int)(msg.getMessageSize() + 64));
            String target = msg.getTarget().getIdentity().getHash().toBase64();
            StringBuffer buf = new StringBuffer();
            buf.append(PARAM_SEND_TARGET).append('=').append(target).append('&');
            buf.append(PARAM_SEND_TIMEOUTMS).append('=').append(msg.getExpiration() - _context.clock().now()).append('&');
            buf.append(PARAM_SEND_DATA_LENGTH).append('=').append(msg.getMessageSize()).append('&');
            buf.append(PARAM_SEND_TIME).append('=').append(_context.clock().now()).append('&').append('\n');
            baos.write(buf.toString().getBytes());
            baos.write(msg.getMessageData());
            byte data[] = baos.toByteArray();
            _log.debug("Data to be sent: " + data.length);
            return data;
        } catch (Throwable t) {
            _log.error("Error preparing the data", t);
            return null;
        }
    }
    
    private URL getURL(OutNetMessage msg) {
        for (Iterator iter = msg.getTarget().getAddresses().iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            URL url = getURL(addr);
            if (url != null) return url;
        }
        _log.warn("No URLs could be constructed to send to " + msg.getTarget().getIdentity().getHash().toBase64());
        return null;
    }
    
    private URL getURL(RouterAddress addr) {
        if (PHTTPTransport.STYLE.equals(addr.getTransportStyle())) {
            String url = addr.getOptions().getProperty(PHTTPTransport.PROP_TO_SEND_URL);
            if (url == null) return null;
            try {
                return new URL(url);
            } catch (MalformedURLException mue) {
                _log.info("Address has a bad url [" + url + "]", mue);
            }
        }
        return null;
    }
}
