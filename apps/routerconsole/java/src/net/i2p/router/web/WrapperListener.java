package net.i2p.router.web;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

import org.tanukisoftware.wrapper.WrapperManager;
import org.tanukisoftware.wrapper.event.WrapperControlEvent;
import org.tanukisoftware.wrapper.event.WrapperServiceControlEvent;
import org.tanukisoftware.wrapper.event.WrapperEvent;
import org.tanukisoftware.wrapper.event.WrapperEventListener;

/**
 *  Listen for events. Requires wrapper 3.2.0 or higher.
 *  Hides the actual listener so that
 *  ConfigServiceHandler can have a static field and not die on
 *  class not found error with wrapper 3.1.1.
 *
 *  @since 0.8.13
 */
class WrapperListener {
    private final WrapperEventListener _listener;

    private static final String PROP_GRACEFUL_HUP = "router.gracefulHUP";

    /**
     *  Wrapper must be 3.2.0 or higher, or will throw class not found error.
     *  Registers with the wrapper in the constructor.
     */
    public WrapperListener(RouterContext ctx) {
        _listener = new SignalHandler(ctx);
        long mask = SystemVersion.isWindows() ? WrapperEventListener.EVENT_FLAG_SERVICE :
                                                WrapperEventListener.EVENT_FLAG_CONTROL;

        WrapperManager.addWrapperEventListener(_listener, mask);
    }

    /**
     *  Unregister the handler for signals
     */
    public void unregister() {
        WrapperManager.removeWrapperEventListener(_listener);
    }

    /**
     *  Catch signals.
     *  The wrapper will potentially forward HUP, USR1, and USR2.
     *  But USR1 and USR2 are used by the JVM GC and cannot be trapped.
     *  So we will only get HUP.
     *
     *  @since 0.8.13
     */
    private static class SignalHandler implements WrapperEventListener {
        private final RouterContext _ctxt;

        public SignalHandler(RouterContext ctx) {
            _ctxt = ctx;
        }

        public void fired(WrapperEvent event) {
            Log log = _ctxt.logManager().getLog(ConfigServiceHandler.class);
            if (SystemVersion.isWindows() && (event instanceof WrapperServiceControlEvent)) {
                WrapperServiceControlEvent wcse = (WrapperServiceControlEvent) event;
                int code = wcse.getServiceControlCode();
                switch (code) {
                  case WrapperManager.SERVICE_CONTROL_CODE_STOP:       // 1
                  case WrapperManager.SERVICE_CONTROL_CODE_SHUTDOWN:   // 5
                    log.log(Log.CRIT, "Hard shutdown initiated by Windows service control: " + code);
                    // JVM will call ShutdownHook if we don't do it ourselves
                    ConfigServiceHandler.registerWrapperNotifier(_ctxt, Router.EXIT_HARD, false);
                    _ctxt.router().shutdown(Router.EXIT_HARD);
                    break;

                  // TODO Power suspend/resume?
                  // Warning, definitions not available in 3.2.0, use integers
                  // Tanuki doesn't usually mark things with @since, sadly
                  // case 35xx // WrapperManager.SERVICE_CONTROL_POWEREVENT_ ...
                  //  break;

                  default:
                    if (log.shouldWarn())
                        log.warn("Unhandled control event code: " + code);
                    break;
                }
                return;
            } else if (!(event instanceof WrapperControlEvent)) {
                if (log.shouldWarn())
                    log.warn("Got unhandled event: " + event);
                return;
            }
            WrapperControlEvent wce = (WrapperControlEvent) event;
            if (log.shouldLog(Log.WARN))
                log.warn("Got signal: " + wce.getControlEventName());
            int sig = wce.getControlEvent();
            switch (sig) {
              case WrapperManager.WRAPPER_CTRL_HUP_EVENT:
                if (_ctxt.getBooleanPropertyDefaultTrue(PROP_GRACEFUL_HUP)) {
                    wce.consume();
                    if (!(_ctxt.router().gracefulShutdownInProgress() ||
                          _ctxt.router().isFinalShutdownInProgress())) {
                        System.err.println("WARN: Graceful shutdown initiated by SIGHUP");
                        log.logAlways(Log.WARN, "Graceful shutdown initiated by SIGHUP");
                        ConfigServiceHandler.registerWrapperNotifier(_ctxt, Router.EXIT_GRACEFUL, false);
                        _ctxt.router().shutdownGracefully();
                    }
                } else {
                    log.log(Log.CRIT, "Hard shutdown initiated by SIGHUP");
                    // JVM will call ShutdownHook if we don't do it ourselves
                    //wce.consume();
                    //registerWrapperNotifier(_ctxt, Router.EXIT_HARD, false);
                    //_ctxt.router().shutdown(Router.EXIT_HARD);
                }
                break;
            }
        }
    }
}
