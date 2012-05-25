package net.i2p.util;

import java.io.IOException;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SocketTimeout implements SimpleTimer.TimedEvent {
    private Socket _targetSocket;
    private long _startTime;
    private long _inactivityDelay;
    private long _lastActivity;
    private long _totalTimeoutTime;
    private boolean _cancelled;
    private Runnable _command;
    public SocketTimeout(long delay) { this(null, delay); }
    public SocketTimeout(Socket socket, long delay) {
        _inactivityDelay = delay;
        _targetSocket = socket;
        _cancelled = false;
        _lastActivity = _startTime = System.currentTimeMillis();
        _totalTimeoutTime = -1;
        SimpleTimer.getInstance().addEvent(SocketTimeout.this, delay);
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
            SimpleTimer.getInstance().addEvent(SocketTimeout.this, _inactivityDelay);
        }
    }
    
    public void cancel() {
        _cancelled = true;
        SimpleTimer.getInstance().removeEvent(SocketTimeout.this);
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
        buf.append("started on ");
        buf.append(ts(_startTime));
        buf.append("idle for ");
        buf.append(System.currentTimeMillis() - _lastActivity);
        buf.append("ms ");
        if (_totalTimeoutTime > 0)
            buf.append("total timeout at ").append(ts(_totalTimeoutTime));
        buf.append("cancelled? ").append(_cancelled);
        return buf.toString();
    }
}