package net.i2p.update;

import java.net.URI;
import java.util.List;

/**
 *  Controls one or more types of updates.
 *  This must be registered with the UpdateManager.
 *
 *  @since 0.9.2
 */
public interface Updater {
    
    /**
     *  Check for updates.
     *  Should not block.
     *  If any are found, call back to UpdateManager.notifyUpdateAvailable().
     *
     *  @param id plugin name or ignored
     *  @param maxTime how long you have
     *  @return active task or null if unable to check
     */
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime);

    /**
     *  Start a download and return a handle to the download task.
     *  Should not block.
     *
     *  @param id plugin name or ignored
     *  @param maxTime how long you have
     *  @return active task or null if unable to download
     */
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                               String id, String newVersion, long maxTime);
}
