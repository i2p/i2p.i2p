package net.i2p.router.update;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.util.RFC822Date;
import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NewsHelper;
import net.i2p.router.web.PluginStarter;
import net.i2p.update.*;
import static net.i2p.update.UpdateType.*;
import static net.i2p.update.UpdateMethod.*;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
import net.i2p.util.VersionComparator;

/**
 *  The central resource coordinating updates.
 *  This must be registered with the context.
 *
 *  The UpdateManager starts and stops all updates,
 *  prevents multiple updates as appropriate,
 *  and controls notification to the user.
 *
 *  @since 0.9.4
 */
public class ConsoleUpdateManager implements UpdateManager {
    
    private final RouterContext _context;
    private final Log _log;
    private final Collection<RegisteredUpdater> _registeredUpdaters;
    private final Collection<RegisteredChecker> _registeredCheckers;
    /** active checking tasks */
    private final Collection<UpdateTask> _activeCheckers;
    /** active updating tasks, pointing to the next ones to try */
    private final Map<UpdateTask, List<RegisteredUpdater>> _downloaders;
    /** as reported by checkers */
    private final Map<UpdateItem, VersionAvailable> _available;
    /** downloaded but NOT installed */
    private final Map<UpdateItem, Version> _downloaded;
    /** downloaded AND installed */
    private final Map<UpdateItem, Version> _installed;
    private static final DecimalFormat _pct = new DecimalFormat("0.0%");
    private static final VersionComparator _versionComparator = new VersionComparator();

    private volatile String _status;

    private static final long DEFAULT_MAX_TIME = 3*60*60*1000L;

    public ConsoleUpdateManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(ConsoleUpdateManager.class);
        _registeredUpdaters = new ConcurrentHashSet();
        _registeredCheckers = new ConcurrentHashSet();
        _activeCheckers = new ConcurrentHashSet();
        _downloaders = new ConcurrentHashMap();
        _available = new ConcurrentHashMap();
        _downloaded = new ConcurrentHashMap();
        _installed = new ConcurrentHashMap();
        _status = "";
    }

    public static ConsoleUpdateManager getInstance() {
        return (ConsoleUpdateManager) I2PAppContext.getGlobalContext().updateManager();
    }

    public void start() {
        notifyInstalled(NEWS, "", Long.toString(NewsHelper.lastUpdated(_context)));
        notifyInstalled(ROUTER_SIGNED, "", RouterVersion.VERSION);
        // hack to init from the current news file... do this before we register Updaters
        (new NewsFetcher(_context, Collections.EMPTY_LIST)).checkForUpdates();
        for (String plugin : PluginStarter.getPlugins()) {
            Properties props = PluginStarter.pluginProperties(_context, plugin);
            String ver = props.getProperty("version");
            if (ver != null)
                notifyInstalled(PLUGIN, plugin, ver);
        }

        _context.registerUpdateManager(this);
        Updater u = new DummyHandler(_context);
        register(u, TYPE_DUMMY, HTTP, 0);
        // register news before router, so we don't fire off an update
        // right at instantiation if the news is already indicating a new version
        Checker c = new NewsHandler(_context);
        register(c, NEWS, HTTP, 0);
        register(c, ROUTER_SIGNED, HTTP, 0);  // news is an update checker for the router
        u = new UpdateHandler(_context);
        register(u, ROUTER_SIGNED, HTTP, 0);
        UnsignedUpdateHandler uuh = new UnsignedUpdateHandler(_context);
        register((Checker)uuh, ROUTER_UNSIGNED, HTTP, 0);
        register((Updater)uuh, ROUTER_UNSIGNED, HTTP, 0);
        PluginUpdateHandler puh = new PluginUpdateHandler(_context);
        register((Checker)puh, PLUGIN, HTTP, 0);
        register((Checker)puh, PLUGIN_INSTALL, HTTP, 0);
        register((Updater)puh, PLUGIN_INSTALL, HTTP, 0);
        new NewsTimerTask(_context);
    }

    public void shutdown() {
        _context.unregisterUpdateManager(this);
        stopChecks();
        stopUpdates();
        _registeredUpdaters.clear();
        _registeredCheckers.clear();
        _available.clear();
        _downloaded.clear();
        _installed.clear();
    }

    /**
     *  The status on any update current or last finished.
     *  @return status or ""
     */
    public String getStatus() {
        return _status;
    }
    
    public String checkAvailable(UpdateType type, long maxWait) {
        return checkAvailable(type, "", maxWait);
    }

    /**
     *  Is an update available?
     *  Blocking.
     *  @param maxWait max time to block
     *  @return new version or null if nothing newer is available
     */
    public String checkAvailable(UpdateType type, String id, long maxWait) {
//// update too?
        if (isCheckInProgress(type, id) || isUpdateInProgress(type, id)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Check or update already in progress for: " + type + ' ' + id);
            return null;
        }
        for (RegisteredChecker r : _registeredCheckers) {
            if (r.type == type) {
                UpdateTask t = r.checker.check(type, r.method, id, "FIXME", maxWait);
                if (t != null) {
                    synchronized(t) {
                        try {
                            t.wait(maxWait);
                        } catch (InterruptedException ie) {}
                    }
                    return getUpdateAvailable(type, id);
                }
            }
        }
        return null;
    }

    /**
     *  Fire off a checker task
     *  Non-blocking.
     */
    public void check(UpdateType type, String id) {
        if (isCheckInProgress(type, id)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Check or update already in progress for: " + type + ' ' + id);
            return;
        }
        for (RegisteredChecker r : _registeredCheckers) {
            if (r.type == type) {
/// fixme "" will put an entry in _available for everything grrrrr????
                UpdateTask t = r.checker.check(type, r.method, id, "", 5*60*1000);
                if (t != null)
                    break;
            }
        }
    }

    /**
     *  Is an update available?
     *  Non-blocking, returns result of last check or notification from an Updater
     *  @return new version or null if nothing newer is available
     */
    public String getUpdateAvailable(UpdateType type) {
        return getUpdateAvailable(type, "");
    }

    /**
     *  Is an update available?
     *  Non-blocking, returns result of last check or notification from an Updater
     *  @return new version or null if nothing newer is available
     */
    public String getUpdateAvailable(UpdateType type, String id) {
        Version v = _available.get(new UpdateItem(type, id));
        if (v == null)
            return null;
        return v.version;
    }

    /**
     *  Is an update downloaded?
     *  Non-blocking, returns result of last download
     *  @return new version or null if nothing was downloaded
     */
    public String getUpdateDownloaded(UpdateType type) {
        return getUpdateDownloaded(type, "");
    }

    /**
     *  Is an update downloaded?
     *  Non-blocking, returns result of last download
     *  @return new version or null if nothing was downloaded
     */
    public String getUpdateDownloaded(UpdateType type, String id) {
        Version v = _downloaded.get(new UpdateItem(type, id));
        if (v == null)
            return null;
        return v.version;
    }

    /**
     *  Is any download in progress?
     *  Does not include checks.
     */
    public boolean isUpdateInProgress() {
        return !_downloaders.isEmpty();
    }

    /**
     *  Is a download in progress?
     */
    public boolean isUpdateInProgress(UpdateType type) {
        return isUpdateInProgress(type, "");
    }

    /**
     *  Is a download in progress?
     */
    public boolean isUpdateInProgress(UpdateType type, String id) {
        for (UpdateTask t : _downloaders.keySet()) {
            if (t.getType() == type && id.equals(t.getID()))
                return true;
        }
        return false;
    }

    /**
     *  Stop all downloads in progress
     */
    public void stopUpdates() {
        for (UpdateTask t : _downloaders.keySet()) {
            t.shutdown();
        }
        _downloaders.clear();
    }

    /**
     *  Stop this download
     */
    public void stopUpdate(UpdateType type) {
        stopUpdate(type, "");
    }

    /**
     *  Stop this download
     */
    public void stopUpdate(UpdateType type, String id) {
        for (Iterator<UpdateTask> iter = _downloaders.keySet().iterator(); iter.hasNext(); ) {
            UpdateTask t = iter.next();
            if (t.getType() == type && id.equals(t.getID())) {
                iter.remove();
                t.shutdown();
            }
        }
    }

    /**
     *  Is any check in progress?
     *  Does not include updates.
     */
    public boolean isCheckInProgress() {
        return !_activeCheckers.isEmpty();
    }

    /**
     *  Is a check in progress?
     */
    public boolean isCheckInProgress(UpdateType type) {
        return isCheckInProgress(type, "");
    }

    /**
     *  Is a check in progress?
     */
    public boolean isCheckInProgress(UpdateType type, String id) {
        for (UpdateTask t : _activeCheckers) {
            if (t.getType() == type && id.equals(t.getID()))
                return true;
        }
        return false;
    }

    /**
     *  Stop all checks in progress
     */
    public void stopChecks() {
        for (UpdateTask t : _activeCheckers) {
            t.shutdown();
        }
        _activeCheckers.clear();
    }

    /**
     *  Stop this check
     */
    public void stopCheck(UpdateType type) {
        stopCheck(type, "");
    }

    /**
     *  Stop this check
     */
    public void stopCheck(UpdateType type, String id) {
        for (Iterator<UpdateTask> iter = _activeCheckers.iterator(); iter.hasNext(); ) {
            UpdateTask t = iter.next();
            if (t.getType() == type && id.equals(t.getID())) {
                iter.remove();
                t.shutdown();
            }
        }
    }

    /**
     *  Install a plugin. Non-blocking.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @return true if task started
     */
    public boolean installPlugin(URI uri) {
        String fakeName = Long.toString(_context.random().nextLong());
        List<URI> uris = Collections.singletonList(uri);
        UpdateItem fake = new UpdateItem(PLUGIN_INSTALL, fakeName);
        VersionAvailable va = new VersionAvailable("", "", HTTP, uris);
        _available.put(fake, va);
        return update(PLUGIN_INSTALL, fakeName);
    }

    /**
     *  Non-blocking. Does not check.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  Max time 3 hours by default but not honored by all Updaters
     *  @return true if task started
     */
    public boolean update(UpdateType type) {
        return update(type, "", DEFAULT_MAX_TIME);
    }

    /**
     *  Non-blocking. Does not check.
     *  Max time 3 hours by default but not honored by all Updaters
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @return true if task started
     */
    public boolean update(UpdateType type, String id) {
        return update(type, id, DEFAULT_MAX_TIME);
    }

    /**
     *  Non-blocking. Does not check.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @param maxTime not honored by all Updaters
     *  @return true if task started
     */
    public boolean update(UpdateType type, long maxTime) {
        return update(type, "", maxTime);
    }

    /**
     *  Non-blocking. Does not check.
     *  Fails if check or update already in progress.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @param maxTime not honored by all Updaters
     *  @return true if task started
     */
    public boolean update(UpdateType type, String id, long maxTime) {
        if (isCheckInProgress(type, id)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Check already in progress for: " + type + ' ' + id);
            return false;
        }
        return update_fromCheck(type, id, maxTime);
    }

    /**
     *  Non-blocking. Does not check.
     *  Fails update already in progress. Use this to call from within a checker task.
     *  If returns true, then call isUpdateInProgress() in a loop
     *  @param maxTime not honored by all Updaters
     *  @return true if task started
     */
    private boolean update_fromCheck(UpdateType type, String id, long maxTime) {
        if (isUpdateInProgress(type, id)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Update already in progress for: " + type + ' ' + id);
            return false;
        }
        List<URI> updateSources = null;
        UpdateItem ui = new UpdateItem(type, id);
        VersionAvailable va = _available.get(ui);
        if (va == null)
            return false;
        List<RegisteredUpdater> sorted = new ArrayList(_registeredUpdaters);
        Collections.sort(sorted);
        return retry(ui, va.sourceMap, sorted, maxTime) != null;
    }

    private UpdateTask retry(UpdateItem ui,
                             Map<UpdateMethod, List<URI>> sourceMap,
                             List<RegisteredUpdater> toTry, long maxTime) {
        for (Iterator<RegisteredUpdater> iter = toTry.iterator(); iter.hasNext(); ) {
            RegisteredUpdater r = iter.next();
            iter.remove();
            // check in case unregistered later
            if (!_registeredUpdaters.contains(r))
                continue;
            for (Map.Entry<UpdateMethod, List<URI>> e : sourceMap.entrySet()) {
                UpdateMethod meth = e.getKey();
                if (r.type == ui.type && r.method == meth) {
                                                                                    // fixme
                    UpdateTask t = r.updater.update(ui.type, meth, e.getValue(), ui.id, "", maxTime);
                    if (t != null) {
                        // race window here
                        //  store the remaining ones for retrying
                        _downloaders.put(t, toTry);
                        return t;
                    }
                }
            }
        }
        return null;
    }

    /////////// start UpdateManager interface

    /**
     *  Call once for each type/method pair.
     */
    public void register(Updater updater, UpdateType type, UpdateMethod method, int priority) {
        RegisteredUpdater ru = new RegisteredUpdater(updater, type, method, priority);
        if (_log.shouldLog(Log.INFO))
            _log.info("Registering " + ru);
        _registeredUpdaters.add(ru);
    }

    public void unregister(Updater updater, UpdateType type, UpdateMethod method) {
        RegisteredUpdater ru = new RegisteredUpdater(updater, type, method, 0);
        if (_log.shouldLog(Log.INFO))
            _log.info("Unregistering " + ru);
        _registeredUpdaters.remove(ru);
    }
    
    public void register(Checker updater, UpdateType type, UpdateMethod method, int priority) {
        RegisteredChecker rc = new RegisteredChecker(updater, type, method, priority);
        if (_log.shouldLog(Log.INFO))
            _log.info("Registering " + rc);
        _registeredCheckers.add(rc);
    }

    public void unregister(Checker updater, UpdateType type, UpdateMethod method) {
        RegisteredChecker rc = new RegisteredChecker(updater, type, method, 0);
        if (_log.shouldLog(Log.INFO))
            _log.info("Unregistering " + rc);
        _registeredCheckers.remove(rc);
    }
    
    /**
     *  Called by the Updater, either after check() was called, or it found out on its own.
     *
     *  @param newsSource who told us
     *  @param id plugin name for plugins, ignored otherwise
     *  @param updateSourcew Where to get the new version
     *  @param newVersion The new version available
     *  @param minVersion The minimum installed version to be able to update to newVersion
     *  @return true if it's newer
     */
    public boolean notifyVersionAvailable(UpdateTask task, URI newsSource,
                                          UpdateType type, String id,
                                          UpdateMethod method, List<URI> updateSources,
                                          String newVersion, String minVersion) {
        if (type == NEWS) {
            // shortcut
            notifyInstalled(NEWS, "", newVersion);
            return true;
        }
        UpdateItem ui = new UpdateItem(type, id);
        VersionAvailable newVA = new VersionAvailable(newVersion, minVersion, method, updateSources);
        Version old = _installed.get(ui);
        if (_log.shouldLog(Log.INFO))
            _log.info("notifyVersionAvailable " + ui + ' ' + newVA + " old: " + old);
        if (old != null && old.compareTo(newVA) >= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(ui.toString() + ' ' + old + " already installed");
            return false;
        }
        old = _downloaded.get(ui);
        if (old != null && old.compareTo(newVA) >= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(ui.toString() + ' ' + old + " already downloaded");
            return false;
        }
        VersionAvailable oldVA = _available.get(ui);
        if (oldVA != null)  {
            int comp = oldVA.compareTo(newVA);
            if (comp > 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(ui.toString() + ' ' + oldVA + " already available");
                return false;
            }
            if (comp == 0) {
                if (oldVA.sourceMap.putIfAbsent(method, updateSources) == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(ui.toString() + ' ' + oldVA + " updated with new source method");
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(ui.toString() + ' ' + oldVA + " already available");
                }
                return false;
            }
        }

        if (_log.shouldLog(Log.INFO))
            _log.info(ui.toString() + ' ' + newVA + " now available");
        _available.put(ui, newVA);

        String msg = null;
        switch (type) {
            case NEWS:
                break;

            case ROUTER_SIGNED:
            case ROUTER_SIGNED_PACK200:
            case ROUTER_UNSIGNED:
                if (shouldInstall() &&
                    !(isUpdateInProgress(ROUTER_SIGNED) ||
                      isUpdateInProgress(ROUTER_SIGNED_PACK200) ||
                      isUpdateInProgress(ROUTER_UNSIGNED))) {
                    update_fromCheck(type, id, DEFAULT_MAX_TIME);
                }
                // ConfigUpdateHandler, SummaryHelper, SummaryBarRenderer handle status display
                break;

            case PLUGIN:
                msg = "<b>" + _("New plugin version {0} is available", newVersion) + "</b>";
                break;

            default:
                break;
        }
        if (msg != null)
            finishStatus(msg);
        return true;
    }

    /**
     *  Called by the Updater after check() was called and all notifyVersionAvailable() callbacks are finished
     */
    public void notifyCheckComplete(UpdateTask task, boolean newer, boolean success) {
        if (_log.shouldLog(Log.INFO))
            _log.info(task.toString() + " complete");
        _activeCheckers.remove(task);
        String msg = null;
        switch (task.getType()) {
            case NEWS:
            case ROUTER_SIGNED:
            case ROUTER_SIGNED_PACK200:
            case ROUTER_UNSIGNED:
                // ConfigUpdateHandler, SummaryHelper, SummaryBarRenderer handle status display
                break;

            case PLUGIN:
                if (!success)
                    msg = "<b>" + _("Update check failed for plugin {0}", task.getID()) + "</b>";
                else if (!newer)
                    msg = "<b>" + _("No new version is available for plugin {0}", task.getID()) + "</b>";
                /// else success.... message for that?

                break;

            default:
                break;
        }
        if (msg != null)
            finishStatus(msg);
        synchronized(task) {
            task.notifyAll();
        }
// TODO
    }

    public void notifyProgress(UpdateTask task, String status, long downloaded, long totalSize) {
        StringBuilder buf = new StringBuilder(64);
        buf.append(status).append(' ');
        double pct = ((double)downloaded) / ((double)totalSize);
        synchronized (_pct) {
            buf.append(_pct.format(pct));
        }
        buf.append("<br>\n");
        buf.append(_("{0}B transferred", DataHelper.formatSize2(downloaded)));
        updateStatus(buf.toString());
    }

    /**
     *  @param task may be null
     */
    public void notifyProgress(UpdateTask task, String status) {
        updateStatus(status);
    }

    /**
     *  An expiring status
     *  @param task may be null
     */
    public void notifyComplete(UpdateTask task, String status) {
        finishStatus(status);
    }

    /**
     *  Not necessarily the end if there are more URIs to try.
     *  @param t may be null
     */
    public void notifyAttemptFailed(UpdateTask task, String reason, Throwable t) {
        _log.warn("Attempt failed " + task + ": " + reason, t);
    }

    /**
     *  The task has finished and failed.
     *  @param t may be null
     */
    public void notifyTaskFailed(UpdateTask task, String reason, Throwable t) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Failed " + task + ": " + reason, t);
        List<RegisteredUpdater> toTry = _downloaders.get(task);
        if (toTry != null) {
            UpdateItem ui = new UpdateItem(task.getType(), task.getID());
            VersionAvailable va = _available.get(ui);
            if (va != null) {
                UpdateTask next = retry(ui, va.sourceMap, toTry, DEFAULT_MAX_TIME);  // fixme old maxtime lost
                if (next != null) {
                   if (_log.shouldLog(Log.WARN))
                       _log.warn("Retrying with " + next);
                }
            }
        }
        _downloaders.remove(task);
///// for certain types only
        finishStatus("<b>" + _("Transfer failed from {0}", linkify(task.getURI().toString())) + "</b>");
    }

    /**
     *  An update has been downloaded but not verified.
     *  The manager will verify it.
     *  Caller should delete the file upon return, unless it will share it with others,
     *  e.g. on a torrent.
     *  If the return value is false, caller must call notifyTaskFailed() or notifyComplete()
     *  again.
     *
     *  @param actualVersion may be higher (or lower?) than the version requested
     *  @param file a valid format for the task's UpdateType
     *  @return true if valid, false if corrupt
     */
    public boolean notifyComplete(UpdateTask task, String actualVersion, File file) {
        if (_log.shouldLog(Log.INFO))
            _log.info(task.toString() + " complete");
        boolean rv = false;
        switch (task.getType()) {
            case TYPE_DUMMY:
            case NEWS:
                rv = true;
                break;

            case ROUTER_SIGNED:
            case ROUTER_SIGNED_PACK200:
                rv = handleSudFile(task.getURI(), actualVersion, file);
                if (rv)
                    notifyDownloaded(task.getType(), task.getID(), actualVersion);
                break;

            case ROUTER_UNSIGNED:
                rv = handleUnsignedFile(task.getURI(), actualVersion, file);
/////// FIXME RFC822 or long?
                if (rv)
                    notifyDownloaded(task.getType(), task.getID(), actualVersion);
                break;

            case PLUGIN:
/// FIXME probably handled in PluginUpdateRunner??????????
                rv = handlePluginFile(task.getURI(), actualVersion, file);
                break;

            default:
                break;
        }
        if (rv)
            _downloaders.remove(task);
        return rv;
    }

    ///////// End UpdateManager interface

    /**
     *  Adds to installed, removes from downloaded and available
     *  @param version null to remove from installed
     */
    private void notifyInstalled(UpdateType type, String id, String version) {
        UpdateItem ui = new UpdateItem(type, id);
        if (version == null) {
            _installed.remove(ui);
            if (_log.shouldLog(Log.INFO))
                _log.info(ui + " removed");
            return;
        }
        Version ver = new Version(version);
        if (_log.shouldLog(Log.INFO))
            _log.info(ui + " " + ver + " installed");
        _installed.put(ui, ver);
        Version old = _downloaded.get(ui);
        if (old != null && old.compareTo(ver) <= 0)
            _downloaded.remove(ui);
        old = _available.get(ui);
        if (old != null && old.compareTo(ver) <= 0)
            _available.remove(ui);
    }

    /**
     *  Adds to downloaded, removes from available
     */
    private void notifyDownloaded(UpdateType type, String id, String version) {
        UpdateItem ui = new UpdateItem(type, id);
        Version ver = new Version(version);
        if (_log.shouldLog(Log.INFO))
            _log.info(ui + " " + ver + " downloaded");
        _downloaded.put(ui, ver);
        // one trumps the other
        if (type == ROUTER_SIGNED)
            _downloaded.remove(new UpdateItem(ROUTER_UNSIGNED, ""));
        else if (type == ROUTER_UNSIGNED)
            _downloaded.remove(new UpdateItem(ROUTER_SIGNED, ""));
        Version old = _available.get(ui);
        if (old != null && old.compareTo(ver) <= 0)
            _available.remove(ui);
    }
    
    /** from NewsFetcher */
    private boolean shouldInstall() {
        String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
        if ("notify".equals(policy) || NewsHelper.dontInstall(_context))
            return false;
//////////////////
        File zip = new File(_context.getRouterDir(), Router.UPDATE_FILE);
        return !zip.exists();
    }
    
    /**
     *  Where to find various resources
     *  @return non-null may be empty
     */
    public List<URI> getUpdateURLs(UpdateType type, String id, UpdateMethod method) {
        VersionAvailable va = _available.get(new UpdateItem(type, id));
        if (va != null) {
            List<URI> rv = va.sourceMap.get(method);
            if (rv != null)
                return rv;
        }

        switch (type) {
            case NEWS:
                // handled in NewsHandler
                break;

            case ROUTER_SIGNED:
            case ROUTER_SIGNED_PACK200:
                String URLs = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_URL, ConfigUpdateHandler.DEFAULT_UPDATE_URL);
                StringTokenizer tok = new StringTokenizer(URLs, " ,\r\n");
                List<URI> rv = new ArrayList();
                while (tok.hasMoreTokens()) {
                    try {
                        rv.add(new URI(tok.nextToken().trim()));
                    } catch (URISyntaxException use) {}
                }
                Collections.shuffle(rv, _context.random());
                return rv;

            case ROUTER_UNSIGNED:
                String url = _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL);
                if (url != null) {
                    try {
                        return Collections.singletonList(new URI(url));
                    } catch (URISyntaxException use) {}
                }
                break;

            case PLUGIN:
                Properties props = PluginStarter.pluginProperties(_context, id);
                String oldVersion = props.getProperty("version");
                String xpi2pURL = props.getProperty("updateURL");
                if (xpi2pURL != null) {
                    try {
                        return Collections.singletonList(new URI(xpi2pURL));
                    } catch (URISyntaxException use) {}
                }
                break;

             default:
                break;
        }
        return Collections.EMPTY_LIST;
    }

    /**
     *  @return success
     */
    private boolean handleSudFile(URI uri, String actualVersion, File f) {
        String url = uri.toString();
        // Process the .sud/.su2 file
        updateStatus("<b>" + _("Update downloaded") + "</b>");
        TrustedUpdate up = new TrustedUpdate(_context);
        File to = new File(_context.getRouterDir(), Router.UPDATE_FILE);
        String err = up.migrateVerified(RouterVersion.VERSION, f, to);
///////////
        // caller must delete now.. why?
        //f.delete();
        if (err == null) {
            String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
            // So unsigned update handler doesn't overwrite unless newer.
/// FIXME
            //String lastmod = _get.getLastModified();
            String lastmod = null;
            long modtime = 0;
            if (lastmod != null)
                modtime = RFC822Date.parse822Date(lastmod);
            if (modtime <= 0)
                modtime = _context.clock().now();
            _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATE_TIME, "" + modtime);

            if ("install".equals(policy)) {
                _log.log(Log.CRIT, "Update was VERIFIED, restarting to install it");
                updateStatus("<b>" + _("Update verified") + "</b><br>" + _("Restarting"));
                restart();
            } else {
                _log.log(Log.CRIT, "Update was VERIFIED, will be installed at next restart");
                StringBuilder buf = new StringBuilder(64);
                buf.append("<b>").append(_("Update downloaded")).append("<br>");
                if (_context.hasWrapper())
                    buf.append(_("Click Restart to install"));
                else
                    buf.append(_("Click Shutdown and restart to install"));
                if (up.newVersion() != null)
                    buf.append(' ').append(_("Version {0}", up.newVersion()));
                buf.append("</b>");
                updateStatus(buf.toString());
            }
        } else {
            _log.log(Log.CRIT, err + " from " + url);
            updateStatus("<b>" + err + ' ' + _("from {0}", linkify(url)) + " </b>");
        }
        return err == null;
    }

    /**
     *  @return success
     */
    private boolean handleUnsignedFile(URI uri, String lastmod, File updFile) {
        if (FileUtil.verifyZip(updFile)) {
            updateStatus("<b>" + _("Update downloaded") + "</b>");
        } else {
            updFile.delete();
            String url = uri.toString();
            updateStatus("<b>" + _("Unsigned update file from {0} is corrupt", url) + "</b>");
            _log.log(Log.CRIT, "Corrupt zip file from " + url);
            return false;
        }
        File to = new File(_context.getRouterDir(), Router.UPDATE_FILE);
        boolean copied = FileUtil.copy(updFile, to, true, false);
        if (copied) {
            updFile.delete();
            String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
            long modtime = 0;
            if (lastmod != null)
                modtime = RFC822Date.parse822Date(lastmod);
            if (modtime <= 0)
                modtime = _context.clock().now();
            _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATE_TIME, "" + modtime);
            if ("install".equals(policy)) {
                _log.log(Log.CRIT, "Update was downloaded, restarting to install it");
                updateStatus("<b>" + _("Update downloaded") + "</b><br>" + _("Restarting"));
                restart();
            } else {
                _log.log(Log.CRIT, "Update was downloaded, will be installed at next restart");
                StringBuilder buf = new StringBuilder(64);
                buf.append("<b>").append(_("Update downloaded")).append("</b><br>");
                if (_context.hasWrapper())
                    buf.append(_("Click Restart to install"));
                else
                    buf.append(_("Click Shutdown and restart to install"));
/// OK?
                    buf.append(' ').append(_("Version {0}", lastmod));
                updateStatus(buf.toString());
            }
        } else {
            _log.log(Log.CRIT, "Failed copy to " + to);
            updateStatus("<b>" + _("Failed copy to {0}", to.getAbsolutePath()) + "</b>");
        }
        return copied;
    }

    /**
     *  @return success
     */
    private boolean handlePluginFile(URI uri, String actualVersion, File sudFile) {
       //////////////// handled elsewhere?
        return false;
    }

    private void restart() {
        if (_context.hasWrapper())
            ConfigServiceHandler.registerWrapperNotifier(_context, Router.EXIT_GRACEFUL_RESTART, false);
        _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
    }

    static String linkify(String url) {
        return "<a target=\"_blank\" href=\"" + url + "\"/>" + url + "</a>";
    }

    /** translate a string */
    private String _(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     */
    private String _(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    private void updateStatus(String s) {
        _status = s;
    }

    private void finishStatus(String msg) {
        updateStatus(msg);
        _context.simpleScheduler().addEvent(new Cleaner(msg), 20*60*1000);
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        private final String _msg;
        public Cleaner(String msg) {
            _msg = msg;
        }
        public void timeReached() {
            if (_msg.equals(getStatus()))
                updateStatus("");
        }
    }

    /**
     *  Equals on updater, type and method only
     */
    private static class RegisteredUpdater implements Comparable<RegisteredUpdater> {
        public final Updater updater;
        public final UpdateType type;
        public final UpdateMethod method;
        public final int priority;

        public RegisteredUpdater(Updater u, UpdateType t, UpdateMethod m, int priority) {
            updater = u; type = t; method = m; this.priority = priority;
        }

        /** reverse, highest priority first, ensure different ones are different */
        public int compareTo(RegisteredUpdater r) {
            int p = r.priority - priority;
            if (p != 0)
                return p;
            return hashCode() - r.hashCode();
        }

        @Override
        public int hashCode() {
            return updater.hashCode() ^ type.hashCode() ^ method.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RegisteredUpdater))
                return false;
            RegisteredUpdater r = (RegisteredUpdater) o;
            return type == r.type && method == r.method &&
                   updater.equals(r.updater);
        }

        @Override
        public String toString() {
            return "RegisteredUpdater " + updater + " for " + type + ' ' + method + " @pri " + priority;
        }
    }

    /**
     *  Equals on checker, type and method only
     */
    private static class RegisteredChecker implements Comparable<RegisteredChecker> {
        public final Checker checker;
        public final UpdateType type;
        public final UpdateMethod method;
        public final int priority;

        public RegisteredChecker(Checker u, UpdateType t, UpdateMethod m, int priority) {
            checker = u; type = t; method = m; this.priority = priority;
        }

        /** reverse, highest priority first, ensure different ones are different */
        public int compareTo(RegisteredChecker r) {
            int p = r.priority - priority;
            if (p != 0)
                return p;
            return hashCode() - r.hashCode();
        }

        @Override
        public int hashCode() {
            return checker.hashCode() ^ type.hashCode() ^ method.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RegisteredChecker))
                return false;
            RegisteredChecker r = (RegisteredChecker) o;
            return type == r.type && method == r.method &&
                   checker.equals(r.checker);
        }

        @Override
        public String toString() {
            return "RegisteredChecker " + checker + " for " + type + ' ' + method + " @pri " + priority;
        }
    }

    /**
     *  Equals on type and ID only
     */
    private static class UpdateItem {
        public final UpdateType type;
        public final String id;

        public UpdateItem(UpdateType t, String id) {
            type = t;
            this.id = id;
        }

        @Override
        public int hashCode() {
            return type.hashCode() ^ id.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof UpdateItem))
                return false;
            UpdateItem r = (UpdateItem) o;
            return type == r.type && id.equals(r.id);
        }

        @Override
        public String toString() {
            return "UpdateItem " + type + ' ' + id;
        }
    }

    private static class Version implements Comparable<Version> {
        public final String version;

        public Version(String version) {
            this.version = version;
        }

        public int compareTo(Version r) {
            return _versionComparator.compare(version, r.version);
        }

        @Override
        public String toString() {
            return "Version " + version;
        }
    }

    private static class VersionAvailable extends Version {
        public final String minVersion;
        public final ConcurrentHashMap<UpdateMethod, List<URI>> sourceMap;

        /**
         * Puts the method and sources in the map. The map may be added to later.
         */
        public VersionAvailable(String version, String min, UpdateMethod method, List<URI> updateSources) {
            super(version);
            minVersion = min;
            sourceMap = new ConcurrentHashMap(4);
            sourceMap.put(method, updateSources);
        }

        @Override
        public String toString() {
            return "VersionAvailable " + version + ' ' + sourceMap;
        }
    }
}
