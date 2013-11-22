package net.i2p.i2ptunnel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SystemVersion;

/**
 * Coordinate a set of tunnels within the JVM, loading and storing their config
 * to disk, and building new ones as requested.
 *
 * This is the entry point from clients.config.
 */
public class TunnelControllerGroup implements ClientApp {
    private final Log _log;
    private volatile ClientAppState _state;
    private final I2PAppContext _context;
    private final ClientAppManager _mgr;
    private static volatile TunnelControllerGroup _instance;
    static final String DEFAULT_CONFIG_FILE = "i2ptunnel.config";
    
    private final List<TunnelController> _controllers;
    private final String _configFile;
    
    private static final String REGISTERED_NAME = "i2ptunnel";

    /** 
     * Map of I2PSession to a Set of TunnelController objects 
     * using the session (to prevent closing the session until
     * no more tunnels are using it)
     *
     */
    private final Map<I2PSession, Set<TunnelController>> _sessions;
    
    /**
     *  In I2PAppContext will instantiate if necessary and always return non-null.
     *  As of 0.9.4, when in RouterContext, will return null (except in Android)
     *  if the TCG has not yet been started by the router.
     *
     *  @throws IllegalArgumentException if unable to load from i2ptunnel.config
     */
    public static TunnelControllerGroup getInstance() { 
        synchronized (TunnelControllerGroup.class) {
            if (_instance == null) {
                I2PAppContext ctx = I2PAppContext.getGlobalContext();
                if (SystemVersion.isAndroid() || !ctx.isRouterContext()) {
                    _instance = new TunnelControllerGroup(ctx, null, null);
                    _instance.startup();
                } // else wait for the router to start it
            }
            return _instance; 
        }
    }

    /**
     *  Instantiation only. Caller must call startup().
     *  Config file problems will not throw exception until startup().
     *
     *  @param mgr may be null
     *  @param args one arg, the config file, if not absolute will be relative to the context's config dir,
     *              if empty or null, the default is i2ptunnel.config
     *  @since 0.9.4
     */
    public TunnelControllerGroup(I2PAppContext context, ClientAppManager mgr, String[] args) {
        _state = UNINITIALIZED;
        _context = context;
        _mgr = mgr;
        _log = _context.logManager().getLog(TunnelControllerGroup.class);
        _controllers = new ArrayList<TunnelController>();
        if (args == null || args.length <= 0)
            _configFile = DEFAULT_CONFIG_FILE;
        else if (args.length == 1)
            _configFile = args[0];
        else
            throw new IllegalArgumentException("Usage: TunnelControllerGroup [filename]");
        _sessions = new HashMap<I2PSession, Set<TunnelController>>(4);
        synchronized (TunnelControllerGroup.class) {
            if (_instance == null)
                _instance = this;
        }
        if (_instance != this) {
            _log.logAlways(Log.WARN, "New TunnelControllerGroup, now you have two");
            if (_log.shouldLog(Log.WARN))
                _log.warn("I did it", new Exception());
        }
        _state = INITIALIZED;
    }

    /**
     *  @param args one arg, the config file, if not absolute will be relative to the context's config dir,
     *              if no args, the default is i2ptunnel.config
     *  @throws IllegalArgumentException if unable to load from config from file
     */
    public static void main(String args[]) {
        synchronized (TunnelControllerGroup.class) {
            if (_instance != null) return; // already loaded through the web
            _instance = new TunnelControllerGroup(I2PAppContext.getGlobalContext(), null, args);
            _instance.startup();
        }
    }

    /**
     *  ClientApp interface
     *  @throws IllegalArgumentException if unable to load config from file
     *  @since 0.9.4
     */
    public void startup() {
        loadControllers(_configFile);
        if (_mgr != null)
            _mgr.register(this);
            // RouterAppManager registers its own shutdown hook
        else
            _context.addShutdownTask(new Shutdown());
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public ClientAppState getState() {
        return _state;
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public String getName() {
        return REGISTERED_NAME;
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public String getDisplayName() {
        return REGISTERED_NAME;
    }

    /**
     *  @since 0.9.4
     */
    private void changeState(ClientAppState state) {
        changeState(state, null);
    }

    /**
     *  @since 0.9.4
     */
    private synchronized void changeState(ClientAppState state, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, null, e);
    }

    /**
     *  Warning - destroys the singleton!
     *  @since 0.8.8
     */
    private class Shutdown implements Runnable {
        public void run() {
            shutdown();
        }
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public void shutdown(String[] args) {
        shutdown();
    }

    /**
     *  Warning - destroys the singleton!
     *  Caller must root a new context before calling instance() or main() again.
     *  Agressively kill and null everything to reduce memory usage in the JVM
     *  after stopping, and to recognize what must be reinitialized on restart (Android)
     *
     *  @since 0.8.8
     */
    public synchronized void shutdown() {
        if (_state != STARTING && _state != RUNNING)
            return;
        changeState(STOPPING);
        if (_mgr != null)
            _mgr.unregister(this);
        unloadControllers();
        synchronized (TunnelControllerGroup.class) {
            if (_instance == this)
                _instance = null;
        }
/// fixme static
        I2PTunnelClientBase.killClientExecutor();
        changeState(STOPPED);
    }
    
    /**
     * Load up all of the tunnels configured in the given file (but do not start
     * them)
     *
     * DEPRECATED for use outside this class. Use startup() or getInstance().
     *
     * @throws IllegalArgumentException if unable to load from file
     */
    public synchronized void loadControllers(String configFile) {
        changeState(STARTING);
        Properties cfg = loadConfig(configFile);
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
        changeState(RUNNING);
    }
    
    private class StartControllers implements Runnable {
        public void run() {
            synchronized(TunnelControllerGroup.this) {
                for (int i = 0; i < _controllers.size(); i++) {
                    TunnelController controller = _controllers.get(i);
                    if (controller.getStartOnLoad())
                        controller.startTunnel();
                }
            }
        }
    }
    
    /**
     * Stop all tunnels, reload config, and restart those configured to do so.
     * WARNING - Does NOT simply reload the configuration!!! This is probably not what you want.
     *
     * @throws IllegalArgumentException if unable to reload config file
     */
    public synchronized void reloadControllers() {
        unloadControllers();
        loadControllers(_configFile);
    }
    
    /**
     * Stop and remove reference to all known tunnels (but dont delete any config
     * file or do other silly things)
     *
     */
    public synchronized void unloadControllers() {
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
    public synchronized void addController(TunnelController controller) { _controllers.add(controller); }
    
    /**
     * Stop and remove the given tunnel
     *
     * @return list of messages from the controller as it is stopped
     */
    public synchronized List<String> removeController(TunnelController controller) {
        if (controller == null) return new ArrayList<String>();
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
    public synchronized List<String> stopAllControllers() {
        List<String> msgs = new ArrayList<String>();
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
    public synchronized List<String> startAllControllers() {
        List<String> msgs = new ArrayList<String>();
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
    public synchronized List<String> restartAllControllers() {
        List<String> msgs = new ArrayList<String>();
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
    public synchronized List<String> clearAllMessages() {
        List<String> msgs = new ArrayList<String>();
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
    public synchronized void saveConfig(String configFile) throws IOException {
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
     * @return properties loaded
     * @throws IllegalArgumentException if unable to load from file
     */
    private synchronized Properties loadConfig(String configFile) {
        File cfgFile = new File(configFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), configFile);
        if (!cfgFile.exists()) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to load the controllers from " + cfgFile.getAbsolutePath());
            throw new IllegalArgumentException("Unable to load the controllers from " + cfgFile.getAbsolutePath());
        }
        
        Properties props = new Properties();
        try {
            DataHelper.loadProps(props, cfgFile);
            return props;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the controllers from " + cfgFile.getAbsolutePath(), ioe);
            throw new IllegalArgumentException("Error reading the controllers from " + cfgFile.getAbsolutePath(), ioe);
        }
    }
    
    /**
     * Retrieve a list of tunnels known
     *
     * @return list of TunnelController objects
     */
    public synchronized List<TunnelController> getControllers() {
        return new ArrayList<TunnelController>(_controllers);
    }
    
    
    /** 
     * Note the fact that the controller is using the session so that
     * it isn't destroyed prematurely.
     *
     */
    void acquire(TunnelController controller, I2PSession session) {
        synchronized (_sessions) {
            Set<TunnelController> owners = _sessions.get(session);
            if (owners == null) {
                owners = new HashSet<TunnelController>(2);
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
