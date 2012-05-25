package net.i2p.router.web;

import java.util.StringTokenizer;

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
            // Normal browsers send value, IE sends button label
            if ("shutdownImmediate".equals(action) || "Shutdown immediately".equals(action)) {
                ctx.addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_HARD));
                //ctx.router().shutdown(Router.EXIT_HARD); // never returns
                ctx.router().shutdownGracefully(Router.EXIT_HARD); // give the UI time to respond
            } else if ("cancelShutdown".equals(action) || "Cancel shutdown".equals(action)) {
                ctx.router().cancelGracefulShutdown();
            } else if ("restartImmediate".equals(action) || "Restart immediately".equals(action)) {
                ctx.addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_HARD_RESTART));
                //ctx.router().shutdown(Router.EXIT_HARD_RESTART); // never returns
                ctx.router().shutdownGracefully(Router.EXIT_HARD_RESTART); // give the UI time to respond
            } else if ("restart".equalsIgnoreCase(action)) {
                ctx.addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_GRACEFUL_RESTART));
                ctx.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            } else if ("shutdown".equalsIgnoreCase(action)) {
                ctx.addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_GRACEFUL));
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
                     + buttons(urlBase, systemNonce, "shutdownImmediate,Shutdown immediately,cancelShutdown,Cancel shutdown");
            }
        } else if (restarting) {
            if (timeRemaining <= 0) {
                return "<b>Restart imminent</b>";
            } else {
                return "<b>Restart in " + DataHelper.formatDuration(timeRemaining) + "</b><br />"
                     + buttons(urlBase, systemNonce, "restartImmediate,Restart immediately,cancelShutdown,Cancel restart");
            }
        } else {
            if (System.getProperty("wrapper.version") != null)
                return buttons(urlBase, systemNonce, "restart,Restart,shutdown,Shutdown");
            else
                return buttons(urlBase, systemNonce, "shutdown,Shutdown");
        }
    }
    
    /** @param s value,label,... pairs */
    private static String buttons(String url, String nonce, String s) {
        StringBuilder buf = new StringBuilder(128);
        StringTokenizer tok = new StringTokenizer(s, ",");
        buf.append("<form action=\"").append(url).append("\" method=\"GET\">\n");
        buf.append("<input type=\"hidden\" name=\"consoleNonce\" value=\"").append(nonce).append("\" >\n");
        while (tok.hasMoreTokens())
            buf.append("<button type=\"submit\" name=\"action\" value=\"").append(tok.nextToken()).append("\" >").append(tok.nextToken()).append("</button>\n");
        buf.append("</form>\n");
        return buf.toString();
    }

    private static boolean isShuttingDown(RouterContext ctx) {
        return Router.EXIT_GRACEFUL == ctx.router().scheduledGracefulExitCode() ||
               Router.EXIT_HARD == ctx.router().scheduledGracefulExitCode();
    }
    private static boolean isRestarting(RouterContext ctx) {
        return Router.EXIT_GRACEFUL_RESTART == ctx.router().scheduledGracefulExitCode() ||
               Router.EXIT_HARD_RESTART == ctx.router().scheduledGracefulExitCode();
    }
    /** this is for summaryframe.jsp */
    public static long getRestartTimeRemaining() {
        RouterContext ctx = ContextHelper.getContext(null);
        if (ctx.router().gracefulShutdownInProgress())
            return ctx.router().getShutdownTimeRemaining();
        return Long.MAX_VALUE/2;  // summaryframe.jsp adds a safety factor so we don't want to overflow...
    }
}
