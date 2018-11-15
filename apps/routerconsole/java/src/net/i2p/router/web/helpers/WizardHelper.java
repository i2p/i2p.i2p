package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.Map;

import com.vuze.plugins.mlab.MLabRunner;

import net.i2p.data.DataHelper;
import net.i2p.router.web.HelperBase;

/**
 *  The new user wizard.
 *
 *  This bean has SESSION scope so the results may be retrieved.
 *  All necessary methods are synchronized.
 *
 *  @since 0.9.38
 */
public class WizardHelper extends HelperBase {

    public static final String PROP_COMPLETE = "routerconsole.welcomeWizardComplete";

    // session scope, but it's an underlying singleton
    private MLabRunner _mlab;

    // session scope
    private TestListener _listener;
    private MLabRunner.ToolRun _runner;

    /**
     * Overriden to only do this once.
     */
    @Override
    public void setContextId(String contextId) {
        if (_context == null) {
            super.setContextId(contextId);
            _mlab = MLabRunner.getInstance(_context);
        }
    }

    public void complete() {
        _context.router().saveConfig(PROP_COMPLETE, "true");
    }

    public synchronized boolean isNDTComplete() {
        return _listener != null && _listener.isComplete();
    }

    public synchronized boolean isNDTRunning() {
        return _listener != null && !_listener.isComplete();
    }

    /**
     * @return HTML-escaped status string or ""
     */
    public synchronized String getCompletionStatus() {
        String rv = "";
        if (_listener != null) {
            String s = _listener.getSummary();
            if (s != null)
                s = DataHelper.escapeHTML(s);
        }
        return rv;
    }

    /**
     * @return HTML-escaped status string or ""
     */
    public synchronized String getDetailStatus() {
        String rv = "";
        if (_listener != null) {
            String s = _listener.getDetail();
            if (s != null)
                s = DataHelper.escapeHTML(s);
        }
        return rv;
    }

    /**
     * @return bytes per second or 0
     */
    public long getUpBandwidth() {
        return getLongResult("up");
    }

    /**
     * @return bytes per second or 0
     */
    public long getDownBandwidth() {
        return getLongResult("down");
    }

    public synchronized long getLongResult(String key) {
        if (_listener != null) {
            Map<String, Object> results = _listener.getResults();
            if (results != null) {
                Long v = (Long) results.get(key);
                if (v != null)
                    return v.longValue();
            }
        }
        return 0;
    }

    /**
     *  Start the test. Called from the Handler.
     *  @return success
     */
    synchronized boolean startNDT() {
        if (_mlab.isRunning() || _listener != null && !_listener.isComplete()) {
            return false;
        }
        _listener = new TestListener();
        _runner = _mlab.runNDT(_listener);
        if (_runner != null) {
            return true;
        } else {
            Map<String, Object> map = new HashMap<String, Object>(2);
            _listener.complete(map);
            return false;
        }
    }

    /**
     *  Cancel the test. Called from the Handler.
     *  @return success
     */
    synchronized boolean cancelNDT() {
        if (!_mlab.isRunning()) {
            return false;
        }
        if (_runner != null) {
            _runner.cancel();
            _runner = null;
            return true;
        } else {
            return false;
        }
    }

    /** test results */
    private static class TestListener implements MLabRunner.ToolListener {
        private String _summary, _detail;
        private Map<String, Object> _results;

        public synchronized void reportSummary(String str) {
            _summary = str;
        }

        public synchronized void reportDetail(String str) {
            _detail = str;
        }

        public synchronized void complete(Map<String, Object> results) {
            _results = results;
        }

        public synchronized boolean isComplete() {
            return _results != null;
        }

        public synchronized String getSummary() {
            return _summary;
        }

        public synchronized String getDetail() {
            return _detail;
        }

        public synchronized Map<String, Object> getResults() {
            return _results;
        }
    }
}
