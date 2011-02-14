package net.i2p.i2ptunnel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 * Coordinate a set of tunnels within the JVM, loading and storing their config
 * to disk, and building new ones as requested.
 *
 * Warning - this is a singleton. Todo: fix
 */
public class TunnelControllerGroup {
    private final Log _log;
    private static TunnelControllerGroup _instance;
    static final String DEFAULT_CONFIG_FILE = "i2ptunnel.config";
    
    private final List<TunnelController> _controllers;
    private String _configFile = DEFAULT_CONFIG_FILE;
    
    /** 
     * Map of I2PSession to a Set of TunnelController objects 
     * using the session (to prevent closing the session until
     * no more tunnels are using it)
     *
     */
    private final Map<I2PSession, Set<TunnelController>> _sessions;
    
    public static TunnelControllerGroup getInstance() { 
        synchronized (TunnelControllerGroup.class) {
            if (_instance == null)
                _instance = new TunnelControllerGroup(DEFAULT_CONFIG_FILE);
            return _instance; 
        }
    }

    private TunnelControllerGroup(String configFile) {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(TunnelControllerGroup.class);
        _controllers = Collections.synchronizedList(new ArrayList());
        _configFile = configFile;
        _sessions = new HashMap(4);
        loadControllers(_configFile);
    }

    public static void main(String args[]) {
        synchronized (TunnelControllerGroup.class) {
            if (_instance != null) return; // already loaded through the web
            
            if ( (args == null) || (args.length <= 0) ) {
                _instance = new TunnelControllerGroup(DEFAULT_CONFIG_FILE);
            } else if (args.length == 1) {
                _instance = new TunnelControllerGroup(args[0]);
            } else {
                System.err.println("Usage: TunnelControllerGroup [filename]");
                return;
            }
        }
    }
    
    /**
     * Load up all of the tunnels configured in the given file (but do not start
     * them)
     *
     */
    public void loadControllers(String configFile) {
        Properties cfg = loadConfig(configFile);
        if (cfg == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to load the config from " + configFile);
            return;
        }
        int i = 0; 
        while (true) {
            String type = cfg.getProperty("tunnel." + i + ".type");
            if (type == null) 
                break;
            TunnelController controller = new TunnelController(cfg, "tunnel." + i + ".");
            _controllers.add(controller);
            i++;
        }
        I2PAppThread startupThread = new I2PAppThread(new StartControllers(), "Startup tunnels");
        startupThread.start();
        
        if (_log.shouldLog(Log.INFO))
            _log.info(i + " controllers loaded from " + configFile);
    }
    
    private class StartControllers implements Runnable {
        public void run() {
            for (int i = 0; i < _controllers.size(); i++) {
                TunnelController controller = _controllers.get(i);
                if (controller.getStartOnLoad())
                    controller.startTunnel();
            }
        }
    }
    
    
    public void reloadControllers() {
        unloadControllers();
        loadControllers(_configFile);
    }
    
    /**
     * Stop and remove reference to all known tunnels (but dont delete any config
     * file or do other silly things)
     *
     */
    public void unloadControllers() {
        stopAllControllers();
        _controllers.clear();
        if (_log.shouldLog(Log.INFO))
            _log.info("All controllers stopped and unloaded");
    }
    
    /**
     * Add the given tunnel to the set of known controllers (but dont add it to
     * a config file or start it or anything)
     *
     */
    public void addController(TunnelController controller) { _controllers.add(controller); }
    
    /**
     * Stop and remove the given tunnel
     *
     * @return list of messages from the controller as it is stopped
     */
    public List<String> removeController(TunnelController controller) {
        if (controller == null) return new ArrayList();
        controller.stopTunnel();
        List<String> msgs = controller.clearMessages();
        _controllers.remove(controller);
        msgs.add("Tunnel " + controller.getName() + " removed");
        return msgs;
    }
    
    /**
     * Stop all tunnels
     *
     * @return list of messages the tunnels generate when stopped
     */
    public List<String> stopAllControllers() {
        List<String> msgs = new ArrayList();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = _controllers.get(i);
            controller.stopTunnel();
            msgs.addAll(controller.clearMessages());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(_controllers.size() + " controllers stopped");
        return msgs;
    }
    
    /**
     * Start all tunnels
     *
     * @return list of messages the tunnels generate when started
     */
    public List<String> startAllControllers() {
        List<String> msgs = new ArrayList();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = _controllers.get(i);
            controller.startTunnelBackground();
            msgs.addAll(controller.clearMessages());
        }

        if (_log.shouldLog(Log.INFO))
            _log.info(_controllers.size() + " controllers started");
        return msgs;
    }
    
    /**
     * Restart all tunnels
     *
     * @return list of messages the tunnels generate when restarted
     */
    public List<String> restartAllControllers() {
        List<String> msgs = new ArrayList();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = _controllers.get(i);
            controller.restartTunnel();
            msgs.addAll(controller.clearMessages());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(_controllers.size() + " controllers restarted");
        return msgs;
    }
    
    /**
     * Fetch all outstanding messages from any of the known tunnels
     *
     * @return list of messages the tunnels have generated
     */
    public List<String> clearAllMessages() {
        List<String> msgs = new ArrayList();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = _controllers.get(i);
            msgs.addAll(controller.clearMessages());
        }
        return msgs;
    }
    
    /**
     * Save the configuration of all known tunnels to the default config 
     * file
     *
     */
    public void saveConfig() throws IOException {
        saveConfig(_configFile);
    }

    /**
     * Save the configuration of all known tunnels to the given file
     *
     */
    public void saveConfig(String configFile) throws IOException {
        _configFile = configFile;
        File cfgFile = new File(configFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), configFile);
        File parent = cfgFile.getParentFile();
        if ( (parent != null) && (!parent.exists()) )
            parent.mkdirs();
        
        Properties map = new OrderedProperties();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = _controllers.get(i);
            Properties cur = controller.getConfig("tunnel." + i + ".");
            map.putAll(cur);
        }
        
        DataHelper.storeProps(map, cfgFile);
    }
    
    /**
     * Load up the config data from the file
     *
     * @return properties loaded or null if there was an error
     */
    private Properties loadConfig(String configFile) {
        File cfgFile = new File(configFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), configFile);
        if (!cfgFile.exists()) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to load the controllers from " + cfgFile.getAbsolutePath());
            return null;
        }
        
        Properties props = new Properties();
        try {
            DataHelper.loadProps(props, cfgFile);
            return props;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the controllers from " + cfgFile.getAbsolutePath(), ioe);
            return null;
        }
    }
    
    /**
     * Retrieve a list of tunnels known
     *
     * @return list of TunnelController objects
     */
    public List<TunnelController> getControllers() { return _controllers; }
    
    
    /** 
     * Note the fact that the controller is using the session so that
     * it isn't destroyed prematurely.
     *
     */
    void acquire(TunnelController controller, I2PSession session) {
        synchronized (_sessions) {
            Set<TunnelController> owners = _sessions.get(session);
            if (owners == null) {
                owners = new HashSet(2);
                _sessions.put(session, owners);
            }
            owners.add(controller);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Acquiring session " + session + " for " + controller);

    }
    
    /** 
     * Note the fact that the controller is no longer using the session, and if
     * no other controllers are using it, destroy the session.
     *
     */
    void release(TunnelController controller, I2PSession session) {
        boolean shouldClose = false;
        synchronized (_sessions) {
            Set<TunnelController> owners = _sessions.get(session);
            if (owners != null) {
                owners.remove(controller);
                if (owners.isEmpty()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("After releasing session " + session + " by " + controller + ", no more owners remain");
                    shouldClose = true;
                    _sessions.remove(session);
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("After releasing session " + session + " by " + controller + ", " + owners.size() + " owners remain");
                    shouldClose = false;
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("After releasing session " + session + " by " + controller + ", no owners were even known?!");
                shouldClose = true;
            }
        }
        if (shouldClose) {
            try {
                session.destroySession();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Session destroyed: " + session);
            } catch (I2PSessionException ise) {
                _log.error("Error closing the client session", ise);
            }
        }
    }
}
