package net.i2p.router.admin;

import java.io.Writer;

import net.i2p.router.RouterContext;
import net.i2p.router.Service;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

public class AdminManager implements Service {
    private Log _log;
    private RouterContext _context;
    public final static String PARAM_ADMIN_PORT = "router.adminPort";
    public final static int DEFAULT_ADMIN_PORT = 7655;
    
    private AdminListener _listener;
    
    public AdminManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(AdminManager.class);
    }
    
    public void renderStatusHTML(Writer out) { }
    
    public void shutdown() {
        if (_listener != null) {
            _log.info("Shutting down admin listener");
            _listener.shutdown();
            _listener = null;
        }
    }
    
    public void restart() {
        startup();
    }
    
    public void startup() {
        int port = DEFAULT_ADMIN_PORT;
        String str = _context.router().getConfigSetting(PARAM_ADMIN_PORT);
        if (str != null) {
            try {
                int val = Integer.parseInt(str);
                port = val;
                _log.info("Starting up admin listener on port " + port);
            } catch (NumberFormatException nfe) {
                _log.warn("Invalid admin port specified [" + str + "], using the default " + DEFAULT_ADMIN_PORT, nfe);
            }
        } else {
            _log.warn("Router admin port not specified, using the default " + DEFAULT_ADMIN_PORT);
        }
        startup(port);
    }
    
    private void startup(int port) {
        if (_listener == null) {
            _listener = new AdminListener(_context, port);
            I2PThread t = new I2PThread(_listener);
            t.setName("Admin Listener:" + port);
            t.setDaemon(true);
            //t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } else {
            _listener.setPort(port);
            _listener.restart();
        }
    }
}
