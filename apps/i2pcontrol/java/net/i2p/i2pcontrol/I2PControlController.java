package net.i2p.i2pcontrol;
/*
 *  Copyright 2010 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import static net.i2p.app.ClientAppState.*;
import net.i2p.router.RouterContext;
import net.i2p.router.app.RouterApp;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

import net.i2p.i2pcontrol.security.KeyStoreProvider;
import net.i2p.i2pcontrol.security.SecurityManager;
import net.i2p.i2pcontrol.servlets.JSONRPC2Servlet;
import net.i2p.i2pcontrol.servlets.configuration.ConfigurationManager;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;


/**
 * This handles the starting and stopping of Jetty
 * from a single static class so it can be called via clients.config.
 *
 * This makes installation of a new I2P Site a turnkey operation.
 *
 * Usage: I2PControlController -d $PLUGIN [start|stop]
 *
 * This class is NOT used for the webapp or the bare ServerSocket implementation.
 *
 * @author hottuna
 */
public class I2PControlController implements RouterApp {
    // non-null
    private final I2PAppContext _appContext;
    // warning, null in app context
    private final RouterContext _context;
    private final ClientAppManager _mgr;
    private final Log _log;
    private final String _pluginDir;
    private final ConfigurationManager _conf;
    private final KeyStoreProvider _ksp;
    private final SecurityManager _secMan;
    private final Server _server;
    private ClientAppState _state = UNINITIALIZED;
    // only for main()
    private static I2PControlController _instance;
    static final String PROP_ALLOWED_HOSTS = "i2pcontrol.allowedhosts";
    private static final String SVC_HTTPS_I2PCONTROL = "https_i2pcontrol";
    private static final int DEFAULT_PORT = 7650;

    /**
     *  RouterApp (new way)
     */
    public I2PControlController(RouterContext ctx, ClientAppManager mgr, String args[]) {
        _appContext = _context = ctx;
        _mgr = mgr;
        _log = _appContext.logManager().getLog(I2PControlController.class);
        File pluginDir = new File(_context.getAppDir(), "plugins/I2PControl");
        _pluginDir = pluginDir.getAbsolutePath();
        _conf = new ConfigurationManager(_appContext, pluginDir, true);
        _ksp = new KeyStoreProvider(_pluginDir);
        _secMan = new SecurityManager(_appContext, _ksp, _conf);
        _server = buildServer();
        _state = INITIALIZED;
    }

    /**
     *  From main() (old way)
     */
    public I2PControlController(File pluginDir) {
        _appContext = I2PAppContext.getGlobalContext();
        if (_appContext instanceof RouterContext)
            _context = (RouterContext) _appContext;
        else
            _context = null;
        _mgr = null;
        _log = _appContext.logManager().getLog(I2PControlController.class);
        _pluginDir = pluginDir.getAbsolutePath();
        _conf = new ConfigurationManager(_appContext, pluginDir, true);
        _ksp = new KeyStoreProvider(_pluginDir);
        _secMan = new SecurityManager(_appContext, _ksp, _conf);
        _server = buildServer();
        _state = INITIALIZED;
    }

    /////// ClientApp methods

    public synchronized void startup() {
        changeState(STARTING);
        try {
            start(null);
            changeState(RUNNING);
        } catch (Exception e) {
            changeState(START_FAILED, "Failed to start", e);
            _log.error("Unable to start jetty server", e);
            stop();
        }
    }

    public synchronized void shutdown(String[] args) {
        if (_state == STOPPED)
            return;
        changeState(STOPPING);
        stop();
        changeState(STOPPED);
    }

    public synchronized ClientAppState getState() {
        return _state;
    }

    public String getName() {
        return "I2PControl";
    }

    public String getDisplayName() {
        return "I2PControl";
    }

    /////// end ClientApp methods

    private void changeState(ClientAppState state) {
        changeState(state, null, null);
    }

    private synchronized void changeState(ClientAppState state, String msg, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, msg, e);
        if (_context == null) {
            if (msg != null)
                System.out.println(state + ": " + msg);
            if (e != null)
                e.printStackTrace();
        }
    }


    /**
     *  Deprecated, use constructor
     */
    public static void main(String args[]) {
        if (args.length != 3 || (!"-d".equals(args[0])))
            throw new IllegalArgumentException("Usage: PluginController -d $PLUGINDIR [start|stop]");

        if ("start".equals(args[2])) {
            File pluginDir = new File(args[1]);
            if (!pluginDir.exists())
                throw new IllegalArgumentException("Plugin directory " + pluginDir.getAbsolutePath() + " does not exist");
            synchronized(I2PControlController.class) {
                if (_instance != null)
                    throw new IllegalStateException();
                I2PControlController i2pcc = new I2PControlController(pluginDir);
                try {
                    i2pcc.startup();
                    _instance = i2pcc;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else if ("stop".equals(args[2])) {
            synchronized(I2PControlController.class) {
                if (_instance != null) {
                    _instance.shutdown(null);
                    _instance = null;
                }
            }
        } else {
            throw new IllegalArgumentException("Usage: PluginController -d $PLUGINDIR [start|stop]");
        }
    }


    private synchronized void start(String args[]) throws Exception {
        _appContext.logManager().getLog(JSONRPC2Servlet.class).setMinimumPriority(Log.DEBUG);
        _server.start();
        _context.portMapper().register(SVC_HTTPS_I2PCONTROL,
                                       _conf.getConf("i2pcontrol.listen.address", "127.0.0.1"),
                                       _conf.getConf("i2pcontrol.listen.port", DEFAULT_PORT));
    }



    /**
     * Builds a new server. Used for changing ports during operation and such.
     * @return Server - A new server built from current configuration.
     */
    private Connector buildDefaultListener(Server server) {
        Connector ssl = buildSslListener(server, _conf.getConf("i2pcontrol.listen.address", "127.0.0.1"),
                                 _conf.getConf("i2pcontrol.listen.port", DEFAULT_PORT));
        return ssl;
    }


    /**
     * Builds a new server. Used for changing ports during operation and such.
     *
     * Does NOT start the server. Must call start() on the returned server.
     *
     * @return Server - A new server built from current configuration.
     */
    public Server buildServer() {
        Server server = new Server();
        Connector ssl = buildDefaultListener(server);
        server.addConnector(ssl);

        ServletHandler sh = new ServletHandler();
        sh.addServletWithMapping(new ServletHolder(new JSONRPC2Servlet(_context, _secMan)), "/");
        HostCheckHandler hch = new HostCheckHandler(_appContext);
        Set<String> listenHosts = new HashSet<String>(8);
        // fix up the allowed hosts set (see HostCheckHandler)
        // empty set says all are valid
        String address = _conf.getConf("i2pcontrol.listen.address", "127.0.0.1");
        if (!(address.equals("0.0.0.0") ||
              address.equals("::") ||
              address.equals("0:0:0:0:0:0:0:0"))) {
            listenHosts.add("localhost");
            listenHosts.add("127.0.0.1");
            listenHosts.add("::1");
            listenHosts.add("0:0:0:0:0:0:0:1");
            String allowed = _conf.getConf(PROP_ALLOWED_HOSTS, "");
            if (!allowed.equals("")) {
                StringTokenizer tok = new StringTokenizer(allowed, " ,");
                while (tok.hasMoreTokens()) {
                    listenHosts.add(tok.nextToken());
                }
            }
        }
        hch.setListenHosts(listenHosts);
        hch.setHandler(sh);
        server.getServer().setHandler(hch);

        _conf.writeConfFile();
        return server;
    }


    /**
     * Creates a SSLListener with all the default options. The listener will use all the default options.
     * @param address - The address the listener will listen to.
     * @param port - The port the listener will listen to.
     * @return - Newly created listener
     */
    private Connector buildSslListener(Server server, String address, int port) {
        int listeners = 0;
        if (server != null) {
            listeners = server.getConnectors().length;
        }

        // the keystore path and password
        SslContextFactory sslFactory = new SslContextFactory(_ksp.getKeyStoreLocation());
        sslFactory.setKeyStorePassword(KeyStoreProvider.DEFAULT_KEYSTORE_PASSWORD);
        // the X.509 cert password (if not present, verifyKeyStore() returned false)
        sslFactory.setKeyManagerPassword(KeyStoreProvider.DEFAULT_CERTIFICATE_PASSWORD);
        sslFactory.addExcludeProtocols(I2PSSLSocketFactory.EXCLUDE_PROTOCOLS.toArray(
                                       new String[I2PSSLSocketFactory.EXCLUDE_PROTOCOLS.size()]));
        sslFactory.addExcludeCipherSuites(I2PSSLSocketFactory.EXCLUDE_CIPHERS.toArray(
                                          new String[I2PSSLSocketFactory.EXCLUDE_CIPHERS.size()]));

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(port);
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        // number of acceptors, (default) number of selectors
        ServerConnector ssl = new ServerConnector(server, 1, 0,
                                                  new SslConnectionFactory(sslFactory, "http/1.1"),
                                                  new HttpConnectionFactory(httpConfig));
        ssl.setHost(address);
        ssl.setPort(port);
        ssl.setIdleTimeout(90*1000);  // default 10 sec
        // all with same name will use the same thread pool
        ssl.setName("I2PControl");

        ssl.setName("SSL Listener-" + ++listeners);

        return ssl;
    }


    /**
     * Add a listener to the server
     * If a listener listening to the same port as the provided listener
     * uses already exists within the server, replace the one already used by
     * the server with the provided listener.
     * @param listener
     * @throws Exception
     */
/****
    public synchronized void replaceListener(Connector listener) throws Exception {
        if (_server != null) {
            stopServer();
        }
        _server = buildServer(listener);
    }
****/

    /**
     * Get all listeners of the server.
     * @return
     */
/****
    public synchronized Connector[] getListeners() {
        if (_server != null) {
            return _server.getConnectors();
        }
        return new Connector[0];
    }
****/

    /**
     * Removes all listeners
     */
/****
    public synchronized void clearListeners() {
        if (_server != null) {
            for (Connector listen : getListeners()) {
                _server.removeConnector(listen);
            }
        }
    }
****/

    /**
     * Stop it
     */
    private synchronized void stopServer()
    {
        try {
            if (_server != null) {
                _appContext.portMapper().unregister(SVC_HTTPS_I2PCONTROL);
                _server.stop();
                for (Connector listener : _server.getConnectors()) {
                    listener.stop();
                }
                _server.destroy();
            }
        } catch (Exception e) {
            _log.error("Stopping server", e);
        }
    }

    private synchronized void stop() {
        _conf.writeConfFile();
        _secMan.stopTimedEvents();
        stopServer();

/****
        // Get and stop all running threads
        ThreadGroup threadgroup = Thread.currentThread().getThreadGroup();
        Thread[] threads = new Thread[threadgroup.activeCount() + 3];
        threadgroup.enumerate(threads, true);
        for (Thread thread : threads) {
            if (thread != null) {//&& thread.isAlive()){
                thread.interrupt();
            }
        }

        for (Thread thread : threads) {
            if (thread != null) {
                System.out.println("Active thread: " + thread.getName());
            }
        }
        threadgroup.interrupt();

        //Thread.currentThread().getThreadGroup().destroy();
****/
    }

    public String getPluginDir() {
        return _pluginDir;
    }
}
