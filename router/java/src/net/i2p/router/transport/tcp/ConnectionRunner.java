package net.i2p.router.transport.tcp;

import java.io.IOException;
import java.io.OutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Push out I2NPMessages across the wire
 *
 */
class ConnectionRunner implements Runnable {
    private Log _log;
    private RouterContext _context;
    private TCPConnection _con;
    private boolean _keepRunning;
    
    public ConnectionRunner(RouterContext ctx, TCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(ConnectionRunner.class);
        _con = con;
        _keepRunning = false;
    }
    
    public void startRunning() {
        _keepRunning = true;
        
        String name = "TCP " + _context.routerHash().toBase64().substring(0,6) 
                      + " to " 
                      + _con.getRemoteRouterIdentity().calculateHash().toBase64().substring(0,6);
        I2PThread t = new I2PThread(this, name);
        t.start();
    }
    public void stopRunning() {
        _keepRunning = false;
    }
    
    public void run() {
        while (_keepRunning && !_con.getIsClosed()) {
            OutNetMessage msg = _con.getNextMessage();
            if ( (msg == null) && (_keepRunning) ) {
                _log.error("next message is null but we should keep running?");
            } else {
                sendMessage(msg);
            }
        }
    }
    
    private void sendMessage(OutNetMessage msg) {
        byte data[] = msg.getMessageData();
        if (data == null) {            
            if (_log.shouldLog(Log.WARN))
                _log.warn("message " + msg.getMessageType() + "/" + msg.getMessageId() 
                          + " expired before it could be sent");
            _con.sent(msg, false, 0);
            return;
        }

        OutputStream out = _con.getOutputStream();
        boolean ok = false;
        long before = -1;
        long after = -1;
        try {
            synchronized (out) {
                before = _context.clock().now();
                out.write(data);
                out.flush();
                after = _context.clock().now();
            }
            
            ok = true;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the message", ioe);
        }
        _con.sent(msg, ok, after - before);
    }
}
