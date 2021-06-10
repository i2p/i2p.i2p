package net.i2p.update;

import java.io.IOException;
import java.io.File;

/**
 *  An external class to handle complex processing of update files,
 *  where necessary instead of simply copying i2pupdate.zip to the config dir.
 *
 *  @since 0.9.51
 */
public interface UpdatePostProcessor {
    
    /**
     *  Notify the post-processor that an update has been downloaded and verified.
     *  The version will be higher than the currently-installed version.
     *
     *  This method MUST immediately postprocess, copy, or rename the file, which
     *  will be located in the temporary directory.
     *  Caller will delete the file if it remains, immediately after this method returns.
     *
     *  This method MUST throw an IOException on all errors. The IOException will be
     *  displayed to the user in the console, so it should be clear.
     *
     *  This method must not trigger the shutdown itself.
     *  Caller will trigger the shutdown if so configured.
     *
     *  If the post-processor needs to perform any actions at shutdown, it should
     *  call I2PAppContext.addShutdownTask() or RouterContext.addFinalShutdownTask().
     *  See javadocs for restrictions on final shutdown tasks.
     *  Note that the router's temporary directory is deleted at shutdown,
     *  BEFORE the final shutdown tasks are run.
     *
     *  After this call, the router will do a graceful shutdown if so configured,
     *  or will notify the user in the console to manually shut down the router.
     *  Therefore, the shutdown may happen immediately, or be delayed for 10 minutes,
     *  or may be hours, days, or weeks later.
     *
     *  In rare cases, a newer update may be downloaded before the shutdown
     *  for the first update, and this method may be called again with the newer version.
     *  Implementers must take care to properly handle multiple calls.
     *
     *  @param type only ROUTER_SIGNED_SU3 and ROUTER_DEV_SU3 are currently supported
     *  @param fileType a TYPE_xxx file type code from the SU3File, 0-255
     *  @param version the version string from the SU3File
     *  @param file in the temp directory, as extracted from the validated su3 file
     *  @throws IOException on all errors, message will be displayed to the user
     */
    public void updateDownloadedandVerified(UpdateType type, int fileType, String version, File file) throws IOException;
}
