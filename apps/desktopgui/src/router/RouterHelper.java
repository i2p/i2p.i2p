package router;

import net.i2p.router.RouterContext;

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
}
