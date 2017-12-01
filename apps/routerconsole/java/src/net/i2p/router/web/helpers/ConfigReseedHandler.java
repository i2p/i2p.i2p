package net.i2p.router.web.helpers;

import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.router.networkdb.reseed.ReseedChecker;
import net.i2p.router.networkdb.reseed.Reseeder;
import net.i2p.router.web.FormHandler;
import net.i2p.router.web.Messages;

/**
 *  @since 0.8.3
 */
public class ConfigReseedHandler extends FormHandler {
    private final Map<String, String> changes = new HashMap<String, String>();
    private final List<String> removes = new ArrayList<String>();
    
    @Override
    protected void processForm() {

        ReseedChecker checker = _context.netDb().reseedChecker();
        if (_action.equals(_t("Save changes and reseed now"))) {
            saveChanges();
            if (!checker.requestReseed()) {
                addFormError(_t("Reseeding is already in progress"));
                addCheckerStatus(checker);
            } else {
                // skip the nonce checking in ReseedHandler
                addFormNotice(_t("Starting reseed process"));
            }
        } else if (_action.equals(_t("Reseed from URL"))) {
            // threaded
            String val = getJettyString("url");
            if (val != null)
                val = val.trim();
            if (val == null || val.length() == 0) {
                addFormError(_t("You must enter a URL"));
                return;
            }
            URI url;
            try {
                url = new URI(val);
            } catch (URISyntaxException mue) {
                addFormError(_t("Bad URL {0}", val));
                return;
            }
            try {
                if (!checker.requestReseed(url)) {
                    addFormError(_t("Reseeding is already in progress"));
                    addCheckerStatus(checker);
                } else {
                    // wait a while for completion but not forever
                    for (int i = 0; i < 40; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {}
                        if (!checker.inProgress())
                            break;
                    }
                    if (!addCheckerStatus(checker)) {
                        if (checker.inProgress()) {
                            addFormNotice(_t("Reseed in progress, check summary bar for status"));
                        } else {
                            addFormNotice(_t("Reseed complete, check summary bar for status"));
                        }
                    }
                }
            } catch (IllegalArgumentException iae) {
                addFormError(_t("Bad URL {0}", val) + " - " + iae.getMessage());
            }
        } else if (_action.equals(_t("Reseed from file"))) {
            // inline
            InputStream in = _requestWrapper.getInputStream("file");
            try {
                // non-null but zero bytes if no file entered, don't know why
                if (in == null || in.available() <= 0) {
                    addFormError(_t("You must enter a file"));
                    return;
                }
                int count = checker.requestReseed(in);
                if (count <= 0) {
                    addFormError(_t("Reseed from file failed"));
                    addCheckerStatus(checker);
                } else {
                    addFormNotice(ngettext("Reseed successful, loaded {0} router info from file",
                                           "Reseed successful, loaded {0} router infos from file",
                                           count));
                }
            } catch (IOException ioe) {
                addFormError(_t("Reseed from file failed") + " - " + ioe);
                addCheckerStatus(checker);
            } finally {
                // it's really a ByteArrayInputStream but we'll play along...
                if (in != null)
                    try { in.close(); } catch (IOException ioe) {}
            }
        } else if (_action.equals(_t("Save changes"))) {
            saveChanges();
        } else if (_action.equals(_t("Reset URL list"))) {
            resetUrlList();
        }
        //addFormError(_t("Unsupported") + ' ' + _action + '.');
    }

    /**
     *  @return true if anything was output
     *  @since 0.9.33
     */
    private boolean addCheckerStatus(ReseedChecker checker) {
        String error = checker.getError();
        if (error.length() > 0) {
            addFormErrorNoEscape(error);
            return true;
        }
        String status = checker.getStatus();
        if (status.length() > 0) {
            addFormNoticeNoEscape(status);
            return true;
        }
        return false;
    }

    private void resetUrlList() {
        if (_context.router().saveConfig(Reseeder.PROP_RESEED_URL, null))
	    addFormNotice(_t("URL list reset successfully"));
        else
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
    }

    /** @since 0.8.9 */
    private void saveString(String config, String param) {
        String val = getJettyString(param);
        if (val != null && val.length() > 0)
            changes.put(config, val);
        else
            removes.add(config);
    }

    /** @since 0.8.9 */
    private void saveBoolean(String config, String param) {
        boolean val = getJettyString(param) != null;
        changes.put(config, Boolean.toString(val));
    }

    private void saveChanges() {
        saveString(Reseeder.PROP_PROXY_PORT, "port");
        saveString(Reseeder.PROP_PROXY_HOST, "host");
        saveString(Reseeder.PROP_PROXY_USERNAME, "username");
        saveString(Reseeder.PROP_PROXY_PASSWORD, "password");
        saveBoolean(Reseeder.PROP_PROXY_AUTH_ENABLE, "auth");
        saveString(Reseeder.PROP_SPROXY_PORT, "sport");
        saveString(Reseeder.PROP_SPROXY_HOST, "shost");
        saveString(Reseeder.PROP_SPROXY_USERNAME, "susername");
        saveString(Reseeder.PROP_SPROXY_PASSWORD, "spassword");
        saveBoolean(Reseeder.PROP_SPROXY_AUTH_ENABLE, "sauth");
        String url = getJettyString("reseedURL");
        if (url != null) {
            url = url.trim().replace("\r\n", ",").replace("\n", ",");
            if (url.length() <= 0) {
                addFormNotice("Restoring default URLs");
                removes.add(Reseeder.PROP_RESEED_URL);
            } else {
                changes.put(Reseeder.PROP_RESEED_URL, url);
            }
        }
        String mode = getJettyString("mode");
        boolean req = "1".equals(mode);
        boolean disabled = "2".equals(mode);
        changes.put(Reseeder.PROP_SSL_REQUIRED,
                                           Boolean.toString(req));
        changes.put(Reseeder.PROP_SSL_DISABLE,
                                           Boolean.toString(disabled));
        saveBoolean(Reseeder.PROP_PROXY_ENABLE, "enable");
        String pmode = getJettyString("pmode");
        boolean senable = pmode != null && pmode.length() > 0;
        changes.put(Reseeder.PROP_SPROXY_ENABLE, Boolean.toString(senable));
        saveString(Reseeder.PROP_SPROXY_TYPE, "pmode");
        if (_context.router().saveConfig(changes, removes))
            addFormNotice(_t("Configuration saved successfully."));
        else
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
    }

    /** translate (ngettext) @since 0.9.19 */
    public String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }
}
