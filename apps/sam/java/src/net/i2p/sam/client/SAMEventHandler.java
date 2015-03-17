package net.i2p.sam.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Simple helper implementation of a the SAMClientEventListener
 *
 */
public class SAMEventHandler extends SAMClientEventListenerImpl {
    //private I2PAppContext _context;
    private Log _log;
    private Boolean _helloOk;
    private Object _helloLock = new Object();
    private Boolean _sessionCreateOk;
    private Object _sessionCreateLock = new Object();
    private Object _namingReplyLock = new Object();
    private Map<String,String> _namingReplies = new HashMap<String,String>();

    public SAMEventHandler(I2PAppContext ctx) {
        //_context = ctx;
        _log = ctx.logManager().getLog(getClass());
    }
    
	@Override
    public void helloReplyReceived(boolean ok) {
        synchronized (_helloLock) {
            if (ok)
                _helloOk = Boolean.TRUE;
            else
                _helloOk = Boolean.FALSE;
            _helloLock.notifyAll();
        }
    }

	@Override
    public void sessionStatusReceived(String result, String destination, String msg) {
        synchronized (_sessionCreateLock) {
            if (SAMReader.SAMClientEventListener.SESSION_STATUS_OK.equals(result))
                _sessionCreateOk = Boolean.TRUE;
            else 
                _sessionCreateOk = Boolean.FALSE;
            _sessionCreateLock.notifyAll();
        }
    }

	@Override
    public void namingReplyReceived(String name, String result, String value, String msg) {
        synchronized (_namingReplyLock) {
            if (SAMReader.SAMClientEventListener.NAMING_REPLY_OK.equals(result)) 
                _namingReplies.put(name, value);
            else
                _namingReplies.put(name, result);
            _namingReplyLock.notifyAll();
        }
    }

	@Override
    public void unknownMessageReceived(String major, String minor, Properties params) {
        _log.error("wrt, [" + major + "] [" + minor + "] [" + params + "]");
    }

    
    //
    // blocking lookup calls below
    //

    /**
     * Wait for the connection to be established, returning true if everything 
     * went ok
     * @return true if everything ok
     */
    public boolean waitForHelloReply() {
        while (true) {
            try {
                synchronized (_helloLock) {
                    if (_helloOk == null)
                        _helloLock.wait();
                    else 
                        return _helloOk.booleanValue();
                }
            } catch (InterruptedException ie) {}
        }
    }

    /**
     * Wait for the session to be created, returning true if everything went ok
     *
     * @return true if everything ok
     */
    public boolean waitForSessionCreateReply() {
        while (true) {
            try {
                synchronized (_sessionCreateLock) {
                    if (_sessionCreateOk == null)
                        _sessionCreateLock.wait();
                    else
                        return _sessionCreateOk.booleanValue();
                }
            } catch (InterruptedException ie) {}
        }
    }
    
    /**
     * Return the destination found matching the name, or null if the key was
     * not able to be retrieved.
     *
     * @param name name to be looked for, or "ME"
     * @return destination found matching the name, or null
     */
    public String waitForNamingReply(String name) {
        while (true) {
            try {
                synchronized (_namingReplyLock) {
                    String val = _namingReplies.remove(name);
                    if (val == null) {
                        _namingReplyLock.wait();
                    } else {
                        if (SAMReader.SAMClientEventListener.NAMING_REPLY_INVALID_KEY.equals(val))
                            return null;
                        else if (SAMReader.SAMClientEventListener.NAMING_REPLY_KEY_NOT_FOUND.equals(val))
                            return null;
                        else
                            return val;
                    }
                }
            } catch (InterruptedException ie) {}
        }
    }
}
