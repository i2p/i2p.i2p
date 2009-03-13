package net.i2p.router.web;

import java.io.File;

import net.i2p.router.web.ReseedHandler;
import net.i2p.util.I2PFile;

/**
 *  Copied from RouterConsoleRunner.java
 */
public class ReseedChecker {
    
    public static void checkReseed() {

        System.err.println("Checking to see if we should reseed");
        // we check the i2p installation directory (.) for a flag telling us not to reseed, 
        // but also check the home directory for that flag too, since new users installing i2p
        // don't have an installation directory that they can put the flag in yet.
        File noReseedFile = new I2PFile(new I2PFile(System.getProperty("user.home")), ".i2pnoreseed");
        File noReseedFileAlt1 = new I2PFile(new I2PFile(System.getProperty("user.home")), "noreseed.i2p");
        File noReseedFileAlt2 = new I2PFile(".i2pnoreseed");
        File noReseedFileAlt3 = new I2PFile("noreseed.i2p");
        if (!noReseedFile.exists() && !noReseedFileAlt1.exists() && !noReseedFileAlt2.exists() && !noReseedFileAlt3.exists()) {
            File netDb = new I2PFile("netDb");
            // sure, some of them could be "my.info" or various leaseSet- files, but chances are, 
            // if someone has those files, they've already been seeded (at least enough to let them
            // get i2p started - they can reseed later in the web console)
            String names[] = (netDb.exists() ? netDb.list() : null);
            if ( (names == null) || (names.length < 15) ) {
                System.err.println("Yes, reseeding now");
                ReseedHandler reseedHandler = new ReseedHandler();
                reseedHandler.requestReseed();
            }
        }
    }
    
}
