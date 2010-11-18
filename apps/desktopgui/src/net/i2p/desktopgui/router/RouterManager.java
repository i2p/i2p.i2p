package net.i2p.desktopgui.router;

import net.i2p.I2PAppContext;
import org.tanukisoftware.wrapper.WrapperManager;

public class RouterManager {

	public static void shutDown(final int exitCode) {
		I2PAppContext.getGlobalContext().addShutdownTask(new Runnable() {

			@Override
			public void run() {
                try {
                    WrapperManager.signalStopped(exitCode);
                }
                catch(Throwable t) {
                    //TODO: log
                }
            }
            
        });
    }
}
