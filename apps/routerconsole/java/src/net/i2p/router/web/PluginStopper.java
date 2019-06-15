package net.i2p.router.web;

import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.server.Server;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Stop all plugins that are installed and running
 *
 *  @since 0.7.13
 *  @author zzz
 */
class PluginStopper extends PluginStarter {

    private final Server _server;

    public PluginStopper(RouterContext ctx, Server server) {
        super(ctx);
        _server = server;
    }

    @Override
    public void run() {
        stopPlugins();
    }

    /**
     *  Stop all running plugins
     *
     *  this shouldn't throw anything
     */
    private void stopPlugins() {
        Log log = _context.logManager().getLog(PluginStopper.class);
        List<String> pl = getPlugins();
        Collections.reverse(pl); // reverse the order
        for (String app : pl) {
            if (isPluginRunning(app, _context, _server)) {
                try {
                    if (log.shouldInfo())
                        log.info("Stopping plugin: " + app);
                    stopPlugin(_context, _server, app);
                } catch (Throwable e) {
                   if (log.shouldLog(Log.WARN))
                       log.warn("Failed to stop plugin: " + app, e);
                }
            } else {
                if (log.shouldInfo())
                    log.info("Plugin not running: " + app);
            }
        }
    }
}
