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
    private final Log _log;
    private Boolean _helloOk;
    private String _version;
    private final Object _helloLock = new Object();
    private Boolean _sessionCreateOk;
    private Boolean _sessionAddOk;
    private Boolean _streamStatusOk;
    private final Object _sessionCreateLock = new Object();
    private final Object _namingReplyLock = new Object();
    private final Object _streamStatusLock = new Object();
    private final Map<String,String> _namingReplies = new HashMap<String,String>();

    public SAMEventHandler(I2PAppContext ctx) {
        //_context = ctx;
        _log = ctx.logManager().getLog(getClass());
    }
    
    @Override
    public void helloReplyReceived(boolean ok, String version) {
        synchronized (_helloLock) {
            if (ok)
                _helloOk = Boolean.TRUE;
            else
                _helloOk = Boolean.FALSE;
            _version = version;
            _helloLock.notifyAll();
        }
    }

    /** may be called twice, first for CREATE and second for ADD */
    @Override
    public void sessionStatusReceived(String result, String destination, String msg) {
        synchronized (_sessionCreateLock) {
            Boolean ok;
            if (SAMReader.SAMClientEventListener.SESSION_STATUS_OK.equals(result))
                ok = Boolean.TRUE;
            else 
                ok = Boolean.FALSE;
            if (_sessionCreateOk == null)
                _sessionCreateOk = ok;
            else if (_sessionAddOk == null)
                _sessionAddOk = ok;
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
    public void streamStatusReceived(String result, String id, String message) {
        synchronized (_streamStatusLock) {
            if (SAMReader.SAMClientEventListener.SESSION_STATUS_OK.equals(result))
                _streamStatusOk = Boolean.TRUE;
            else 
                _streamStatusOk = Boolean.FALSE;
            _streamStatusLock.notifyAll();
        }
    }

    @Override
    public void unknownMessageReceived(String major, String minor, Properties params) {
        _log.error("Unhandled message: [" + major + "] [" + minor + "] [" + params + "]");
    }

    
    //
    // blocking lookup calls below
    //

    /**
     * Wait for the connection to be established, returning the server version if everything 
     * went ok
     * @return SAM server version if everything ok, or null on failure
     */
    public String waitForHelloReply() {
        while (true) {
            try {
                synchronized (_helloLock) {
                    if (_helloOk == null)
                        _helloLock.wait();
                    else 
                        return _helloOk.booleanValue() ? _version : null;
                }
            } catch (InterruptedException ie) { return null; }
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
            } catch (InterruptedException ie) { return false; }
        }
    }

    /**
     * Wait for the session to be added, returning true if everything went ok
     *
     * @return true if everything ok
     * @since 0.9.25
     */
    public boolean waitForSessionAddReply() {
        while (true) {
            try {
                synchronized (_sessionCreateLock) {
                    if (_sessionAddOk == null)
                        _sessionCreateLock.wait();
                    else
                        return _sessionAddOk.booleanValue();
                }
            } catch (InterruptedException ie) { return false; }
        }
    }

    /**
     * Wait for the stream to be created, returning true if everything went ok
     *
     * @return true if everything ok
     */
    public boolean waitForStreamStatusReply() {
        while (true) {
            try {
                synchronized (_streamStatusLock) {
                    if (_streamStatusOk == null)
                        _streamStatusLock.wait();
                    else
                        return _streamStatusOk.booleanValue();
                }
            } catch (InterruptedException ie) { return false; }
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
            } catch (InterruptedException ie) { return null; }
        }
    }
}
