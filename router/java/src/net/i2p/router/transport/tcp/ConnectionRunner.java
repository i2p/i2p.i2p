package net.i2p.router.transport.tcp;

import java.io.IOException;
import java.io.OutputStream;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.DateMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Push out I2NPMessages across the wire
 *
 */
class ConnectionRunner implements Runnable {
    private Log _log;
    private RouterContext _context;
    private TCPConnection _con;
    private boolean _keepRunning;
    private byte _writeBuffer[];
    private long _lastTimeSend;
    private long _lastWriteEnd;
    private long _lastWriteBegin;
    private long _lastRealActivity;
    
    private static final long TIME_SEND_FREQUENCY = 60*1000;
    /** if we don't send them any real data in a 10 minute period, drop 'em */
    static final int DISCONNECT_INACTIVITY_PERIOD = 10*60*1000;
    
    public ConnectionRunner(RouterContext ctx, TCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(ConnectionRunner.class);
        _con = con;
        _keepRunning = false;
        _lastWriteBegin = ctx.clock().now();
        _lastWriteEnd = _lastWriteBegin;
        _lastRealActivity = _lastWriteBegin;
    }
    
    public void startRunning() {
        _keepRunning = true;
        _writeBuffer = new byte[38*1024]; // expansion factor 
        _lastTimeSend = -1;
        
        String name = "TCP " + _context.routerHash().toBase64().substring(0,6) 
                      + " to " 
                      + _con.getRemoteRouterIdentity().calculateHash().toBase64().substring(0,6);
        I2PThread t = new I2PThread(this, name);
        t.start();
        
        long delay = TIME_SEND_FREQUENCY + _context.random().nextInt(60*1000);
        SimpleTimer.getInstance().addEvent(new KeepaliveEvent(), delay);
    }
    
    public void stopRunning() {
        _keepRunning = false;
    }
    
    public void run() {
        while (_keepRunning && !_con.getIsClosed()) {
            OutNetMessage msg = _con.getNextMessage();
            if (msg == null) {
                if (_keepRunning && !_con.getIsClosed())
                    _log.error("next message is null but we should keep running?");
                _con.closeConnection();
                return;
            } else {
                sendMessage(msg);
            }
        }
    }
    
    private void sendMessage(OutNetMessage msg) {
        byte buf[] = _writeBuffer;
        int written = 0;
        try {
            written = msg.getMessageData(_writeBuffer);
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            I2NPMessage m = msg.getMessage();
            if (m != null) {
                buf = m.toByteArray();
                written = buf.length;
            }
        } catch (Exception e) {
            _log.log(Log.CRIT, "getting the message data", e);
            _con.closeConnection();
            return;
        }
        if (written <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("message " + msg.getMessageType() + "/" + msg.getMessageId() 
                          + " expired before it could be sent");
            
            msg.timestamp("ConnectionRunner.sendMessage noData");
            _con.sent(msg, false, 0);
            return;
        }
        
        msg.timestamp("ConnectionRunner.sendMessage data");

        boolean sendTime = false;
        if (_lastTimeSend < _context.clock().now() - TIME_SEND_FREQUENCY)
            sendTime = true;
        
        OutputStream out = _con.getOutputStream();
        boolean ok = false;
        
        if (!DateMessage.class.getName().equals(msg.getMessageType()))
            _lastRealActivity = _context.clock().now();
        try {
            synchronized (out) {
                _lastWriteBegin = _context.clock().now();
                out.write(buf, 0, written);
                if (sendTime) {
                    out.write(buildTimeMessage().toByteArray());
                    _lastTimeSend = _context.clock().now();
                }
                out.flush();
                _lastWriteEnd = _context.clock().now();
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Just sent message " + msg.getMessageId() + " to " 
                         + msg.getTarget().getIdentity().getHash().toBase64().substring(0,6)
                         + " writeTime = " + (_lastWriteEnd - _lastWriteBegin) +"ms"
                         + " lifetime = " + msg.getLifetime() + "ms");
            
            ok = true;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the message", ioe);
            _con.closeConnection();
        }
        _con.sent(msg, ok, _lastWriteEnd - _lastWriteBegin);
    }

    /**
     * Build up a new message to be sent with the current router's time
     *
     */
    private I2NPMessage buildTimeMessage() {
        DateMessage dm = new DateMessage(_context);
        dm.setNow(_context.clock().now());
        return dm;
    }
    
    /**
     * Every few minutes, send a (tiny) message to the peer if we haven't
     * spoken with them recently.  This will help kill off any hung
     * connections (due to IP address changes, etc).  If we don't get any
     * messages through in 5 minutes, kill the connection as well.
     *
     */
    private class KeepaliveEvent implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (!_keepRunning) return;
            if (_con.getIsClosed()) return;
            long now = _context.clock().now();
            long timeSinceWrite = now - _lastWriteEnd;
            long timeSinceWriteBegin = now - _lastWriteBegin;
            long timeSinceWriteReal = now - _lastRealActivity;
            if (timeSinceWrite > 5*TIME_SEND_FREQUENCY) {
                TCPTransport t = _con.getTransport();
                String msg = "Connection closed with "
                             + _con.getRemoteRouterIdentity().getHash().toBase64().substring(0,6)
                             + " due to " + DataHelper.formatDuration(timeSinceWrite)
                             + " of inactivity after " 
                             + DataHelper.formatDuration(_con.getLifetime());
                if (_lastWriteBegin > _lastWriteEnd)
                    msg = msg + " with a message being written for " + 
                          DataHelper.formatDuration(timeSinceWriteBegin);
                t.addConnectionErrorMessage(msg);
                if (_log.shouldLog(Log.INFO))
                    _log.info(msg);
                _con.closeConnection(false);
                return;
            }
            if (timeSinceWriteReal > DISCONNECT_INACTIVITY_PERIOD) {
                TCPTransport t = _con.getTransport();
                String msg = "Connection closed with "
                             + _con.getRemoteRouterIdentity().getHash().toBase64().substring(0,6)
                             + " due to " + DataHelper.formatDuration(timeSinceWriteReal)
                             + " of inactivity after " 
                             + DataHelper.formatDuration(_con.getLifetime());
                if (_lastWriteBegin > _lastWriteEnd)
                    msg = msg + " with a message being written for " + 
                          DataHelper.formatDuration(timeSinceWriteBegin);
                t.addConnectionErrorMessage(msg);
                if (_log.shouldLog(Log.INFO))
                    _log.info(msg);
                _con.closeConnection(false);
                return;
            }
            
            if (_lastTimeSend < _context.clock().now() - 2*TIME_SEND_FREQUENCY) 
                enqueueTimeMessage();
            long delay = 2*TIME_SEND_FREQUENCY + _context.random().nextInt((int)TIME_SEND_FREQUENCY);
            SimpleTimer.getInstance().addEvent(KeepaliveEvent.this, delay);
        }
    }
    
    private void enqueueTimeMessage() {
        OutNetMessage msg = new OutNetMessage(_context);
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_con.getRemoteRouterIdentity().getHash());
        if (ri == null) return;
        msg.setTarget(ri);
        msg.setExpiration(_context.clock().now() + TIME_SEND_FREQUENCY);
        msg.setMessage(buildTimeMessage());
        msg.setPriority(100);
        _con.addMessage(msg);
        if (_log.shouldLog(Log.INFO))
            _log.info("Enqueueing time message to " + _con.getRemoteRouterIdentity().getHash().toBase64().substring(0,6));
    }
}
