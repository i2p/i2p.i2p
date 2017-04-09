package net.i2p.app;

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;

/**
 *  A simple ClientAppManager that supports register/unregister only,
 *  so that client apps may find each other in AppContext.
 *  See RouterAppManager for the real thing in RouterContext.
 *
 *  @since 0.9.30
 */
public class ClientAppManagerImpl implements ClientAppManager {
    
    // registered name to client
    protected final ConcurrentHashMap<String, ClientApp> _registered;

    public ClientAppManagerImpl(I2PAppContext ctx) {
        _registered = new ConcurrentHashMap<String, ClientApp>(8);
    }

    /**
     *  Does nothing.
     *
     *  @param app non-null
     *  @param state non-null
     *  @param message may be null
     *  @param e may be null
     */
    public void notify(ClientApp app, ClientAppState state, String message, Exception e) {}
    
    /**
     *  Register with the manager under the given name,
     *  so that other clients may find it.
     *  Only required for apps used by other apps.
     *
     *  @param app non-null
     *  @return true if successful, false if duplicate name
     */
    public boolean register(ClientApp app) {
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
