package net.i2p.router.admin;

import net.i2p.util.Log;
import net.i2p.util.I2PThread;

import net.i2p.router.Service;
import net.i2p.router.Router;

public class AdminManager implements Service {
    private final static Log _log = new Log(AdminManager.class);
    private final static AdminManager _instance = new AdminManager();
    public final static AdminManager getInstance() { return _instance; }
    public final static String PARAM_ADMIN_PORT = "router.adminPort";
    public final static int DEFAULT_ADMIN_PORT = 7655;
    
    private AdminListener _listener;
    
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
	String str = Router.getInstance().getConfigSetting(PARAM_ADMIN_PORT);
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
	_listener = new AdminListener(port);
	I2PThread t = new I2PThread(_listener);
	t.setName("Admin Listener");
	t.setDaemon(true);
	t.setPriority(Thread.MIN_PRIORITY);
	t.start();
    }
}
