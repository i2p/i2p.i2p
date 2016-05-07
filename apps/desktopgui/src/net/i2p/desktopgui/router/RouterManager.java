package net.i2p.desktopgui.router;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
//import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.util.Log;

/**
 * Handle communications with the router instance.
 *
 * See ConfigServiceHandler for best practices on stopping the router.
 * We don't bother notifying any Wrapper instance here.
 *
 * @author mathias
 *
 */
public class RouterManager {
	
    /** @return non-null */
    private static I2PAppContext getAppContext() {
        return I2PAppContext.getGlobalContext();
    }
    
    /**
     * Start an I2P router instance.
     * This method has limited knowledge
     * (there is no I2P instance running to collect information from).
     * 
     * It determines the I2P location using the I2PAppContext.
     */
    public static void start() {
        try {
            //TODO: set/get PID
            String separator = System.getProperty("file.separator");
            String location = getAppContext().getBaseDir().getAbsolutePath();
            String[] args = new String[] { location + separator + "i2psvc", location + separator + "wrapper.config" };
            Runtime.getRuntime().exec(args);
        } catch (IOException e) {
            Log log = getAppContext().logManager().getLog(RouterManager.class);
            log.log(Log.WARN, "Failed to start I2P", e);
        }
    }
    
    /**
     * Restart the running I2P instance.
     */
    public static void restart(RouterContext ctx) {
        //if (ctx.hasWrapper())
        //    ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_HARD_RESTART, false);
        ctx.router().shutdownGracefully(Router.EXIT_HARD_RESTART);
    }

    /**
     * Stop the running I2P instance.
     */
    public static void shutDown(RouterContext ctx) {
        //if (ctx.hasWrapper())
        //    ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_HARD, false);
        ctx.router().shutdownGracefully(Router.EXIT_HARD);
    }
    
    /**
     * Restart the running I2P instance.
     * @since 0.9.26
     */
    public static void restartGracefully(RouterContext ctx) {
        //if (ctx.hasWrapper())
        //    ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_GRACEFUL_RESTART, false);
        ctx.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
    }

    /**
     * Stop the running I2P instance.
     * @since 0.9.26
     */
    public static void shutDownGracefully(RouterContext ctx) {
        //if (ctx.hasWrapper())
        //    ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_GRACEFUL, false);
        ctx.router().shutdownGracefully();
    }

    /**
     * Cancel a graceful shutdown or restart
     * @since 0.9.26
     */
    public static void cancelShutdown(RouterContext ctx) {
        ctx.router().cancelGracefulShutdown();
    }

    /**
     * Is a graceful shutdown or restart in progress?
     * @since 0.9.26
     */
    public static boolean isShutdownInProgress(RouterContext ctx) {
        return ctx.router().scheduledGracefulExitCode() > 0;
    }

    /**
     * Get time until shutdown
     * @return -1 if no shutdown in progress.
     * @since 0.9.26
     */
    public static long getShutdownTimeRemaining(RouterContext ctx) {
        return ctx.router().getShutdownTimeRemaining();
    }

    /**
     * Get network status, untranslated
     * @since 0.9.26
     */
    public static String getStatus(RouterContext ctx) {
        return ctx.commSystem().getStatus().toStatusString();
    }
}
