package net.i2p.myi2p;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Main controller for a MyI2P node, coordinating all network communication and
 * distributing messages to the appropriate services.
 *
 */
public class Node {
    private static List _nodes = new ArrayList(1);
    /**
     * Return a list of Node instances that are currently 
     * operating in the JVM
     */
    private static List nodes() { 
        synchronized (_nodes) {
            return new ArrayList(_nodes);
        }
    }
    
    private I2PAppContext _context;
    private Log _log;
    private NodeAdapter _adapter;
    /** 
     * contains configuration properties, such where our router is, what 
     * services to run, etc
     *
     */
    private Properties _config;
    /** filename where _config is stored */
    private String _configFile = DEFAULT_CONFIG_FILE;
    /** mapping of service name (String) to Service for all services loaded */
    private Map _services;
    
    private static final String DEFAULT_CONFIG_FILE = "myi2p.config";
    private static final String DEFAULT_KEY_FILE = "myi2p.keys";
    private static final String PROP_KEY_FILE = "keyFile";
    
    public Node(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(Node.class);
        _config = new Properties();
        _services = new HashMap(1);
        if (_log.shouldLog(Log.CRIT))
            _log.log(Log.CRIT, "Node created");
        _adapter = new NodeAdapter(_context, this);
    }
    
    /**
     * Main driver for the node.  Usage: <code>Node [configFile]</code>
     *
     */
    public static void main(String args[]) {
        String filename = DEFAULT_CONFIG_FILE;
        if ( (args != null) && (args.length == 1) )
            filename = args[0];
        Node node = new Node(I2PAppContext.getGlobalContext());
        node.setConfigFile(filename);
        node.loadConfig();
        node.startup();
        while (true) {
            synchronized (node) {
                try { node.wait(); } catch (InterruptedException ie) {}
            }
        }
    }
    
    public Properties getConfig() { 
        synchronized (_config) {
            return new Properties(_config); 
        }
    }
    public void setConfig(Properties props) { 
        synchronized (_config) {
            _config.clear();
            _config.putAll(props);
        }
    }
    
    public String getConfigFile() { return _configFile; }
    public void setConfigFile(String filename) { _configFile = filename; }
    
    /**
     * Load up the config and all of the services
     *
     */
    public void loadConfig() {
        FileInputStream fis = null;
        try {
            File cfgFile = new File(_configFile);
            if (cfgFile.exists()) {
                fis = new FileInputStream(cfgFile);
                Properties props = new Properties();
                props.load(fis);
                setConfig(props);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Config loaded from " + _configFile);
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Config file " + _configFile + " does not exist, so we aren't going to do much");
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading config file " + _configFile, ioe);
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
    }
    
    /**
     * Boot up the node, connect to the router, and start all the services
     *
     */
    public void startup() {
        boolean connected = connect();
        if (connected) {
            loadServices();
            startServices();
            synchronized (_nodes) {
                _nodes.add(this);
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Node started");
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to connect, startup didn't do much");
        }
    }
    
    /**
     * Drop any connections to the network and stop all services
     *
     */
    public void shutdown() {
        disconnect();
        stopServices();
        synchronized (_nodes) {
            _nodes.remove(this);
        }
    }
    
    /**
     * Send a message from the node to the peer as specified in the message
     *
     * @return true if it was sent
     */
    public boolean sendMessage(MyI2PMessage msg) {
        return _adapter.sendMessage(msg);
    }
    
    private void loadServices() {
        Properties config = getConfig();
        int i = 0;
        while (true) {
            String classname = config.getProperty("service."+i+".classname");
            if ( (classname == null) || (classname.trim().length() <= 0) )
                break;
            boolean ok = loadService("service." + i + ".", config);
            if (ok) i++;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(i + " services loaded");
    }
    
    private boolean loadService(String prefix, Properties config) {
        String classname = config.getProperty(prefix + "classname");
        String type = config.getProperty(prefix + "type");
        if (type == null) return false;
        
        Properties opts = new Properties();
        int i = 0;
        while (true) {
            String name = config.getProperty(prefix + "option." + i + ".name");
            String value = config.getProperty(prefix + "option." + i + ".value");
            if ( (name == null) || (name.trim().length() <= 0) || (value == null) || (value.trim().length() <= 0) )
                break;
            opts.setProperty(name.trim(), value.trim());
            i++;
        }
        
        try {
            Class cls = Class.forName(classname);
            Object obj = cls.newInstance();
            if (obj instanceof Service) {
                Service service = (Service)obj;
                service.setType(type);
                service.setOptions(opts);
                service.setNode(this);
                service.setContext(_context);
                synchronized (_services) {
                    _services.put(type, service);
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info("Service " + type + " loaded");
                return true;
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error loading service " + type + ": not a service [" + classname + "]");
            }
        } catch (ClassNotFoundException cnfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error loading service " + type + ": class " + classname + " is invalid", cnfe);
        } catch (InstantiationException ie) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error instantiating service " + type + ": class " + classname + " could not be created", ie);
        } catch (IllegalAccessException iae) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error creating service " + type + ": class " + classname + " could not be accessed", iae);
        }
        return false;
    }
    
    private boolean connect() {
        Properties config = getConfig();
        File keyFile = new File(config.getProperty(PROP_KEY_FILE, DEFAULT_KEY_FILE));
        return _adapter.connect(config, keyFile);
    }
    
    private void disconnect() {
        _adapter.disconnect();
    }
    
    private void startServices() {
        for (Iterator iter = _services.values().iterator(); iter.hasNext(); ) {
            Service service = (Service)iter.next();
            service.startup();
        }
    }
    private void stopServices() {
        for (Iterator iter = _services.values().iterator(); iter.hasNext(); ) {
            Service service = (Service)iter.next();
            service.shutdown();
        }
    }
    
    void handleMessage(MyI2PMessage msg) {
        Service service = (Service)_services.get(msg.getServiceType());
        if (service == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Message received for an unknown service [" 
                           + msg.getServiceType() + "] from " 
                           + msg.getPeer().calculateHash().toBase64());
        } else {
            service.receiveMessage(msg);
        }
    }
}
