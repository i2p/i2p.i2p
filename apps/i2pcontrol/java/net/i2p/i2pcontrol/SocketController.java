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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import static net.i2p.app.ClientAppState.*;
import net.i2p.router.RouterContext;
import net.i2p.router.app.RouterApp;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

import org.json.simple.Jsoner;
import org.json.simple.DeserializationException;

import net.i2p.i2pcontrol.security.KeyStoreProvider;
import net.i2p.i2pcontrol.security.SecurityManager;
import net.i2p.i2pcontrol.servlets.JSONRPC2Servlet;
import net.i2p.i2pcontrol.servlets.configuration.ConfigurationManager;


/**
 * This handles the starting and stopping of a ServerSocket
 * from a single static class so it can be called via clients.config.
 *
 * This class is NOT used for the webapp or the HTTP/HTTPS implementation.
 *
 * @author hottuna
 */
public class SocketController implements RouterApp {
    // non-null
    private final RouterContext _context;
    private final ClientAppManager _mgr;
    private final Log _log;
    private final String _pluginDir;
    private final ConfigurationManager _conf;
    private final KeyStoreProvider _ksp;
    private final SecurityManager _secMan;
    private ServerSocket _server;
    private final List<Socket> _listeners;
    private ClientAppState _state = UNINITIALIZED;
    // only for main()
    private static SocketController _instance;
    static final String PROP_ALLOWED_HOSTS = "i2pcontrol.allowedhosts";
    private static final String SVC_SKT_I2PCONTROL = "skt_i2pcontrol";
    private static final String SVC_SSL_I2PCONTROL = "skt_ssl_i2pcontrol";
    private static final int DEFAULT_PORT = 7640;

    /**
     *  RouterApp (new way)
     */
    public SocketController(RouterContext ctx, ClientAppManager mgr, String args[]) throws IOException {
        _context = ctx;
        _mgr = mgr;
        _log = _context.logManager().getLog(SocketController.class);
        File pluginDir = new File(_context.getConfigDir(), "keystore/I2PControl");
        _pluginDir = pluginDir.getAbsolutePath();
        _conf = new ConfigurationManager(_context, pluginDir, true);
        _ksp = new KeyStoreProvider(_pluginDir);
        _secMan = new SecurityManager(_context, _ksp, _conf);
        _listeners = new ArrayList<Socket>(4);
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
            _log.error("Unable to start socket server", e);
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
        return "I2PControl-Socket";
    }

    public String getDisplayName() {
        return "I2PControl-Socket";
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

    private synchronized void start(String args[]) throws Exception {
        _context.logManager().getLog(JSONRPC2Servlet.class).setMinimumPriority(Log.DEBUG);
        _server = buildServer();
        _context.portMapper().register(SVC_SKT_I2PCONTROL,
                                       _conf.getConf("i2pcontrol.listen.address", "127.0.0.1"),
                                       _conf.getConf("i2pcontrol.listen.port", DEFAULT_PORT));
    }

    /**
     * Builds a new server. Used for changing ports during operation and such.
     *
     * Does NOT start the server. Must call start() on the returned server.
     *
     * @return Server - A new server built from current configuration.
     */
    public ServerSocket buildServer() throws IOException {
        String address = _conf.getConf("i2pcontrol.listen.address", "127.0.0.1");
        int port = _conf.getConf("i2pcontrol.listen.port", DEFAULT_PORT);
        ServerSocket server = new ServerSocket(port, 0, InetAddress.getByName(address));
        _conf.writeConfFile();
        return server;
    }

    private class Server implements Runnable {
        public void run() {
            try {
                while (true) {
                    Socket s = _server.accept();
                    synchronized (SocketController.this) {
                        _listeners.add(s);
                    }
                    new Handler(s);
                }
            } catch (IOException ioe) {
                _log.error("i2pcontrol server", ioe);
            } finally {
                synchronized (SocketController.this) {
                    if (_server != null) {
                        try { _server.close(); } catch (IOException ioe) {}
                    }
                }
            }
        }
    }

    private class Handler implements Runnable {
        private final Socket s;

        public Handler(Socket skt) { s = skt; }

        public void run() {
            try {
                final BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                while (true) {
                    Object o = Jsoner.deserialize(reader);
                    // TODO
                    System.out.println("i2pcontrol got: " + o);
                }
            } catch (DeserializationException pe) {
                _log.error("i2pcontrol handler", pe);
                return;
            } catch (IOException ioe) {
                _log.error("i2pcontrol handler", ioe);
                return;
            } finally {
                synchronized (SocketController.this) {
                    _listeners.remove(s);
                }
                try { s.close(); } catch (IOException ioe) {}
            }
        }
    }

    /**
     * Stop it
     */
    private synchronized void stopServer()
    {
        try {
            if (_server != null) {
                _context.portMapper().unregister(SVC_SKT_I2PCONTROL);
                try {
                    _server.close();
                } catch (IOException ioe) {}
                for (Socket listener : _listeners) {
                    try {
                        listener.close();
                    } catch (IOException ioe) {}
                }
                _listeners.clear();
            }
        } catch (Exception e) {
            _log.error("Stopping server", e);
        }
    }

    private synchronized void stop() {
        _conf.writeConfFile();
        _secMan.stopTimedEvents();
        stopServer();
    }
}
