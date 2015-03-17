package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import net.i2p.data.DataFormatException;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

/**
 *  Update the router.info file whenever its, er, updated
 *
 *  @since 0.8.12 moved from Router.java
 */
public class PersistRouterInfoJob extends JobImpl {
    public PersistRouterInfoJob(RouterContext ctx) { 
        super(ctx); 
    }

    public String getName() { return "Persist Updated Router Information"; }

    public void runJob() {
        Log _log = getContext().logManager().getLog(PersistRouterInfoJob.class);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Persisting updated router info");

        String infoFilename = getContext().getProperty(Router.PROP_INFO_FILENAME, Router.PROP_INFO_FILENAME_DEFAULT);
        File infoFile = new File(getContext().getRouterDir(), infoFilename);

        RouterInfo info = getContext().router().getRouterInfo();

        FileOutputStream fos = null;
        synchronized (getContext().router().routerInfoFileLock) {
            try {
                fos = new SecureFileOutputStream(infoFile);
                info.writeBytes(fos);
            } catch (DataFormatException dfe) {
                _log.error("Error rebuilding the router information", dfe);
            } catch (IOException ioe) {
                _log.error("Error writing out the rebuilt router information", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
}
