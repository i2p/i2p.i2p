package net.i2p.app;

/**
 *  Notify the router of events, and provide methods for
 *  client apps to find each other.
 *
 *  @since 0.9.4
 */
public interface ClientAppManager {
    
    /**
     *  Must be called on all state transitions except
     *  from UNINITIALIZED to INITIALIZED.
     *
     *  @param app non-null
     *  @param state non-null
     *  @param message may be null
     *  @param e may be null
     */
    public void notify(ClientApp app, ClientAppState state, String message, Exception e);
    
    /**
     *  Register with the manager under the given name,
     *  so that other clients may find it.
     *  Only required for apps used by other apps.
     *
     *  @param app non-null
     *  @return true if successful, false if duplicate name
     */
    public boolean register(ClientApp app);
    
    /**
     *  Unregister with the manager. Name must be the same as that from register().
     *  Only required for apps used by other apps.
     *
     *  @param app non-null
     */
    public void unregister(ClientApp app);
    
    /**
     *  Get a registered app.
     *  Only used for apps finding other apps.
     *
     *  @param name non-null
     *  @return client app or null
     */
    public ClientApp getRegisteredApp(String name);
}
