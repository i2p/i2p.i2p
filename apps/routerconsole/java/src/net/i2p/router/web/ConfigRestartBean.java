package net.i2p.router.web;

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

/**
 * simple helper to control restarts/shutdowns in the left hand nav
 *
 */
public class ConfigRestartBean {
    public static String getNonce() { 
        RouterContext ctx = ContextHelper.getContext(null);
        String nonce = System.getProperty("console.nonce");
        if (nonce == null) {
            nonce = ""+ctx.random().nextLong();
            System.setProperty("console.nonce", nonce);
        }
        return nonce;
    }
    public static String renderStatus(String urlBase, String action, String nonce) {
        RouterContext ctx = ContextHelper.getContext(null);
        String systemNonce = getNonce();
        if ( (nonce != null) && (systemNonce.equals(nonce)) && (action != null) ) {
            if ("shutdownImmediate".equals(action)) {
                ctx.router().addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_HARD));
                ctx.router().shutdown(Router.EXIT_HARD); // never returns
            } else if ("cancelShutdown".equals(action)) {
                ctx.router().cancelGracefulShutdown();
            } else if ("restartImmediate".equals(action)) {
                ctx.router().addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_HARD_RESTART));
                ctx.router().shutdown(Router.EXIT_HARD_RESTART); // never returns
            } else if ("restart".equals(action)) {
                ctx.router().addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_GRACEFUL_RESTART));
                ctx.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            } else if ("shutdown".equals(action)) {
                ctx.router().addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_GRACEFUL));
                ctx.router().shutdownGracefully();
            }
        }
        
        boolean shuttingDown = isShuttingDown(ctx);
        boolean restarting = isRestarting(ctx);
        long timeRemaining = ctx.router().getShutdownTimeRemaining();
        if (shuttingDown) {
            if (timeRemaining <= 0) {
                return "<b>Shutdown imminent</b>";
            } else {
                return "<b>Shutdown in " + DataHelper.formatDuration(timeRemaining) + "</b><br />"
                     + "<a href=\"" + urlBase + "?consoleNonce=" + systemNonce + "&amp;action=shutdownImmediate\">Shutdown immediately</a><br />"
                     + "<a href=\"" + urlBase + "?consoleNonce=" + systemNonce + "&amp;action=cancelShutdown\">Cancel shutdown</a> ";
            }
        } else if (restarting) {
            if (timeRemaining <= 0) {
                return "<b>Restart imminent</b>";
            } else {
                return "<b>Restart in " + DataHelper.formatDuration(timeRemaining) + "</b><br />"
                     + "<a href=\"" + urlBase + "?consoleNonce=" + systemNonce + "&amp;action=restartImmediate\">Restart immediately</a><br />"
                     + "<a href=\"" + urlBase + "?consoleNonce=" + systemNonce + "&amp;action=cancelShutdown\">Cancel restart</a> ";
            }
        } else {
            return "<a href=\"" + urlBase + "?consoleNonce=" + systemNonce + "&amp;action=restart\" title=\"Graceful restart\">Restart</a> "
                 + "<a href=\"" + urlBase + "?consoleNonce=" + systemNonce + "&amp;action=shutdown\" title=\"Graceful shutdown\">Shutdown</a>";
        }
    }
    
    private static boolean isShuttingDown(RouterContext ctx) {
        return Router.EXIT_GRACEFUL == ctx.router().scheduledGracefulExitCode();
    }
    private static boolean isRestarting(RouterContext ctx) {
        return Router.EXIT_GRACEFUL_RESTART == ctx.router().scheduledGracefulExitCode();
    }
}
