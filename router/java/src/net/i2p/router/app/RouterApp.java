package net.i2p.router.app;

import net.i2p.app.ClientApp;

/**
 *  If a class started via clients.config implements this interface,
 *  it will be used to manage the client, instead of starting with main()
 *
 *  Clients implementing this interface MUST provide the following constructor:
 *
 *  public MyClientApp(RouterContext context, ClientAppManager listener, String[] args) {...}
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
public interface RouterApp extends ClientApp {}
