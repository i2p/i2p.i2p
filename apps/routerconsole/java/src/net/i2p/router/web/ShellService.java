/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * ShellService.java
 * 2021 The I2P Project
 * http://www.geti2p.net
 * This code is public domain.
 */

package net.i2p.router.web;

import java.io.File;

import java.util.Arrays;
import java.util.ArrayList;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.util.Log;

/**
 * Alternative to ShellCommand for plugins based on ProcessBuilder, which
 * manages
 * a process and keeps track of it's state by maintaining a Process object.
 *
 * Keeps track of the process, and reports start/stop status correctly
 * on configplugins. When running a ShellService from a clients.config file,
 * the user MUST pass -shellservice.name in the args field in clients.config
 * to override the plugin name. The name passed to -shellservice.name should
 * be unique to avoid causing issues.
 * (https://i2pgit.org/i2p-hackers/i2p.i2p/-/merge_requests/39#note_4234)
 * -shellservice.displayName is optional and configures the name of the plugin
 * which is shown on the console. In most cases, the -shellservice.name must be
 * the same as the plugin name in order for the $PLUGIN field in clients.config
 * to match the expected value. If this is not the case, i.e.
 * (-shellservice.name != plugin.name), you must not use $PLUGIN in your
 * clients.config file.
 *
 * The recommended way to use this tool is to manage a single forked
 * app/process,
 * with a single ShellService, in a single plugin.
 *
 * When you are writing your clients.config file, please take note that $PLUGIN
 * will be derived from the `shellservice.name` field in the config file args.
 *
 * Works on Windows, OSX, and Linux.
 *
 * @author eyedeekay
 * @since 1.6.0/0.9.52, moved from net.i2p.app in 0.9.53
 */
public class ShellService implements ClientApp {
    private static final String NAME_OPTION = "-shellservice.name";
    private static final String DISPLAY_NAME_OPTION = "-shellservice.displayname";
    private static final String PLUGIN_DIR = "plugins";

    private final Log _log;
    private final ProcessBuilder _pb;
    private final I2PAppContext _context;
    private final ClientAppManager _cmgr;
    private final File _errorLog;
    private final File _outputLog;

    private ClientAppState _state = ClientAppState.UNINITIALIZED;

    private volatile String name = "unnamedClient";
    private volatile String displayName = "unnamedClient";

    private Process _p;

    public ShellService(I2PAppContext context, ClientAppManager listener, String[] args) {
        _context = context;
        _cmgr = listener;
        _log = context.logManager().getLog(ShellService.class);

        String[] procArgs = trimArgs(args);

        String process = Arrays.toString(procArgs);

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Process: " + process);
            _log.debug("Name: " + this.getName() + ", DisplayName: " + this.getDisplayName());
        }

        _pb = new ProcessBuilder(process);

        File pluginDir = new File(_context.getConfigDir(), PLUGIN_DIR + '/' + this.getName());
        _errorLog = new File(pluginDir, "error.log");
        _outputLog = new File(pluginDir, "output.log");
        _pb.redirectOutput(_outputLog);
        _pb.redirectError(_errorLog);
        _pb.directory(pluginDir);
        changeState(ClientAppState.INITIALIZED, "ShellService: " + getName() + " setup and initialized");
    }

    private String[] trimArgs(String[] args) {
        ArrayList<String> newargs = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(NAME_OPTION)) {
                if (args[i].contains("=")) {
                    name = args[i].split("=")[1];
                } else {
                    name = args[i + 1];
                    i++;
                }
            } else if (args[i].startsWith(DISPLAY_NAME_OPTION)) {
                if (args[i].contains("=")) {
                    displayName = args[i].split("=")[1];
                } else {
                    displayName = args[i + 1];
                    i++;
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Adding arg: " + args[i]);
                newargs.add(args[i]);
            }
        }
        if (getName() == null)
            throw new IllegalArgumentException(
                    "ShellService: ShellService passed with args=" + Arrays.toString(args) + " must have a name");
        if (getDisplayName() == null)
            displayName = name;
        String arr[] = new String[newargs.size()];
        return newargs.toArray(arr);
    }

    private synchronized void changeState(ClientAppState newState, String message, Exception ex) {
        if (_state != newState) {
            _state = newState;
            _cmgr.notify(this, newState, message, ex);
        }
    }

    private synchronized void changeState(ClientAppState newState, String message) {
        changeState(newState, message, null);
    }

    /**
     * Determine if a ShellService corresponding to the wrapped application
     * has been started yet. If it hasn't, attempt to start the process and
     * notify the router that it has been started.
     */
    public synchronized void startup() throws Throwable {
        if (getName().equals("unnamedClient")) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ShellService has no name, not starting");
            return;
        }
        changeState(ClientAppState.STARTING, "ShellService: " + getName() + " starting");
        boolean start = isProcessStopped();
        if (start) {
            _p = _pb.start();
            if (!_p.isAlive() && _log.shouldLog(Log.ERROR))
                _log.error("Error getting Process of application from recently instantiated shellservice" + getName());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Started " + getName() + "process");
        }
        if (!_p.isAlive())
            changeState(ClientAppState.RUNNING, "ShellService: " + getName() + " started");
        Boolean reg = _cmgr.register(this);
        if (reg) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("ShellService: " + getName() + " registered with the router");
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ShellService: " + getName() + " failed to register with the router");
            _cmgr.unregister(this);
            _cmgr.register(this);
        }
        return;
    }

    /**
     * Determine if the process running or not.
     *
     * @return {@code true} if the Process is NOT running, {@code false} if the
     *         Process is
     *         running
     */
    public boolean isProcessStopped() {
        return !isProcessRunning();
    }

    /**
     * Determine if the process running or not.
     *
     * @return {@code true} if the Process is running, {@code false} if the Process
     *         is
     *         not running
     */
    public boolean isProcessRunning() {
        if (_p == null)
            return false;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Checking process status " + getName() + _p.isAlive());
        return _p.isAlive();
    }

    /**
     * Shut down the process by calling Process.destroy()
     *
     * @param args generally null but could be stopArgs from clients.config
     */
    public synchronized void shutdown(String[] args) throws Throwable {
        if (getName().equals("unnamedClient")) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ShellService has no name, not shutting down");
            return;
        }
        changeState(ClientAppState.STOPPING, "ShellService: " + getName() + " stopping");
        if (_p != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stopping " + getName() + "process started with ShellService " + getName());
            _p.destroy();
        }
        changeState(ClientAppState.STOPPED, "ShellService: " + getName() + " stopped");
        _cmgr.unregister(this);
    }

    /**
     * Query the state of managed process and determine if it is running
     * or not. Convert to corresponding ClientAppState and return the correct
     * value.
     *
     * @return non-null
     */
    public ClientAppState getState() {
        if (!isProcessRunning()) {
            changeState(ClientAppState.STOPPED, "ShellService: " + getName() + " stopped");
            _cmgr.unregister(this);
        }
        return _state;
    }

    /**
     * The generic name of the ClientApp, used for registration,
     * e.g. "console". Do not translate. Has a special use in the context of
     * ShellService, must match the plugin name.
     *
     * @return non-null
     */
    public String getName() {
        return name;
    }

    /**
     * The display name of the ClientApp, used in user interfaces.
     * The app must translate.
     *
     * @return non-null
     */
    public String getDisplayName() {
        return displayName;
    }

}
