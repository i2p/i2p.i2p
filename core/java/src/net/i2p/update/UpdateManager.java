package net.i2p.update;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 *  The central resource coordinating updates.
 *  This must be registered with the context.
 *
 *  The UpdateManager starts and stops all updates,
 *  and controls notification to the user.
 *
 *  @since 0.9.4
 */
public interface UpdateManager {
    
    /**
     *  The name we register with the ClientAppManager
     *  @since 0.9.12
     */
    public static final String APP_NAME = "update";

    /**
     *  Call once for each type/method pair.
     */
    public void register(Updater updater, UpdateType type, UpdateMethod method, int priority);

    public void register(Checker checker, UpdateType type, UpdateMethod method, int priority);

    public void unregister(Updater updater, UpdateType type, UpdateMethod method);

    public void unregister(Checker checker, UpdateType type, UpdateMethod method);
    
    public void start();

    public void shutdown();

    /**
     *  Called by the Checker, either after check() was called, or it found out on its own.
     *  Use this if there is only one UpdateMethod; otherwise use the Map method below.
     *
     *  @param newsSource who told us
     *  @param id plugin name for plugins, ignored otherwise
     *  @param method How to get the new version
     *  @param updateSources Where to get the new version
     *  @param newVersion The new version available
     *  @param minVersion The minimum installed version to be able to update to newVersion
     *  @return true if we didn't know already
     */
    public boolean notifyVersionAvailable(UpdateTask task, URI newsSource,
                                          UpdateType type, String id,
                                          UpdateMethod method, List<URI> updateSources,
                                          String newVersion, String minVersion);

    /**
     *  Called by the Checker, either after check() was called, or it found out on its own.
     *  Checkers must use this method if there are multiple UpdateMethods discoverd simultaneously.
     *
     *  @param newsSource who told us
     *  @param id plugin name for plugins, ignored otherwise
     *  @param sourceMap Mapping of methods to sources
     *  @param newVersion The new version available
     *  @param minVersion The minimum installed version to be able to update to newVersion
     *  @return true if we didn't know already
     *  @since 0.9.6
     */
    public boolean notifyVersionAvailable(UpdateTask task, URI newsSource,
                                          UpdateType type, String id,
                                          Map<UpdateMethod, List<URI>> sourceMap,
                                          String newVersion, String minVersion);

    /**
     *  A new version is available but cannot be downloaded or installed due to some constraint.
     *  The manager should notify the user.
     *  Called by the Checker, either after check() was called, or it found out on its own.
     *
     *  @param newsSource who told us
     *  @param id plugin name for plugins, ignored otherwise
     *  @param newVersion The new version available
     *  @param message A translated message to be displayed to the user, non-null
     *  @since 0.9.9
     */
    public void notifyVersionConstraint(UpdateTask task, URI newsSource,
                                        UpdateType type, String id,
                                        String newVersion, String message);

    /**
     *  Called by the Checker after check() was called and all notifyVersionAvailable() callbacks are finished
     *  @param newer notifyVersionAvailable was called
     *  @param success check succeeded (newer or not)
     */
    public void notifyCheckComplete(UpdateTask task, boolean newer, boolean success);

    public void notifyProgress(UpdateTask task, String status);
    public void notifyProgress(UpdateTask task, String status, long downloaded, long totalSize);

    /**
     *  Not necessarily the end if there are more URIs to try.
     *  @param t may be null
     */
    public void notifyAttemptFailed(UpdateTask task, String reason, Throwable t);

    /**
     *  The task has finished and failed.
     *  @param t may be null
     */
    public void notifyTaskFailed(UpdateTask task, String reason, Throwable t);

    /**
     *  An update has been downloaded but not verified.
     *  The manager will verify it.
     *  Caller should delete the file upon return, unless it will share it with others,
     *  e.g. on a torrent.
     *
     *  @param actualVersion may be higher (or lower?) than the version requested
     *  @param file a valid format for the task's UpdateType
     *  @return true if valid, false if corrupt
     */
    public boolean notifyComplete(UpdateTask task, String actualVersion, File file);
    
    /**
     *  Is an update available?
     *  Blocking.
     *  An available update may still have a constraint or lack sources.
     *  @param type the UpdateType of this request
     *  @return new version or null if nothing newer is available
     *  @since 0.9.21
     */
    public String checkAvailable(UpdateType type);
    
    /**
     *  Is an update available?
     *  Blocking.
     *  An available update may still have a constraint or lack sources.
     *  @param type the UpdateType of this request
     *  @param maxWait max time to block
     *  @return new version or null if nothing newer is available
     *  @since 0.9.21
     */
    public String checkAvailable(UpdateType type, long maxWait);
    
    /**
     *  Is an update available?
     *  Blocking.
     *  An available update may still have a constraint or lack sources.
     *  @param type the UpdateType of this request
     *  @param maxWait max time to block
     *  @param id id of this request
     *  @return new version or null if nothing newer is available
     *  @since 0.9.21
     */
    public String checkAvailable(UpdateType type, String id, long maxWait);
    
    /**
     *  Is a router update being downloaded?
     *  @return true iff router update is being downloaded
     *  @since 0.9.21
     */
    public boolean isUpdateInProgress();
    
    /**
     *  Is a router update being downloaded?
     *  @param type the UpdateType of this request
     *  @return true iff router update is being downloaded
     *  @since 0.9.21
     */
    public boolean isUpdateInProgress(UpdateType type);
    
    /**
     *  Is a router update being downloaded?
     *  @param type the UpdateType of this request
     *  @param id of this request
     *  @return true iff router update is being downloaded
     *  @since 0.9.21
     */
    public boolean isUpdateInProgress(UpdateType type, String id);

    /**
     *  Non-blocking. Does not check.
     *  Fails if check or update already in progress.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @param type the UpdateType of this request   
     *  @return true if task started
     *  @since 0.9.21
     */
    public boolean update(UpdateType type);
    
    /**
     *  Non-blocking. Does not check.
     *  Fails if check or update already in progress.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @param type the UpdateType of this request   
     *  @param id id of this request
     *  @return true if task started
     *  @since 0.9.21
     */
    public boolean update(UpdateType type, String id);
    
    /**
     *  Non-blocking. Does not check.
     *  Fails if check or update already in progress.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @param type the UpdateType of this request   
     *  @param maxTime not honored by all Updaters
     *  @return true if task started
     *  @since 0.9.21
     */
    public boolean update(UpdateType type, long maxTime);
    
    /**
     *  Non-blocking. Does not check.
     *  Fails if check or update already in progress.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @param type the UpdateType of this request   
     *  @param maxTime not honored by all Updaters
     *  @param id id of this request
     *  @return true if task started
     *  @since 0.9.21
     */
    public boolean update(UpdateType type, String id, long maxTime);
    
    /**
     *  The status on any update current or last finished.
     *  @return status or ""
     *  @since 0.9.21
     */
    public String getStatus();

    /**
     *  For debugging
     */
    public void renderStatusHTML(Writer out) throws IOException;
}
