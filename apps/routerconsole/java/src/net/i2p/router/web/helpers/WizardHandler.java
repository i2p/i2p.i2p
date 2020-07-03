package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.Map;

import net.i2p.router.Router;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.web.FormHandler;

/**
 *  The new user wizard.
 *
 *  This handler has request scope (as all FormHandlers must), so it controls
 *  the test via the helper, which has session scope.
 *
 *  @since 0.9.38
 */
public class WizardHandler extends FormHandler {

    private WizardHelper _helper;
    
    /**
     *  Bind the helper (scope session) to this handler (scope request)
     */
    public void setWizardHelper(WizardHelper helper) {
        _helper = helper;
    }

    @Override
    protected void processForm() {
        if (getJettyString("cancelbw") != null) {
            cancelNDT();
            for (int i = 0; i < 20 && !_helper.isNDTComplete(); i++) {
                try { Thread.sleep(100); } catch (InterruptedException ie) {}
            }
            return;
        }
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
            if ("3".equals(page)) {
                startNDT();
            } else if ("4".equals(page)) {
                synchronized (_helper) {
                    if (_helper.isNDTSuccessful()) {
                        addFormNotice(_t("Bandwidth test completed successfully"));
                    } else if (_helper.isNDTComplete()) {
                        addFormError(_t("Bandwidth test failed"));
                    } else if (_helper.isNDTRunning()) {
                        addFormError(_t("Bandwidth test did not complete"));
                    } else {
                        // didn't run at all?
                        addFormError(_t("Bandwidth test did not complete"));
                    }
                }
            } else if ("5".equals(page)) {
                Map<String, String> changes = new HashMap<String, String>();
                boolean updated = updateRates(changes);
                if (updated) {
                    boolean saved = _context.router().saveConfig(changes, null);
                    // this has to be after the save
                    _context.bandwidthLimiter().reinitialize();
                    if (saved)
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

    /** start the test */
    private void startNDT() {
        if (_helper == null) {
            addFormError("Bad state for test");
        } else if (_helper.startNDT()) {
            addFormNotice(_t("Bandwidth test started"));
        } else {
            addFormError(_t("Bandwidth test is already running"));
        }
    }

    /** cancel the test */
    private void cancelNDT() {
        if (_helper == null) {
            addFormError("Bad state for test");
        } else if (_helper.cancelNDT()) {
            addFormError(_t("Bandwidth test cancelled"));
        } else {
            addFormError(_t("Bandwidth test was not running"));
        }
    }
}
