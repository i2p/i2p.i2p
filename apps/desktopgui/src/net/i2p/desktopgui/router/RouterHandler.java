/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.desktopgui.router;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.i2p.router.RouterContext;

/**
 *
 * @author mathias
 */
public class RouterHandler {
    public static final int SHUTDOWN_GRACEFULLY = 0;
    public static void setStatus(int status) {
        if(status == SHUTDOWN_GRACEFULLY) {
            Thread t = new Thread(new Runnable() {

                public void run() {
                    RouterContext context = RouterHelper.getContext();
                    context.router().shutdownGracefully();
                    while(context.router().getShutdownTimeRemaining()>0)
                        try {
                            Thread.sleep(context.router().getShutdownTimeRemaining());
                        } catch (InterruptedException ex) {
                            Logger.getLogger(RouterHandler.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    System.exit(0);
                }
                
            });
            t.start();
        }
    }
}
