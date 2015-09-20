package net.i2p.router.web;

import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.router.networkdb.reseed.Reseeder;

/**
 *  @since 0.8.3
 */
public class ConfigReseedHandler extends FormHandler {
    private final Map<String, String> changes = new HashMap<String, String>();
    private final List<String> removes = new ArrayList<String>();
    
    @Override
    protected void processForm() {

        if (_action.equals(_("Save changes and reseed now"))) {
            saveChanges();
            if (!_context.netDb().reseedChecker().requestReseed()) {
                addFormError(_("Reseeding is already in progress"));
            } else {
                // skip the nonce checking in ReseedHandler
                addFormNotice(_("Starting reseed process"));
            }
        } else if (_action.equals(_("Reseed from URL"))) {
            String val = getJettyString("url");
            if (val != null)
                val = val.trim();
            if (val == null || val.length() == 0) {
                addFormError(_("You must enter a URL"));
                return;
            }
            URL url;
            try {
                url = new URL(val);
            } catch (MalformedURLException mue) {
                addFormError(_("Bad URL {0}", val));
                return;
            }
            try {
                if (!_context.netDb().reseedChecker().requestReseed(url)) {
                    addFormError(_("Reseeding is already in progress"));
                } else {
                    // wait a while for completion but not forever
                    for (int i = 0; i < 40; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {}
                        if (!_context.netDb().reseedChecker().inProgress())
                            break;
                    }
                    String status = _context.netDb().reseedChecker().getStatus();
                    String error = _context.netDb().reseedChecker().getError();
                    if (error.length() > 0) {
                        addFormErrorNoEscape(error);
                    } else if (status.length() > 0) {
                        addFormNoticeNoEscape(status);
                    } else if (_context.netDb().reseedChecker().inProgress()) {
                        addFormNotice(_("Reseed in progress, check summary bar for status"));
                    } else {
                        addFormNotice(_("Reseed complete, check summary bar for status"));
                    }
                }
            } catch (IllegalArgumentException iae) {
                addFormError(_("Bad URL {0}", val) + " - " + iae.getMessage());
            }
        } else if (_action.equals(_("Reseed from file"))) {
            InputStream in = _requestWrapper.getInputStream("file");
            try {
                // non-null but zero bytes if no file entered, don't know why
                if (in == null || in.available() <= 0) {
                    addFormError(_("You must enter a file"));
                    return;
                }
                int count = _context.netDb().reseedChecker().requestReseed(in);
                if (count <= 0) {
                    addFormError(_("Reseed from file failed"));
                } else {
                    addFormNotice(ngettext("Reseed successful, loaded {0} router info from file",
                                           "Reseed successful, loaded {0} router infos from file",
                                           count));
                }
            } catch (IOException ioe) {
                addFormError(_("Reseed from file failed") + " - " + ioe);
            } finally {
                // it's really a ByteArrayInputStream but we'll play along...
                if (in != null)
                    try { in.close(); } catch (IOException ioe) {}
            }
        } else if (_action.equals(_("Save changes"))) {
            saveChanges();
        } else if (_action.equals(_("Reset URL list"))) {
            resetUrlList();
        }
        //addFormError(_("Unsupported") + ' ' + _action + '.');
    }

    private void resetUrlList() {
        if (_context.router().saveConfig(Reseeder.PROP_RESEED_URL, null))
	    addFormNotice(_("URL list reset successfully"));
        else
            addFormError(_("URL list reset failed"));
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
        saveBoolean(Reseeder.PROP_SPROXY_ENABLE, "senable");
        if (_context.router().saveConfig(changes, removes))
            addFormNotice(_("Configuration saved successfully."));
        else
            addFormError(_("Error saving the configuration (applied but not saved) - please see the error logs"));
    }

    /** translate (ngettext) @since 0.9.19 */
    public String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }
}
