package net.i2p.app;

/**
 *  A service to send messages to users.
 *  This service is currently provided by desktopgui (when supported and enabled).
 *  Other applications may support this interface in the future.
 *
 *  Example usage:
 *
 * <pre>
 *     ClientAppManager cmgr = _context.clientAppManager();
 *     if (cmgr != null) {
 *         NotificationService ns = (NotificationService) cmgr.getRegisteredApp("desktopgui");
 *         if (ns != null)
 *             ns.notify("foo", null, Log.INFO, _t("foo"), _t("message"), "/foo/bar");
 *     }
 * </pre>
 *
 *  @since 0.9.53
 */
public interface NotificationService {

    /**
     *  Send a (possibly delayed) notification to the user.
     *
     *  @param source e.g. "i2psnark"
     *  @param category may be null, probably unused
     *  @param priority higher is higher, Log.INFO etc. recommended, probably unused
     *  @param title for the popup, translated
     *  @param message translated
     *  @param path in console for more information, starting with /, must be URL-escaped, or null
     *  @return an ID to use with cancel() or update(), or -1 on failure
     */
    public int notify(String source, String category, int priority, String title, String message, String path);

    /**
     *  Cancel a notification if possible.
     *
     *  @param id as received from notify()
     *  @return success
     */
    public boolean cancel(int id);

    /**
     *  Update the text of a notification if possible.
     *
     *  @param id as received from notify()
     *  @param title for the popup, translated
     *  @param message translated
     *  @param path in console starting with /, must be URL-escaped, or null
     *  @return success
     */
    public boolean update(int id, String title, String message, String path);
}
