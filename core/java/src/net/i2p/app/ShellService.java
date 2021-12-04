/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * ShellService.java
 * 2021 The I2P Project
 * http://www.geti2p.net
 * This code is public domain.
 */

package net.i2p.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.util.Arrays;
import java.util.ArrayList;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.ShellCommand;
import net.i2p.util.SystemVersion;

/**
 * Alternative to ShellCommand based on ProcessBuilder, which manages
 * a process and keeps track of it's state by PID when a plugin cannot be
 * managed otherwise. Eliminates the need for a bespoke shell script to manage
 * application state for forked plugins.
 *
 * Keeps track of the PID of the plugin, reports start/stop status correctly
 * on configplugins. When running a ShellService from a clients.config file,
 * the user MUST pass -shellservice.name in the args field in clients.config
 * to override the plugin name. The name passed to -shellservice.name should
 * be unique to avoid causing issues. (https://i2pgit.org/i2p-hackers/i2p.i2p/-/merge_requests/39#note_4234)
 * -shellservice.displayName is optional and configures the name of the plugin
 * which is shown on the console. In most cases, the -shellservice.name must be
 * the same as the plugin name in order for the $PLUGIN field in clients.config
 * to match the expected value. If this is not the case, i.e.
 * (-shellservice.name != plugin.name), you must not use $PLUGIN in your
 * clients.config file.
 *
 * The recommended way to use this tool is to manage a single forked app/process,
 * with a single ShellService, in a single plugin.
 *
 * When you are writing your clients.config file, please take note that $PLUGIN
 * will be derived from the `shellservice.name` field in the config file args.
 *
 * Works on Windows, OSX, and Linux.
 *
 * @author eyedeekay
 * @since 1.6.0/0.9.52
 */
public class ShellService implements ClientApp {
    private static final String NAME_OPTION = "-shellservice.name";
    private static final String DISPLAY_NAME_OPTION = "-shellservice.displayname";
    private static final String PLUGIN_DIR = "plugins";

    private final Log _log;
    private final ProcessBuilder _pb;
    private final I2PAppContext _context;
    private final ClientAppManager _cmgr;

    private ClientAppState _state = ClientAppState.UNINITIALIZED;

    private volatile String name = "unnamedClient";
    private volatile String displayName = "unnamedClient";

    private Process _p;
    private volatile long _pid;

    public ShellService(I2PAppContext context, ClientAppManager listener, String[] args) {
        _context = context;
        _cmgr = listener;
        _log = context.logManager().getLog(ShellService.class);

        String[] procArgs = trimArgs(args);

        String process = writeScript(procArgs);

        if(_log.shouldLog(Log.DEBUG)){
            _log.debug("Process: " + process);
            _log.debug("Name: " + this.getName() + ", DisplayName: " + this.getDisplayName());
        }

        _pb = new ProcessBuilder(process);

        File pluginDir = new File(_context.getConfigDir(), PLUGIN_DIR + '/' + this.getName());
        _pb.directory(pluginDir);
        changeState(ClientAppState.INITIALIZED, "ShellService: "+getName()+" set up and initialized");
    }

    private String scriptArgs(String[] procArgs) {
        StringBuilder tidiedArgs = new StringBuilder();
        for (int i = 0; i < procArgs.length; i++) {
            tidiedArgs.append(" \"").append(procArgs[i]).append("\" ");
        }
        return tidiedArgs.toString();
    }

    private String batchScript(String[] procArgs) {
        if (_log.shouldLog(Log.DEBUG)) {
            String cmd = procArgs[0];
            _log.debug("cmd: " + cmd);
        }
        String script = "start \""+getName()+"\" "+scriptArgs(procArgs)+System.lineSeparator() +
                        "tasklist /V /FI \"WindowTitle eq "+getName()+"*\""+System.lineSeparator();
        return script;
    }

    private String shellScript(String[] procArgs) {
        String cmd = procArgs[0];
        if(_log.shouldLog(Log.DEBUG))
            _log.debug("cmd: " + cmd);
        File file = new File(cmd);
        if(file.exists()){
            if (!file.isDirectory() && !file.canExecute()) {
                file.setExecutable(true);
            }
        }
        String Script = "nohup "+scriptArgs(procArgs)+" 1>/dev/null 2>/dev/null & echo $!"+System.lineSeparator();
        return Script;
    }

    private void deleteScript() {
        File dir = _context.getTempDir();
        if (SystemVersion.isWindows()) {
            File bat = new File(dir, "shellservice-"+getName()+".bat");
            bat.delete();
        } else {
            File sh = new File(dir, "shellservice-"+getName()+".sh");
            sh.delete();
        }
    }

    private String writeScript(File dir, String extension, String[] procArgs){
        File script = new File(dir, "shellservice-"+getName()+extension);
        script.delete();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Writing Batch Script " + script.toString());
        FileWriter scriptWriter = null;
        try {
            script.createNewFile();
            scriptWriter = new FileWriter(script);
            if (extension.equals(".bat") || extension.equals(""))
                scriptWriter.write(batchScript(procArgs));
            else if (extension.equals(".sh"))
                scriptWriter.write(shellScript(procArgs));
            changeState(ClientAppState.INITIALIZED, "ShellService: "+getName()+" initialized");
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing wrapper script shellservice-" + getName() + extension, ioe);
            script.delete();
            changeState(ClientAppState.START_FAILED, "ShellService: "+getName()+" failed to start, error writing script.", ioe);
        } finally {
            try {
                if (scriptWriter != null)
                    scriptWriter.close();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.ERROR)){
                    _log.error("Error writing wrapper script shellservice-" + getName() + extension, ioe);
                    changeState(ClientAppState.START_FAILED, "ShellService: "+getName()+" failed to start, error closing script writer", ioe);
                }
            }
        }
        script.setExecutable(true);
        return script.getAbsolutePath();
    }

    private String writeScript(String[] procArgs){
        File dir = _context.getTempDir();
        if (SystemVersion.isWindows()) {
            return writeScript(dir, ".bat", procArgs);
        } else {
            return writeScript(dir, ".sh", procArgs);
        }
    }

    private String getPID() {
        return String.valueOf(_pid);
    }

    /**
     * Queries {@code tasklist} if the process ID {@code pid} is running.
     *
     * Contain code from Stack Overflow(https://stackoverflow.com/questions/2533984/java-checking-if-any-process-id-is-currently-running-on-windows/41489635)
     *
     * @param pid the PID to check
     * @return {@code true} if the PID is running, {@code false} otherwise
     */
    private boolean isProcessIdRunningOnWindows(String pid){
        try {
            String cmds[] = {"cmd", "/c", "tasklist /FI \"PID eq " + pid + "\""};
            ShellCommand _shellCommand = new ShellCommand();
            return _shellCommand.executeSilentAndWaitTimed(cmds, 240);
        } catch (Exception ex) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error checking if process is running", ex);
            changeState(ClientAppState.CRASHED, "ShellService: "+getName()+" status unknowable", ex);
        }
        return false;
    }

    private boolean isProcessIdRunningOnUnix(String pid) {
        try {
            String cmds[] = {"ps", "-p", pid};
            ShellCommand _shellCommand = new ShellCommand();
            return _shellCommand.executeSilentAndWaitTimed(cmds, 240);
        } catch (Exception ex) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error checking if process is running", ex);
            changeState(ClientAppState.CRASHED, "ShellService: "+getName()+" status unknowable", ex);
        }
        return false;
    }

    private boolean isProcessIdRunning(String pid) {
        boolean running = false;
        if (SystemVersion.isWindows()) {
            running = isProcessIdRunningOnWindows(pid);
        } else {
            running = isProcessIdRunningOnUnix(pid);
        }
        return running;
    }

    private long getPidOfProcess() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Finding the PID of: " + getName());
        if (isProcessIdRunning(getPID())) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Read PID in from " + getPID());
            return _pid;
        }
        BufferedInputStream bis = null;
        ByteArrayOutputStream buf = null;
        try {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Getting PID from output");
            if (_p == null) {
                if (_log.shouldLog(Log.WARN)) {
                    _log.warn("Process is null, something is wrong");
                }
                changeState(ClientAppState.CRASHED, "ShellService: "+getName()+" should be runnning but the process is null.");
                return -1;
            }
            bis = new BufferedInputStream(_p.getInputStream());
            buf = new ByteArrayOutputStream();
            for (int result = bis.read(); result != -1; result = bis.read()) {
                if (result == '\n')
                    break;
                buf.write((byte) result);
            }
            String pidString = buf.toString("UTF-8").replaceAll("[\\r\\n\\t ]", "");
            long pid = Long.parseLong(pidString);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Found " + getName() + "process with PID: " + pid);
            return pid;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error getting PID of application started by shellservice-" + getName() , ioe);
            changeState(ClientAppState.CRASHED, "ShellService: "+getName()+" PID could not be discovered", ioe);
        } finally {
            if (bis != null) {
                try {
                    bis.close();   // close the input stream
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error closing input stream", ioe);
                }
            }
            if (buf != null) {
                try {
                    buf.close();   // close the output stream
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error closing output stream", ioe);
                }
            }

        }
        return -1;
    }

    private String[] trimArgs(String[] args) {
        ArrayList<String> newargs = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if ( args[i].startsWith(NAME_OPTION) ) {
                if (args[i].contains("=")){
                    name = args[i].split("=")[1];
                }else{
                    name = args[i+1];
                    i++;
                }
            } else if ( args[i].startsWith(DISPLAY_NAME_OPTION) ) {
                if (args[i].contains("=")) {
                    displayName = args[i].split("=")[1];
                } else {
                    displayName = args[i+1];
                    i++;
                }
            } else {
                newargs.add(args[i]);
            }
        }
        if (getName() == null)
            throw new IllegalArgumentException("ShellService: ShellService passed with args=" + Arrays.toString(args) + " must have a name");
        if (getDisplayName() == null)
            displayName = name;
        String arr[] = new String[newargs.size()];
        return newargs.toArray(arr);
    }

    private synchronized void changeState(ClientAppState newState, String message, Exception ex){
        if (_state != newState) {
            _state = newState;
            _cmgr.notify(this, newState, message, ex);
        }
    }

    private synchronized void changeState(ClientAppState newState, String message){
        changeState(newState, message, null);
    }

    /**
     *  Determine if a ShellService corresponding to the wrapped application
     *  has been started yet. If it hasn't, attempt to start the process and
     *  notify the router that it has been started.
     */
    public synchronized void startup() throws Throwable {
        if (getName().equals("unnamedClient")){
            if (_log.shouldLog(Log.WARN))
                _log.warn("ShellService has no name, not starting");
            return;
        }
        changeState(ClientAppState.STARTING, "ShellService: "+getName()+" starting");
        boolean start = checkIsStopped();
        if (start) {
            _p = _pb.start();
            long pid = getPidOfProcess();
            if (pid == -1 && _log.shouldLog(Log.ERROR))
                _log.error("Error getting PID of application from recently instantiated shellservice" + getName());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Started " + getName() + "process with PID: " + pid);
            this._pid = pid;
            deleteScript();
        }
        changeState(ClientAppState.RUNNING, "ShellService: "+getName()+" started");
        Boolean reg = _cmgr.register(this);
        if (reg){
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("ShellService: "+getName()+" registered with the router");
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ShellService: "+getName()+" failed to register with the router");
            _cmgr.unregister(this);
            _cmgr.register(this);
        }
        return;
    }

    /**
     *  Determine if the PID found in "shellservice"+getName()+".pid" is
     *  running or not. Result is the answer to the question "Should I attempt
     *  to start the process" so returns false when PID corresponds to a running
     *  process and true if it does not.
     *
     *  Usage in PluginStarter.isClientThreadRunning requires the !inverse of
     *  the result.
     *
     *  @return {@code true} if the PID is NOT running, {@code false} if the PID is running
     */
    public boolean checkIsStopped() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Checking process status " + getName());
        return !isProcessIdRunning(getPID());
    }

    /**
     *  Query the stored PID of the previously launched ShellService and attempt
     *  to send SIGINT on Unix, SIGKILL on Windows in order to stop the wrapped
     *  application.
     *
     *  @param args generally null but could be stopArgs from clients.config
     */
    public synchronized void shutdown(String[] args) throws Throwable {
        String pid = getPID();
        if (getName().equals("unnamedClient")){
            if (_log.shouldLog(Log.WARN))
                _log.warn("ShellService has no name, not shutting down");
            return;
        }
        changeState(ClientAppState.STOPPING, "ShellService: "+getName()+" stopping");
        if (_p != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stopping " + getName() + "process started with ShellService, PID: " + pid);
            _p.destroy();
        }
        ShellCommand _shellCommand = new ShellCommand();
        if (SystemVersion.isWindows()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stopping " + getName() + "process with PID: " + pid + "on Windows");
            String cmd[] = {"cmd", "/c", "taskkill /F /T /PID " + pid};
            _shellCommand.executeSilentAndWaitTimed(cmd, 240);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stopping " + getName() + "process with PID: " + pid + "on Unix");
            String cmd[] = {"kill", pid};
            _shellCommand.executeSilentAndWaitTimed(cmd, 240);
        }
        deleteScript();
        changeState(ClientAppState.STOPPED, "ShellService: "+getName()+" stopped");
        _cmgr.unregister(this);
    }

    /**
     *  Query the PID of the wrapped application and determine if it is running
     *  or not. Convert to corresponding ClientAppState and return the correct
     *  value.
     *
     *  @return non-null
     */
    public ClientAppState getState() {
        String pid = getPID();
        if (!isProcessIdRunning(pid)) {
            changeState(ClientAppState.STOPPED, "ShellService: "+getName()+" stopped");
            _cmgr.unregister(this);
        }
        return _state;
    }

    /**
     *  The generic name of the ClientApp, used for registration,
     *  e.g. "console". Do not translate. Has a special use in the context of
     *  ShellService, it is used to name the file which contains the PID of the
     *  process ShellService is wrapping.
     *
     *  @return non-null
     */
    public String getName() {
        return name;
    }

    /**
     *  The display name of the ClientApp, used in user interfaces.
     *  The app must translate.
     *  @return non-null
     */
    public String getDisplayName() {
        return displayName;
    }

}
