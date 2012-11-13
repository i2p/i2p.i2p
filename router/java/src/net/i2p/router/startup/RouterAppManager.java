package net.i2p.router.startup;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
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
    private final Set<ClientApp> _clients;
    private final ConcurrentHashMap<String, ClientApp> _registered;

    public RouterAppManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(RouterAppManager.class);
        _clients = new ConcurrentHashSet(16);
        _registered = new ConcurrentHashMap(8);
    }

    public void addAndStart(ClientApp app) {
        _clients.add(app);
        try {
            app.startup();
        } catch (Throwable t) {
            _clients.remove(app);
            _log.error("Client " + app + " failed to start", t);
        }
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
                _log.warn("Client " + app.getDisplayName() + " called notify for " + state);
            break;

          case STARTING:
          case RUNNING:
            if (_log.shouldLog(Log.INFO))
                _log.info("Client " + app.getDisplayName() + " called notify for " + state);
            break;

          case FORKED:
          case STOPPING:
          case STOPPED:
            _clients.remove(app);
            _registered.remove(app.getName(), app);
            if (message == null)
                message = "";
            if (_log.shouldLog(Log.INFO))
                _log.info("Client " + app.getDisplayName() + " called notify for " + state +
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
        if (!_clients.contains(app))
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
