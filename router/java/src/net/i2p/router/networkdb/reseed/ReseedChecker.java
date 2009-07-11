package net.i2p.router.networkdb.reseed;

import java.io.File;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Moved from RouterConsoleRunner.java
 *
 *  Reseeding is not strictly a router function, it used to be
 *  in the routerconsole app, but this made it impossible to
 *  bootstrap an embedded router lacking a routerconsole,
 *  in iMule or android for example, without additional modifications.
 *
 *  Also, as this is now called from PersistentDataStore, not from the
 *  routerconsole, we can get started as soon as the netdb has read
 *  the netDb/ directory, not when the console starts.
 */
public class ReseedChecker {
    
    private static final int MINIMUM = 15;

    public static void checkReseed(RouterContext context, int count) {
        if (count >= MINIMUM)
            return;

        // we check the i2p installation directory for a flag telling us not to reseed, 
        // but also check the home directory for that flag too, since new users installing i2p
        // don't have an installation directory that they can put the flag in yet.
        File noReseedFile = new File(new File(System.getProperty("user.home")), ".i2pnoreseed");
        File noReseedFileAlt1 = new File(new File(System.getProperty("user.home")), "noreseed.i2p");
        File noReseedFileAlt2 = new File(context.getConfigDir(), ".i2pnoreseed");
        File noReseedFileAlt3 = new File(context.getConfigDir(), "noreseed.i2p");
        if (!noReseedFile.exists() && !noReseedFileAlt1.exists() && !noReseedFileAlt2.exists() && !noReseedFileAlt3.exists()) {
            Log _log = context.logManager().getLog(ReseedChecker.class);
            if (count <= 1)
                _log.error("Downloading peer router information for a new I2P installation");
            else
                _log.error("Very few routerInfo files remaining - reseeding now");
            Reseeder reseeder = new Reseeder(context);
            reseeder.requestReseed();
        }
    }
}
