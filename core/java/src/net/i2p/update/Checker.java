package net.i2p.update;

/**
 *  Controls one or more types of updates.
 *  This must be registered with the UpdateManager.
 *
 *  @since 0.9.4
 */
public interface Checker {
    
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

}
