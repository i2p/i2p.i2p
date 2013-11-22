package net.i2p.router.update;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.update.*;

/**
 * Dummy to lock up the updates for a period of time
 *
 * @since 0.9.4
 */
class DummyHandler implements Checker, Updater {
    private final RouterContext _context;
    private final ConsoleUpdateManager _mgr;
    
    public DummyHandler(RouterContext ctx, ConsoleUpdateManager mgr) {
        _context = ctx;
        _mgr = mgr;
    }

    /**
     *  Spins off an UpdateTask that sleeps
     */
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime) {
        if (type != UpdateType.TYPE_DUMMY)
            return null;
         return new DummyRunner(_context, _mgr, maxTime);
    }

    /**
     *  Spins off an UpdateTask that sleeps
     */
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                             String id, String newVersion, long maxTime) {
        if (type != UpdateType.TYPE_DUMMY)
            return null;
         return new DummyRunner(_context, _mgr, maxTime);
    }

    /**
     *  Use for both check and update
     */
    private static class DummyRunner extends UpdateRunner {
        private final long _delay;

        public DummyRunner(RouterContext ctx, ConsoleUpdateManager mgr, long maxTime) {
            super(ctx, mgr, UpdateType.TYPE_DUMMY, Collections.<URI> emptyList());
            _delay = maxTime;
        }

        @Override
        public UpdateMethod getMethod() { return UpdateMethod.METHOD_DUMMY; }

        @Override
        protected void update() {
            try {
                Thread.sleep(_delay);
            } catch (InterruptedException ie) {}
            _mgr.notifyCheckComplete(this, false, false);
            _mgr.notifyTaskFailed(this, "dummy", null);
        }
    }
}
