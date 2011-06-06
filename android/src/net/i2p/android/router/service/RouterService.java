package net.i2p.android.router.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import java.util.List;

import net.i2p.android.router.R;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterLaunch;
import net.i2p.util.NativeBigInteger;

/**
 *  Runs the router
 */
public class RouterService extends Service {
    private RouterContext _context;
    private String _myDir;
    private int _state;
    private Thread _starterThread;
    private StatusBar _statusBar;
    private final Object _stateLock = new Object();

    private static final int STATE_INIT = 0;
    private static final int STATE_STARTING  = 1;
    private static final int STATE_RUNNING = 2;
    private static final int STATE_STOPPING = 3;
    private static final int STATE_STOPPED = 4;

    private static final String MARKER = "**************************************  ";

    @Override
    public void onCreate() {
        System.err.println(this + " onCreate called" +
                           " Current state is: " + _state);

        _myDir = getFilesDir().getAbsolutePath();
        Init init = new Init(this);
        init.debugStuff();
        init.initialize();
        _statusBar = new StatusBar(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        System.err.println(this + " onStart called" +
                           "Current state is: " + _state);
        synchronized (_stateLock) {
            if (_state != STATE_INIT)
                return START_STICKY;
            _statusBar.update("I2P is starting up");
            _state = STATE_STARTING;
            _starterThread = new Thread(new Starter());
            _starterThread.start();
        }
        return START_STICKY;
    }

    private class Starter implements Runnable {
        public void run() {
            System.err.println(MARKER + this + " starter thread");
            NativeBigInteger.main(null);
            RouterLaunch.main(null);
            synchronized (_stateLock) {
                if (_state != STATE_STARTING)
                    return;
                _state = STATE_RUNNING;
                List contexts = RouterContext.listContexts();
                if ( (contexts == null) || (contexts.isEmpty()) ) 
                      throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
                _statusBar.update("I2P is running");
                _context = (RouterContext)contexts.get(0);
                _context.router().setKillVMOnEnd(false);
                _context.addShutdownTask(new ShutdownHook());
                _starterThread = null;
            }
            System.err.println("Router.main finished");
        }
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        System.err.println("onBind called" +
                           "Current state is: " + _state);
        return null;
    }

    @Override
    public void onDestroy() {
        System.err.println("onDestroy called" +
                           "Current state is: " + _state);
        synchronized (_stateLock) {
            if (_state == STATE_STARTING)
                _starterThread.interrupt();
            if (_state == STATE_STARTING || _state == STATE_RUNNING) {
                _state = STATE_STOPPING;
              // should this be in a thread?
                _statusBar.update("I2P is stopping");
                Thread stopperThread = new Thread(new Stopper());
                stopperThread.start();
            } else if (_state != STATE_STOPPING) {
                _statusBar.off(this);
            }
        }
    }

    private class Stopper implements Runnable {
        public void run() {
            System.err.println(MARKER + this + " stopper thread");
            _context.router().shutdown(Router.EXIT_HARD);
            _statusBar.off(RouterService.this);
            System.err.println("shutdown complete");
            synchronized (_stateLock) {
                _state = STATE_STOPPED;
            }
        }
    }

    private class ShutdownHook implements Runnable {
        public void run() {
            System.err.println(this + " shutdown hook" +
                               "Current state is: " + _state);
            synchronized (_stateLock) {
                if (_state == STATE_STARTING || _state == STATE_RUNNING) {
                    _state = STATE_STOPPED;
                    _statusBar.off(RouterService.this);
                    stopSelf();
                }
            }
        }
    }
}
