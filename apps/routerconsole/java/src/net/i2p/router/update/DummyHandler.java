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
    
    public DummyHandler(RouterContext ctx) {
        _context = ctx;
    }

    /**
     *  Spins off an UpdateTask that sleeps
     */
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime) {
        if (type != UpdateType.TYPE_DUMMY)
            return null;
         return new DummyRunner(_context, maxTime);
    }

    /**
     *  Spins off an UpdateTask that sleeps
     */
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                             String id, String newVersion, long maxTime) {
        if (type != UpdateType.TYPE_DUMMY)
            return null;
         return new DummyRunner(_context, maxTime);
    }

    /**
     *  Use for both check and update
     */
    private static class DummyRunner extends UpdateRunner {
        private final long _delay;

        public DummyRunner(RouterContext ctx, long maxTime) {
            super(ctx, Collections.EMPTY_LIST);
            _delay = maxTime;
        }

        @Override
        public UpdateType getType() { return UpdateType.TYPE_DUMMY; }

        @Override
        protected void update() {
            try {
                Thread.sleep(_delay);
            } catch (InterruptedException ie) {}
            UpdateManager mgr = _context.updateManager();
            if (mgr != null) {
                mgr.notifyCheckComplete(this, false, false);
                mgr.notifyTaskFailed(this, "dummy", null);
            }
        }
    }
}
