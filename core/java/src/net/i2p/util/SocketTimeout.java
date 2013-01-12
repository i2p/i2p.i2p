package net.i2p.util;

import java.io.IOException;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 *  This should be deprecated.
 *  It is only used by EepGet and Syndie.
 *  The only advantage seems to be a total timeout period, which is the second
 *  argument to EepGet.fetch(headerTimeout, totalTimeout, inactivityTimeout),
 *  which is most likely always set to -1.
 *
 *  Use socket.setsotimeout instead?
 */
public class SocketTimeout extends SimpleTimer2.TimedEvent {
    private volatile Socket _targetSocket;
    private final long _startTime;
    private volatile long _inactivityDelay;
    private volatile long _lastActivity;
    private volatile long _totalTimeoutTime;
    private volatile boolean _cancelled;
    private volatile Runnable _command;

    public SocketTimeout(long delay) { this(null, delay); }

    public SocketTimeout(Socket socket, long delay) {
        super(SimpleTimer2.getInstance());
        _inactivityDelay = delay;
        _targetSocket = socket;
        _cancelled = false;
        _lastActivity = _startTime = System.currentTimeMillis();
        _totalTimeoutTime = -1;
        schedule(delay);
    }

    public void timeReached() {
        if (_cancelled) return;
        
        if ( ( (_totalTimeoutTime > 0) && (_totalTimeoutTime <= System.currentTimeMillis()) ) ||
             (_inactivityDelay + _lastActivity <= System.currentTimeMillis()) ) {
            if (_targetSocket != null) {
                try {
                    if (!_targetSocket.isClosed())
                        _targetSocket.close();
                } catch (IOException ioe) {}
            }
            if (_command != null) _command.run();
        }  else {
            schedule(_inactivityDelay);
        }
    }
    
    /**
     *  Change in return value from void to boolean in
     *  0.9.3 accidentally broke Syndie, sorry.
     *  Recompile Syndie to fix it.
     */
    public boolean cancel() {
        _cancelled = true;
        return super.cancel();
    }

    public void setSocket(Socket s) { _targetSocket = s; }
    public void resetTimer() { _lastActivity = System.currentTimeMillis();  }
    public void setInactivityTimeout(long timeout) { _inactivityDelay = timeout; }

    public void setTotalTimeoutPeriod(long timeoutPeriod) { 
        if (timeoutPeriod > 0)
            _totalTimeoutTime = _startTime + timeoutPeriod;
        else
            _totalTimeoutTime = -1;
    }

    public void setTimeoutCommand(Runnable job) { _command = job; }
    
    private static final SimpleDateFormat _fmt = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss.SSS");
    private static String ts(long when) { synchronized (_fmt) { return _fmt.format(new Date(when)); } }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("SocketTimeout started on ");
        buf.append(ts(_startTime));
        buf.append(" idle for ");
        buf.append(System.currentTimeMillis() - _lastActivity);
        buf.append("ms ");
        if (_totalTimeoutTime > 0)
            buf.append("total timeout at ").append(ts(_totalTimeoutTime));
        buf.append("cancelled? ").append(_cancelled);
        return buf.toString();
    }
}
