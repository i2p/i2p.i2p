package net.i2p.router.admin;

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
    
    public String renderStatusHTML() { return ""; }
    
    public void shutdown() {
        if (_listener != null) {
            _log.info("Shutting down admin listener");
            _listener.shutdown();
            _listener = null;
        }
    }
    
    public void startup() {
        int port = DEFAULT_ADMIN_PORT;
        String str = _context.router().getConfigSetting(PARAM_ADMIN_PORT);
        if (str != null) {
            try {
                int val = Integer.parseInt(str);
                port = val;
            } catch (NumberFormatException nfe) {
                _log.warn("Invalid admin port specified [" + str + "]", nfe);
            }
        }
        _log.info("Starting up admin listener on port " + port);
        startup(port);
    }
    
    private void startup(int port) {
        _listener = new AdminListener(_context, port);
        I2PThread t = new I2PThread(_listener);
        t.setName("Admin Listener");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
