package net.i2p.router.startup;

import java.util.Arrays;
import java.util.Map;
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
}
