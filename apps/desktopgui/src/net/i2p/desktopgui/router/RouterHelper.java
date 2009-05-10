package net.i2p.desktopgui.router;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;

/**
 *
 * @author mathias
 */
public class RouterHelper {
    public static RouterContext getContext() {
        return (RouterContext) RouterContext.listContexts().get(0);
    }
    
    public static long getGracefulShutdownTimeRemaining() {
        return RouterHelper.getContext().router().getShutdownTimeRemaining();
    }
    
    public static String getVersion() {
        return (RouterVersion.VERSION + "-" + RouterVersion.BUILD);
    }
}
