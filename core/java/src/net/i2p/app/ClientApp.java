package net.i2p.app;

/**
 *  If a class started via clients.config implements this interface,
 *  it will be used to manage the client, instead of starting with main()
 *
 *  Clients implementing this interface MUST provide the following constructor:
 *
 *  public MyClientApp(I2PAppContext context, ClientAppManager listener, String[] args) {...}
 *
 *  All parameters are non-null.
 *  This constructor is for instantiation only.
 *  Do not take a long time. Do not block. Never start threads or processes in it.
 *  The ClientAppState of the returned object must be INITIALIZED,
 *  or else throw something.
 *  The startup() method will be called next.
 *
 *  Never ever hold a static reference to the context or anything derived from it.
 *
 *  @since 0.9.4
 */
public interface ClientApp {

    /**
     *  Do not take a long time. Do not block. Start threads here if necessary.
     *  Client must call ClientAppManager.notify() at least once within this
     *  method to change the state from INITIALIZED to something else.
     *  Will not be called multiple times on the same object.
     */
    public void startup() throws Throwable;

    /**
     *  Do not take a long time. Do not block. Use a thread if necessary.
     *  If previously running, client must call ClientAppManager.notify() at least once within this
     *  method to change the state to STOPPING or STOPPED.
     *  May be called multiple times on the same object, in any state.
     *
     *  @param args generally null but could be stopArgs from clients.config
     */
    public void shutdown(String[] args) throws Throwable;

    /**
     *  The current state of the ClientApp.
     *  @return non-null
     */
    public ClientAppState getState();

    /**
     *  The generic name of the ClientApp, used for registration,
     *  e.g. "console". Do not translate.
     *  @return non-null
     */
    public String getName();

    /**
     *  The display name of the ClientApp, used in user interfaces.
     *  The app must translate.
     *  @return non-null
     */
    public String getDisplayName();
}
