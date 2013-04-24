package net.i2p.router.startup;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Notify the router of events, and provide methods for
 *  client apps to find each other.
 *
 *  @since 0.9.4
 */
public class RouterAppManager implements ClientAppManager {
    
    private final RouterContext _context;
    private final Log _log;
    // client to args
    // this assumes clients do not override equals()
    private final ConcurrentHashMap<ClientApp, String[]> _clients;
    // registered name to client
    private final ConcurrentHashMap<String, ClientApp> _registered;

    public RouterAppManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(RouterAppManager.class);
        _clients = new ConcurrentHashMap(16);
        _registered = new ConcurrentHashMap(8);
        ctx.addShutdownTask(new Shutdown());
    }

    /**
     *  @param args the args that were used to instantiate the app, non-null, may be zero-length
     *  @return success
     *  @throws IllegalArgumentException if already added
     */
    public boolean addAndStart(ClientApp app, String[] args) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Client " + app.getDisplayName() + " ADDED");
        String[] old = _clients.put(app, args);
        if (old != null)
            throw new IllegalArgumentException("already added");
        try {
            app.startup();
            return true;
        } catch (Throwable t) {
            _clients.remove(app);
            _log.error("Client " + app + " failed to start", t);
            return false;
        }
    }

    /**
     *  Get the first known ClientApp with this class name and exact arguments.
     *  Caller may then retrieve or control the state of the returned client.
     *  A client will generally be found only if it is running or transitioning;
     *  after it is stopped it will not be tracked by the manager.
     *
     *  @param args non-null, may be zero-length
     *  @return client app or null
     *  @since 0.9.6
     */
    public ClientApp getClientApp(String className, String[] args) {
        for (Map.Entry<ClientApp, String[]> e : _clients.entrySet()) {
            if (e.getKey().getClass().getName().equals(className) &&
                Arrays.equals(e.getValue(), args))
                return e.getKey();
        }
        return null;
    }

    // ClientAppManager methods

    /**
     *  Must be called on all state transitions except
     *  from UNINITIALIZED to INITIALIZED.
     *
     *  @param app non-null
     *  @param state non-null
     *  @param message may be null
     *  @param e may be null
     */
    public void notify(ClientApp app, ClientAppState state, String message, Exception e) {
        switch(state) {
          case UNINITIALIZED:
          case INITIALIZED:
            if (_log.shouldLog(Log.WARN))
                _log.warn("Client " + app.getDisplayName() + " is now " + state);
            break;

          case STARTING:
          case RUNNING:
            if (_log.shouldLog(Log.INFO))
                _log.info("Client " + app.getDisplayName() + " is now " + state);
            break;

          case FORKED:
          case STOPPING:
          case STOPPED:
            _clients.remove(app);
            _registered.remove(app.getName(), app);
            if (message == null)
                message = "";
            if (_log.shouldLog(Log.INFO))
                _log.info("Client " + app.getDisplayName() + " is now " + state +
                          ' ' + message, e);
            break;

          case CRASHED:
          case START_FAILED:
            _clients.remove(app);
            _registered.remove(app.getName(), app);
            if (message == null)
                message = "";
            _log.log(Log.CRIT, "Client " + app.getDisplayName() + ' ' + state +
                               ' ' + message, e);
            break;
        }
    }
    
    /**
     *  Register with the manager under the given name,
     *  so that other clients may find it.
     *  Only required for apps used by other apps.
     *
     *  @param app non-null
     *  @return true if successful, false if duplicate name
     */
    public boolean register(ClientApp app) {
        if (!_clients.containsKey(app))
            return false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Client " + app.getDisplayName() + " REGISTERED AS " + app.getName());
        // TODO if old app in there is not running and != this app, allow replacement
        return _registered.putIfAbsent(app.getName(), app) == null;
    }
    
    /**
     *  Unregister with the manager. Name must be the same as that from register().
     *  Only required for apps used by other apps.
     *
     *  @param app non-null
     */
    public void unregister(ClientApp app) {
        _registered.remove(app.getName(), app);
    }
    
    /**
     *  Get a registered app.
     *  Only used for apps finding other apps.
     *  Do not hold a static reference.
     *  If you only need to find a port, use the PortMapper instead.
     *
     *  @param name non-null
     *  @return client app or null
     */
    public ClientApp getRegisteredApp(String name) {
        return _registered.get(name);
    }

    /// end ClientAppManager interface

    /**
     *  @since 0.9.6
     */
    public synchronized void shutdown() {
        Set<ClientApp> apps = new HashSet(_clients.keySet());
        for (ClientApp app : apps) {
            ClientAppState state = app.getState();
            if (state == RUNNING || state == STARTING) {
                try {
                    app.shutdown(null);
                } catch (Throwable t) {}
            }
        }
    }

    /**
     *  @since 0.9.6
     */
    public class Shutdown implements Runnable {
        public void run() {
            shutdown();
        }
    }

    /**
     *  debug
     *  @since 0.9.6
     */
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h2>App Manager</h2>");
        buf.append("<h3>Tracked</h3>");
        toString1(buf);
        buf.append("<h3>Registered</h3>");
        toString2(buf);
        out.write(buf.toString());
    }

    /**
     *  debug
     *  @since 0.9.6
     */
    private void toString1(StringBuilder buf) {
        List<String> list = new ArrayList(_clients.size());
        for (Map.Entry<ClientApp, String[]> entry : _clients.entrySet()) {
            ClientApp key = entry.getKey();
            String[] val = entry.getValue();
            list.add("[" + key.getName() + "] = [" + key.getClass().getName() + ' ' + Arrays.toString(val) + "] " + key.getState() + "<br>");
        }
        Collections.sort(list);
        for (String e : list) {
            buf.append(e);
        }
    }

    /**
     *  debug
     *  @since 0.9.6
     */
    private void toString2(StringBuilder buf) {
        List<String> list = new ArrayList(_registered.size());
        for (Map.Entry<String, ClientApp> entry : _registered.entrySet()) {
            String key = entry.getKey();
            ClientApp val = entry.getValue();
            list.add("[" + key + "] = [" + val.getClass().getName() + "]<br>");
        }
        Collections.sort(list);
        for (String e : list) {
            buf.append(e);
        }
    }
}
