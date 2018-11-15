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
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.vuze.plugins.mlab.tools.ndt.swingemu.Tcpbw100UIWrapper;
import com.vuze.plugins.mlab.tools.ndt.swingemu.Tcpbw100UIWrapperListener;

import edu.internet2.ndt.Tcpbw100;

import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * As modified from BiglyBT MLabPlugin
 *
 * @since 0.9.38
 */
public class MLabRunner {
    // ns.measurementlab.net does not support https
    // use ndt_ssl for test over ssl? but Tcpbw100 doesn't support it
    private static final String NS_URL = "http://ns.measurementlab.net/ndt?format=json";
    private static final long NS_TIMEOUT = 20*1000;
    private boolean test_active;
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
        if (!_running.compareAndSet(false, true)) {
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
                        
                        new Tcpbw100UIWrapper(
                            new Tcpbw100UIWrapperListener()
                            {
                                private LinkedList<String> history = new LinkedList<String>();
                                
                                public void reportSummary(String str) {
                                    str = str.trim();
                                    log( str );
                                    if ( listener != null ){
                                        if ( !str.startsWith( "Click" )){
                                            listener.reportSummary( str );
                                        }
                                    }
                                }
                                
                                public void reportDetail(String str) {
                                    str = str.trim();
                                    log(str);
                                    if (listener != null) {
                                        listener.reportDetail(str);
                                    }
                                }
                                
                                private void log(String str) {
                                    synchronized( history ){
                                        if (history.size() > 0 && history.getLast().equals(str)) {
                                            return;
                                        }
                                        history.add(str);
                                    }
                                    _log.warn(str);
                                }
                        
                            });
                        
                        // String host = "ndt.iupui.donar.measurement-lab.org";
                        // String host = "jlab4.jlab.org";

                        // on 2014/01/14 (or maybe before) things stopped working with the above. Found server below
                        // still running 3.6.4. Unfortunately when I tested the latest client code against servers
                        // allegedly running compatible server code it didn't work... ;(
                        
                        // reply on mailing list to above issue:
                        
                        // The first is to switch to our new name server, ns.measurementlab.net. For example: http://ns.measurementlab.net/ndt will return a JSON string with the closest NDT server. Example integration can be found on www.measurementlab.net/p/ndt.html
                        // The other option, discouraged, is to continue using donar which should still be resolving. It just uses ns.measurementlab.net on the backend now. However, this is currently down according to my tests, so we'll work on getting this back as soon as possible.
                        
                        String server_host = null;
                        String server_city = null;
                        String server_country = null;
                        
                        try {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                            // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort,
                            //               int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream,
                            //               String url, boolean allowCaching, String etag, String postData) {
                            EepGet eepget = new EepGet(_context, false, null, 0,
                                                       0, 2, 1024, null, baos,
                                                       NS_URL, false, null, null);
                            boolean ok = eepget.fetch(NS_TIMEOUT, NS_TIMEOUT, NS_TIMEOUT);
                            if (!ok)
                                throw new IOException("ns fetch failed");
                            int code = eepget.getStatusCode();
                            if (code != 200)
                                throw new IOException("ns fetch failed: " + code);
                            JSONParser parser = new JSONParser(JSONParser.MODE_PERMISSIVE);
                            byte[] b = baos.toByteArray();
                            JSONObject map = (JSONObject) parser.parse(b);
                            if (map == null) {
                                throw new IOException("no map");
                            }
                            if (_log.shouldWarn())
                                _log.warn("Got response: " + DataHelper.getUTF8(b));
                            // TODO use IP instead to avoid another lookup?
                            // or use "fqdn" in response instead of "url"
                            URL url = new URL((String)map.get( "url" ));
                            if (url == null) {
                                throw new IOException("no url");
                            }
                            server_host = url.getHost();
                            server_city = (String) map.get("city");
                            server_country = (String) map.get("country");
                            // ignore the returned port in the URL (7123) which is the applet, not the control port
                            if (_log.shouldWarn())
                                _log.warn("Selected server: " + server_host);
                        } catch (Exception e) {
                            if (_log.shouldWarn())
                                _log.warn("Failed to get server", e);
                        }
                        
                        if (server_host == null) {
                            // fallback to old, discouraged approach
                            server_host = "ndt.iupui.donar.measurement-lab.org";
                            if (_log.shouldWarn())
                                _log.warn("Failed to select server, falling back to donar method");
                        }
                        
                        long start = System.currentTimeMillis();
                        final Tcpbw100 test = Tcpbw100.mainSupport( new String[]{ server_host });
                        
                        run.addListener(
                            new ToolRunListener()
                            {
                                public void cancelled() {
                                    test.killIt();
                                }
                            });
                        
                        test.runIt();
                        
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                        for (int i = 0; i < 180; i++) {
                            if (!test.isTestInProgress())
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
                        if (up_bps == 0 || down_bps == 0) {
                            result_str = "No results were received. Either the test server is unavailable or network problems are preventing the test from running correctly. Please try again.";
                        } else {
                            result_str =     
                                "Completed: up=" + DataHelper.formatSize2Decimal(up_bps, false) +
                                ", down=" + DataHelper.formatSize2Decimal(down_bps, false);
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
                                results.put("server_city", server_city);
                            if (server_country != null)
                                results.put("server_country", server_country);
                            listener.complete( results );
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
    }
    
    /** The listener for ToolRun */
    public interface ToolRunListener {
        public void cancelled();
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
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        MLabRunner mlab = MLabRunner.getInstance(ctx);
        TestListener lsnr = new TestListener();
        mlab.runNDT(lsnr);
        try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
        for (int i = 0; i < 180; i++) {
            if (lsnr.isComplete())
                break;
            try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
        }
    }
}
