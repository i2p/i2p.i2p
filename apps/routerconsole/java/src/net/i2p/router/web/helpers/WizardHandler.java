package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.vuze.plugins.mlab.MLabRunner;

import net.i2p.router.Router;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.web.FormHandler;

/**
 *  The new user wizard.
 *  This bean has SESSION scope so the results may be retrieved.
 *  All necessary methods are synchronized.
 *
 *  @since 0.9.38
 */
public class WizardHandler extends FormHandler {

    // session scope, but it's an underlying singleton
    private MLabRunner _mlab;
    // session scope
    private TestListener _listener;

    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        _mlab = MLabRunner.getInstance(_context);
    }
    
    @Override
    protected void processForm() {
        if (_action == null)
            return;
        if (getJettyString("next") == null)
            return;
        if (_action.equals("blah")) {
            // note that the page is the page we are on now,
            // which is the page after the one the settings were on.
            String page = getJettyString("page");
            if (getJettyString("lang") != null) {
                // Saved in CSSHelper, assume success
                addFormNoticeNoEscape(_t("Console language saved."));
            }
            if ("6".equals(page)) {
                Map<String, String> changes = new HashMap<String, String>();
                boolean updated = updateRates(changes);
                if (updated) {
                    boolean saved = _context.router().saveConfig(changes, null);
                    if (saved)   // needed?
                        addFormNotice(_t("Configuration saved successfully"));
                    else
                        addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
                }
            }
        } else {
            addFormError(_t("Unsupported") + ": " + _action);
        }
    }

    /**
     *  Modified from ConfigNetHandler
     *  @return changed
     */
    private boolean updateRates(Map<String, String> changes) {
        boolean updated = false;
        boolean bwUpdated = false;
        String sharePct = getJettyString("sharePercentage");
        String inboundRate = getJettyString("inboundrate");
        String outboundRate = getJettyString("outboundrate");

        if (sharePct != null) {
            String old = _context.router().getConfigSetting(Router.PROP_BANDWIDTH_SHARE_PERCENTAGE);
            if ( (old == null) || (!old.equals(sharePct)) ) {
                changes.put(Router.PROP_BANDWIDTH_SHARE_PERCENTAGE, sharePct);
                addFormNotice(_t("Updating bandwidth share percentage"));
                updated = true;
            }
        }
        if ((inboundRate != null) && (inboundRate.length() > 0) &&
            !inboundRate.equals(_context.getProperty(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH,
                                                     Integer.toString(FIFOBandwidthRefiller.DEFAULT_INBOUND_BURST_BANDWIDTH)))) {
            try {
                float rate = Integer.parseInt(inboundRate) / 1.024f;
                float kb = ConfigNetHandler.DEF_BURST_TIME * rate;
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH, Integer.toString(Math.round(rate)));
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH_PEAK, Integer.toString(Math.round(kb)));
                rate -= Math.min(rate * ConfigNetHandler.DEF_BURST_PCT / 100, 50);
                changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, Integer.toString(Math.round(rate)));
	        bwUpdated = true;
            } catch (NumberFormatException nfe) {
                addFormError(_t("Invalid bandwidth"));
            }
        }
        if ((outboundRate != null) && (outboundRate.length() > 0) &&
            !outboundRate.equals(_context.getProperty(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH,
                                                      Integer.toString(FIFOBandwidthRefiller.DEFAULT_OUTBOUND_BURST_BANDWIDTH)))) {
            try {
                float rate = Integer.parseInt(outboundRate) / 1.024f;
                float kb = ConfigNetHandler.DEF_BURST_TIME * rate;
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH, Integer.toString(Math.round(rate)));
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH_PEAK, Integer.toString(Math.round(kb)));
                rate -= Math.min(rate * ConfigNetHandler.DEF_BURST_PCT / 100, 50);
                changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, Integer.toString(Math.round(rate)));
	        bwUpdated = true;
            } catch (NumberFormatException nfe) {
                addFormError(_t("Invalid bandwidth"));
            }
        }
        if (bwUpdated) {
            addFormNotice(_t("Updated bandwidth limits"));
            updated = true;
        }
        return updated; 
    }

    public synchronized boolean isNDTComplete() {
        return _listener != null && _listener.isComplete();
    }

    public synchronized boolean isNDTRunning() {
        return _listener != null && !_listener.isComplete();
    }

    /**
     * @return status string or null
     */
    public synchronized String getCompletionStatus() {
        return _listener != null ? _listener.getSummary() : null;
    }

    /**
     * @return status string or null
     */
    public synchronized String getDetailStatus() {
        return _listener != null ? _listener.getDetail() : null;
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

    /** start the test */
    public synchronized void startNDT() {
        if (_mlab.isRunning() || _listener != null && !_listener.isComplete()) {
            addFormError(_t("Bandwidth test is already running"));
            return;
        }
        _listener = new TestListener();
        MLabRunner.ToolRun runner = _mlab.runNDT(_listener);
        if (runner != null) {
            addFormNotice(_t("Started bandwidth test"));
        } else {
            Map<String, Object> map = new HashMap<String, Object>(2);
            _listener.complete(map);
            addFormError(_t("Bandwidth test is already running"));
        }
    }

    /** cancel the test */
    public synchronized void cancelNDT() {
        synchronized(WizardHandler.class) {
            if (!_mlab.isRunning()) {
                addFormError(_t("Bandwidth test was not running"));
                return;
            }
/****
TODO
            if (runner != null)
                addFormNotice(_t("Started bandwidth test"));
            else
                addFormError(_t("Bandwidth test is already running"));
****/
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
