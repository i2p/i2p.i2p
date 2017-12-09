package net.i2p.jetty;

import java.io.IOException;

// Contains code from org.mortbay.xml.XmlConfiguation:

// ========================================================================
// Copyright 2004-2005 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;
import net.i2p.util.I2PAppThread;
import net.i2p.util.PortMapper;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 *  Start Jetty where the args are one or more XML files.
 *  Save a reference to the Server so it can be cleanly stopped later.
 *  Caller must call startup()
 *
 *  This is like XmlConfiguration.main(), which is essentially what
 *  org.mortbay.start.Main does.
 *
 *  @since 0.9.4
 */
public class JettyStart implements ClientApp {

    private final ClientAppManager _mgr;
    private final String[] _args;
    private final List<LifeCycle> _jettys;
    private final I2PAppContext _context;
    private volatile ClientAppState _state;
    private volatile int _port;

    /**
     *  All args must be XML file names.
     *  Does not support any of the other argument types from org.mortbay.start.Main.
     *
     *  @param context may be null
     *  @param mgr may be null e.g. for use in plugins
     */
    public JettyStart(I2PAppContext context, ClientAppManager mgr, String[] args) throws Exception {
        _state = UNINITIALIZED;
        _mgr = mgr;
        _args = args;
        _jettys = new ArrayList<LifeCycle>(args.length);
        _context = context;
        parseArgs(args);
        _state = INITIALIZED;
    }

    /**
     *  Modified from XmlConfiguration.main()
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void parseArgs(String[] args) throws Exception {
        Properties properties=new Properties();
        XmlConfiguration last=null;
        Resource r = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].toLowerCase().endsWith(".properties")) {
                try {
                    r = Resource.newResource(args[i]);
                    properties.load(r.getInputStream());
                } finally {
                    if (r != null) r.close();
                }
            } else {
                URL configUrl;
                try {
                    r = Resource.newResource(args[i]);
                    configUrl = r.getURL();
                } finally {
                    if (r != null) r.close();
                }
                XmlConfiguration configuration = new XmlConfiguration(configUrl);
                if (last!=null)
                    configuration.getIdMap().putAll(last.getIdMap());
                if (properties.size()>0) {
                    // to avoid compiler errror
                    Map foo = configuration.getProperties();
                    foo.putAll(properties);
                }
                Object o = configuration.configure();
                if (o instanceof LifeCycle)
                    _jettys.add((LifeCycle)o);
                last=configuration;
            }
        }
    }

    public synchronized void startup() {
        if (_state != INITIALIZED)
            return;
        if (_jettys.isEmpty()) {
            changeState(START_FAILED);
        } else {
            (new Starter()).start();
        }
    }

    private class Starter extends I2PAppThread {
        public Starter() {
            super("JettyStarter");
            changeState(STARTING);
        }

        /**
         *  Modified from XmlConfiguration.main()
         */
        public void run() {
            for (LifeCycle lc : _jettys) {
                if (!lc.isRunning()) {
                    try {
                        lc.start();
                        if (_context != null && _context.portMapper().getPort(PortMapper.SVC_EEPSITE) <= 0) {
                            if (lc instanceof Server) {
                                Server server = (Server) lc;
                                Connector[] connectors = server.getConnectors();
                                if (connectors.length > 0) {
                                    Connector conn = connectors[0];
                                    if (conn instanceof NetworkConnector) {
                                        NetworkConnector nconn = (NetworkConnector) conn;
                                        int port = nconn.getPort();
                                        if (port > 0) {
                                            _port = port;
                                            String host = nconn.getHost();
                                            if (host.equals("0.0.0.0") || host.equals("::"))
                                                host = "127.0.0.1";
                                            _context.portMapper().register(PortMapper.SVC_EEPSITE, host, port);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        changeState(START_FAILED, e);
                        return;
                    }
                }
            }
            changeState(RUNNING);
            if (_mgr != null)
                _mgr.register(JettyStart.this);
        }
    }

    public synchronized void shutdown(String[] args) {
        if (_state != RUNNING)
            return;
        if (_jettys.isEmpty()) {
            changeState(STOPPED);
        } else {
            (new Stopper()).start();
        }
    }

    private class Stopper extends I2PAppThread {
        public Stopper() {
            super("JettyStopper");
            changeState(STOPPING);
        }

        public void run() {
            for (LifeCycle lc : _jettys) {
                if (lc.isRunning()) {
                    try {
                        lc.stop();
                    } catch (Exception e) {
                        changeState(STOPPING, e);
                    }
                }
            }
            if (_context != null && _port > 0 && _context.portMapper().getPort(PortMapper.SVC_EEPSITE) == _port) {
                _port = 0;
                _context.portMapper().unregister(PortMapper.SVC_EEPSITE);
            }
            changeState(STOPPED);
        }
    }

    public ClientAppState getState() {
        return _state;
    }

    public String getName() {
        return "Jetty";
    }

    public String getDisplayName() {
        return "Jetty " + Arrays.toString(_args);
    }

    private void changeState(ClientAppState state) {
        changeState(state, null);
    }

    private synchronized void changeState(ClientAppState state, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, null, e);
    }

    /**
     *  For use in a plugin clients.config
     *  @param args passed to constructor
     *  @since 0.9.6
     */
    public static void main(String[] args) {
        try {
            JettyStart js = new JettyStart(null, null, args);
            js.startup();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
