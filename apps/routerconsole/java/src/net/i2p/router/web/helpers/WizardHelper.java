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
    // scale bw test results by this for limiter settings
    public static final float BW_SCALE = 0.75f;
    // pages
    public static final int PAGE_LANG = 1;
    public static final int PAGE_THEME = 2;
    public static final int PAGE_CHECK = 3;
    public static final int PAGE_TEST = 4;
    public static final int PAGE_RESULTS = 5;
    public static final int PAGE_BROWSER = 6;
    public static final int PAGE_DONE = 7;

    // KBps
    private static final float MIN_DOWN_BW = 32.0f;
    private static final float MIN_UP_BW = 12.0f;

    // session scope, but it's an underlying singleton
    private MLabRunner _mlab;

    // session scope
    private TestListener _listener;
    private MLabRunner.ToolRun _runner;
    private String _lastTestStatus;
    private int _lastTestCount;

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

    public synchronized boolean isNDTSuccessful() {
        return isNDTComplete() && getUpBandwidth() > 0 && getDownBandwidth() > 0;
    }

    /**
     * @return HTML-escaped status string or ""
     */
    public synchronized String getTestStatus() {
        String rv = "";
        if (_runner != null) {
            // NDT-translated string
            // NDT has 200+ translated strings but only has 7 translations
            // and we haven't put them up on Transifex.
            // There's only a few commonly seen strings
            // via showStatus(), so if they come through untranslated,
            // try to translate those here in our bundle
            String s = _runner.getStatus();
            if (s != null) {
                if (s.equals("ready"))
                    s = _t("ready");
                else if (s.equals("inbound test..."))
                    s = _t("inbound test") + "...";
                else if (s.equals("outbound test..."))
                    s = _t("outbound test") + "...";
                else if (s.equals("done"))
                    s = _t("done");
                rv = DataHelper.escapeHTML(s);
                if (rv.equals(_lastTestStatus)) {
                    _lastTestCount++;
                    int mod = _lastTestCount & 0x03;
                    if (mod == 1)
                        rv += ".";
                    else if (mod == 2)
                        rv += "..";
                    else if (mod == 3)
                        rv += "...";
                } else {
                    _lastTestCount = 0;
                    _lastTestStatus = rv;
                }
            }
        }
        return rv;
    }

    /**
     * @return HTML-escaped status string or ""
     */
    public synchronized String getCompletionStatus() {
        String rv = "";
        if (_listener != null) {
            String s = _listener.getSummary();
            if (s != null)
                rv = DataHelper.escapeHTML(s);
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
                rv = DataHelper.escapeHTML(s);
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

    /**
     * @return HTML-escaped location or ""
     */
    public String getServerLocation() {
        StringBuilder buf = new StringBuilder(64);
        String s = getStringResult("server_city");
        if (s != null)
            buf.append(s).append(' ');
        s = getStringResult("server_country");
        if (s != null)
            buf.append(s).append(' ');
        s = getStringResult("server_host");
        if (s != null)
            buf.append(s);
        return DataHelper.escapeHTML(buf.toString());
    }

    private synchronized long getLongResult(String key) {
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

    private synchronized String getStringResult(String key) {
        if (_listener != null) {
            Map<String, Object> results = _listener.getResults();
            if (results != null) {
                return (String) results.get(key);
            }
        }
        return null;
    }

    /**
     * To populate form with.
     * Uses the test result if available, else the current setting
     * Adapted from ConfigNetHelper.
     * @return decimal KBytes/sec
     */
    public String getInboundBurstRate() {
        float bw;
        long result = getDownBandwidth();
        if (result > 0) {
            bw = Math.max(MIN_DOWN_BW, BW_SCALE * result / 1000f);
        } else {
            bw = _context.bandwidthLimiter().getInboundBurstKBytesPerSecond() * 1.024f;
        }
        return Integer.toString(Math.round(bw));
    }

    /**
     * To populate form with.
     * Uses the test result if available, else the current setting
     * Adapted from ConfigNetHelper.
     * @return decimal KBytes/sec
     */
    public String getOutboundBurstRate() {
        float bw;
        long result = getUpBandwidth();
        if (result > 0) {
            bw = Math.max(MIN_UP_BW, BW_SCALE * result / 1000f);
        } else {
            bw = _context.bandwidthLimiter().getOutboundBurstKBytesPerSecond() * 1.024f;
        }
        return Integer.toString(Math.round(bw));
    }

    /**
     * Copied from ConfigNetHelper.
     * @return decimal
     */
    public String getInboundBurstRateBits() {
        return kbytesToBits(_context.bandwidthLimiter().getInboundBurstKBytesPerSecond());
    }

    /**
     * Copied from ConfigNetHelper.
     * @return decimal
     */
    public String getOutboundBurstRateBits() {
        return kbytesToBits(_context.bandwidthLimiter().getOutboundBurstKBytesPerSecond());
    }

    /**
     * Copied from ConfigNetHelper.
     * @return decimal
     */
    public String getShareRateBits() {
        return kbytesToBits(getShareBandwidth());
    }

    /**
     * Copied from ConfigNetHelper.
     * @param kbytes binary K
     * @return decimal
     */
    private String kbytesToBits(float kbytes) {
        return DataHelper.formatSize2Decimal((long) (kbytes * (8 * 1024))) + _t("bits per second") +
               "; " +
               _t("{0}Bytes per month maximum", DataHelper.formatSize2Decimal((long) (kbytes * (1024L * 60 * 60 * 24 * 31))));
    }

    /**
     *  Adapted from ConfigNetHelper.
     *  @return in binary KBytes per second
     */
    public int getShareBandwidth() {
        float irateKBps;
        float orateKBps;
        long result = getDownBandwidth();
        if (result > 0) {
            irateKBps = Math.max(MIN_DOWN_BW, BW_SCALE * result / 1024f);
        } else {
            irateKBps = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        }
        result = getUpBandwidth();
        if (result > 0) {
            orateKBps = Math.max(MIN_UP_BW, BW_SCALE * result / 1024f);
        } else {
            orateKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        }
        if (irateKBps < 0 || orateKBps < 0)
            return ConfigNetHelper.DEFAULT_SHARE_KBPS;
        double pct = _context.router().getSharePercentage();
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }

    /**
     *  Start the test. Called from the Handler.
     *  @return success
     */
    synchronized boolean startNDT() {
        if (_mlab.isRunning() || _listener != null && !_listener.isComplete()) {
            return false;
        }
        TestListener lsnr = new TestListener();
        _runner = _mlab.runNDT(lsnr);
        boolean rv = _runner != null;
        if (!rv) {
            Map<String, Object> map = new HashMap<String, Object>(2);
            lsnr.complete(map);
        }
        // replace the old listener
        _listener = lsnr;
        return rv;
    }

    /**
     *  Cancel the test. Called from the Handler.
     *  @return success
     */
    synchronized boolean cancelNDT() {
        if (!_mlab.isRunning()) {
            return false;
        }
        boolean rv = _runner != null;
        if (rv) {
            _runner.cancel();
            _runner = null;
        }
        return rv;
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
