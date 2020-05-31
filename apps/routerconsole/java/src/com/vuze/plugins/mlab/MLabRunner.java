/*
 * Created on Jan 29, 2010
 * Created by Paul Gardner
 * 
 * Copyright 2010 Vuze, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.vuze.plugins.mlab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import edu.internet2.ndt.Tcpbw100;

import org.json.simple.JsonObject;
import org.json.simple.Jsoner;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.EepGet;
import net.i2p.util.SSLEepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * As modified from BiglyBT MLabPlugin
 *
 * @since 0.9.38
 */
public class MLabRunner {
    // ns.measurementlab.net does not support https
    //private static final String NS_URL = "http://ns.measurementlab.net/ndt?format=json";
    private static final String NS_URL_SSL = "https://mlab-ns.appspot.com/ndt?format=json";
    // use ndt_ssl for test over ssl
    private static final String NS_URL_SSL_SSL = "https://mlab-ns.appspot.com/ndt_ssl?format=json";
    private static final String PROP_SSL = "routerconsole.bwtest.useSSL";
    // SSL hangs far too often
    private static final boolean DEFAULT_USE_SSL = false;
    private static final long NS_TIMEOUT = 20*1000;
    private final I2PAppContext _context;
    private final Log _log;
    private final AtomicBoolean _running = new AtomicBoolean();
    private static MLabRunner _instance;
    
    public static MLabRunner getInstance(I2PAppContext ctx) {
        synchronized(MLabRunner.class) {
            if (_instance == null)
                _instance = new MLabRunner(ctx);
            return _instance;
        }
    }

    private MLabRunner(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(MLabRunner.class);
    }

    public boolean isRunning() {
       return _running.get();
    }
    
    /**
     * Non-blocking, spawns a thread and returns immediately.
     *
     * @param listener use to detect completion and get results
     * @return a ToolRun object which may be used to cancel the test,
     *         or null if there was already a test in progress.
     */
    public ToolRun runNDT(final ToolListener listener) {
        boolean useSSL = _context.getProperty(PROP_SSL, DEFAULT_USE_SSL);
        return runNDT(listener, useSSL, null);
    }
    
    /**
     * Non-blocking, spawns a thread and returns immediately.
     *
     * @param listener use to detect completion and get results
     * @param use_SSL whether to use SSL to talk to the servers
     * @param serverHost if non-null, bypass the name server and run test with this host
     * @return a ToolRun object which may be used to cancel the test,
     *         or null if there was already a test in progress.
     * @since 0.9.39
     */
    public ToolRun runNDT(final ToolListener listener, final boolean use_SSL, final String serverHost) {
        if (!_running.compareAndSet(false, true)) {
            listener.reportSummary("Test already running");
            listener.reportDetail("Test already running");
            _log.warn("Test already running");
            return null;
        }
        final ToolRun run = new ToolRunImpl();
        
        runTool(
            new Runnable()
            {
                public void run() {
                    boolean completed = false;
                    try{
                        _log.warn("Starting NDT Test");
                        
                        // String host = "ndt.iupui.donar.measurement-lab.org";
                        // String host = "jlab4.jlab.org";

                        // on 2014/01/14 (or maybe before) things stopped working with the above. Found server below
                        // still running 3.6.4. Unfortunately when I tested the latest client code against servers
                        // allegedly running compatible server code it didn't work... ;(
                        
                        // reply on mailing list to above issue:
                        
                        // The first is to switch to our new name server, ns.measurementlab.net. For example: http://ns.measurementlab.net/ndt will return a JSON string with the closest NDT server. Example integration can be found on www.measurementlab.net/p/ndt.html
                        // The other option, discouraged, is to continue using donar which should still be resolving. It just uses ns.measurementlab.net on the backend now. However, this is currently down according to my tests, so we'll work on getting this back as soon as possible.
                        
                        String server_host = serverHost;
                        String server_city = null;
                        String server_country = null;
                        boolean useSSL = use_SSL;
                        
                        if (server_host == null) {
                            try {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                                // http to name server
                                // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort,
                                //               int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream,
                                //               String url, boolean allowCaching, String etag, String postData) {
                                //EepGet eepget = new EepGet(_context, false, null, 0,
                                //                           0, 2, 1024, null, baos,
                                //                           NS_URL, false, null, null);
                                // https to name server
                                String nsURL = useSSL ? NS_URL_SSL_SSL : NS_URL_SSL;
                                EepGet eepget = new SSLEepGet(_context, baos, nsURL);
                                boolean ok = eepget.fetch(NS_TIMEOUT, NS_TIMEOUT, NS_TIMEOUT);
                                if (!ok)
                                    throw new IOException("ns fetch failed");
                                int code = eepget.getStatusCode();
                                if (code != 200)
                                    throw new IOException("ns fetch failed: " + code);
                                byte[] b = baos.toByteArray();
                                String s = new String(b, "ISO-8859-1");
                                JsonObject map = (JsonObject) Jsoner.deserialize(s);
                                if (map == null) {
                                    throw new IOException("no map");
                                }
                                if (_log.shouldWarn())
                                    _log.warn("Got response: " + DataHelper.getUTF8(b));
                                // TODO use IP instead to avoid another lookup? - no, won't work with ssl
                                // use "fqdn" in response instead of "url" since ndt_ssl does not have url
                                server_host = (String)map.get("fqdn");
                                if (server_host == null) {
                                    throw new IOException("no fqdn");
                                }
                                server_city = (String) map.get("city");
                                server_country = (String) map.get("country");
                                // ignore the returned port in the URL (7123) which is the applet, not the control port
                                if (_log.shouldWarn())
                                    _log.warn("Selected server: " + server_host);
                            } catch (Exception e) {
                                if (_log.shouldWarn())
                                    _log.warn("Failed to get server", e);
                            }
                        }
                        
                        if (server_host == null) {
                            // fallback to old, discouraged approach
                            server_host = "ndt.iupui.donar.measurement-lab.org";
                            useSSL = false;
                            if (_log.shouldWarn())
                                _log.warn("Failed to select server, falling back to donar method");
                        }
                        
                        String[] args = useSSL ? new String[] { "-s", server_host } : new String[] { server_host };
                        long start = System.currentTimeMillis();
                        final Tcpbw100 test;
                        try {
                            test = Tcpbw100.mainSupport(args);
                        } catch (IllegalArgumentException iae) {
                            String err = "Failed to connect to bandwidth test server " + server_host;
                            _log.error(err, iae);
                            if (listener != null) {
                                listener.reportSummary(err);
                                listener.reportDetail(err);
                            }
                            return;
                        }
                        final AtomicBoolean cancelled = new AtomicBoolean();
                        
                        run.addListener(
                            new ToolRunListener()
                            {
                                public void cancelled() {
                                    cancelled.set(true);
                                    _log.warn("TRL cancelling test");
                                    test.killIt();
                                    _log.warn("TRL cancelled test");
                                }

                                public String getStatus() {
                                    return test.getStatus();
                                }
                            });
                        
                        test.runIt();
                        
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                        for (int i = 0; i < 180; i++) {
                            if (cancelled.get() || !test.isTestInProgress())
                                break;
                            try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                        }

                        // in integer bytes per second
                        long up_bps = 0;
                        try {
                            up_bps = (long)(Double.parseDouble(test.get_c2sspd())*1000000)/8;
                        } catch(Throwable e) {}
                        
                        // in integer bytes per second
                        long down_bps = 0;
                        try {
                            down_bps = (long)(Double.parseDouble(test.get_s2cspd())*1000000)/8;
                        } catch(Throwable e) {}
                        
                        String result_str;
                        if (cancelled.get()) {
                            result_str = "Test cancelled";
                        } else if (up_bps == 0 || down_bps == 0) {
                            result_str = "No results were received. Either the test server is unavailable or network problems are preventing the test from running correctly. Please try again.";
                        } else {
                            result_str =     
                                "Completed: up=" + DataHelper.formatSize2Decimal(up_bps, false) +
                                "Bps, down=" + DataHelper.formatSize2Decimal(down_bps, false) + "Bps";
                        }
                        
                        _log.warn(result_str);
                        completed = true;
                        if (listener != null){
                            listener.reportSummary(result_str);
                            listener.reportDetail(result_str);
                            Map<String,Object> results = new HashMap<String, Object>();
                            results.put("up", up_bps);
                            results.put("down", down_bps);
                            results.put("server_host", server_host);
                            if (server_city != null)
                                results.put("server_city", server_city.replace("_", ", "));
                            if (server_country != null)
                                results.put("server_country", server_country);
                            listener.complete(results);
                        }
                        if (_log.shouldWarn()) {
                            long end = System.currentTimeMillis();
                            _log.warn("Test complete in " + DataHelper.formatDuration(end - start));
                        }
                    } finally {
                        if (!completed && listener != null) {
                            listener.complete( new HashMap<String, Object>());
                        }
                        _running.set(false);
                    }
                }
            });
        
        return run;
    }
    
    /**
     * Non-blocking, spawns a thread and returns immediately.
     */
    private void runTool(final Runnable target) {
        new I2PAppThread("MLabRunner")
        {
            @Override
            public void run() {
                try{
                    target.run();
                }finally{
                }
            }
        }.start();
    }
    
    /**
     * Returned from runNDT
     */
    public interface ToolRun {
        public void cancel();
        public void addListener(ToolRunListener    l);
        public String getStatus();
    }
    
    /**
     * Returned from runNDT
     */
    private class ToolRunImpl implements ToolRun {
        private List<ToolRunListener> listeners = new ArrayList<ToolRunListener>();
        private boolean cancelled;
        
        public void cancel() {
            List<ToolRunListener> copy;
            synchronized( this ){
                cancelled = true;
                copy = new ArrayList<ToolRunListener>(listeners);
            }
            for ( ToolRunListener l: copy ){
                try{
                    l.cancelled();
                }catch( Throwable e ){
                    _log.warn("?", e);
                }
            }
        }
        
        public void addListener(ToolRunListener l) {
            boolean inform = false;
            synchronized(this){
                inform = cancelled;
                listeners.add(l);
            }
            if (inform) {
                try{
                    l.cancelled();
                }catch( Throwable e ){
                    _log.warn("?", e);
                }
            }
        }

        public String getStatus() {
            synchronized(this) {
                return listeners.isEmpty() ? "" : listeners.get(0).getStatus();
            }
        }
    }
    
    /** The listener for ToolRun */
    public interface ToolRunListener {
        public void cancelled();
        public String getStatus();
    }
    
    /** The parameter for runNDT() */
    public interface ToolListener {
        public void reportSummary(String str);
        public void reportDetail(String str);
        public void complete(Map<String,Object> results);
    }

    /** standalone test */
    private static class TestListener implements ToolListener {
        private final AtomicBoolean _complete = new AtomicBoolean();

        public void reportSummary(String str) {
            System.out.println(str);
        }

        public void reportDetail(String str) {
            System.out.println(str);
        }

        public void complete(Map<String, Object> results) {
            System.out.println("**************** Results: " + DataHelper.toString(results) + "***********************");
            _complete.set(true);
        }

        public boolean isComplete() {
            return _complete.get();
        }
    }

    /** standalone test */
    public static void main(String[] args) {
        boolean useSSL = args.length > 0 && args[0].equals("-s");
        String host;
        if (useSSL && args.length > 1)
            host = args[1];
        else if (!useSSL && args.length > 0)
            host = args[0];
        else
            host = null;
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        MLabRunner mlab = MLabRunner.getInstance(ctx);
        TestListener lsnr = new TestListener();
        mlab.runNDT(lsnr, useSSL, host);
        try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
        for (int i = 0; i < 180; i++) {
            if (lsnr.isComplete())
                break;
            try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
        }
    }
}
