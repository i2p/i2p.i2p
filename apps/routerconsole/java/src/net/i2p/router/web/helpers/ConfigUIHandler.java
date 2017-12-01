package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataHelper;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.ConsolePasswordManager;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.router.web.FormHandler;


/** set the theme */
public class ConfigUIHandler extends FormHandler {
    private boolean _shouldSave;
    private boolean _universalTheming;
    private boolean _forceMobileConsole;
    private boolean _embedApps;
    private String _config;

    @Override
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else if (_action.equals(_t("Delete selected"))) {
            delUser();
        } else if (_action.equals(_t("Add user"))) {
            addUser();
        }
    }

    public void setShouldsave(String moo) { _shouldSave = true; }

    public void setUniversalTheming(String baa) { _universalTheming = true; }

    public void setForceMobileConsole(String baa) { _forceMobileConsole = true; }

    public void setEmbedApps(String baa) { _embedApps = true; }

    public void setTheme(String val) {
        _config = val;
    }

    /** note - lang change is handled in CSSHelper but we still need to save it here */
    private void saveChanges() {
        if (_config == null || _config.length() <= 0)
            return;
        if (_config.replaceAll("[a-zA-Z0-9_-]", "").length() != 0) {
            addFormError("Bad theme name");
            return;
        }
        Map<String, String> changes = new HashMap<String, String>();
        List<String> removes = new ArrayList<String>();
        String oldTheme = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        boolean oldForceMobileConsole = _context.getBooleanProperty(CSSHelper.PROP_FORCE_MOBILE_CONSOLE);
        if (_config.equals("default")) // obsolete
            removes.add(CSSHelper.PROP_THEME_NAME);
        else
            changes.put(CSSHelper.PROP_THEME_NAME, _config);
        if (_universalTheming)
            changes.put(CSSHelper.PROP_UNIVERSAL_THEMING, "true");
        else
            removes.add(CSSHelper.PROP_UNIVERSAL_THEMING);
        if (_forceMobileConsole)
            changes.put(CSSHelper.PROP_FORCE_MOBILE_CONSOLE, "true");
        else
            removes.add(CSSHelper.PROP_FORCE_MOBILE_CONSOLE);
        if (_embedApps)
            changes.put(CSSHelper.PROP_EMBED_APPS, "true");
        else
            removes.add(CSSHelper.PROP_EMBED_APPS);
        boolean ok = _context.router().saveConfig(changes, removes);
        if (ok) {
            if (!oldTheme.equals(_config))
                addFormNoticeNoEscape(_t("Theme change saved.") +
                              " <a href=\"configui\">" +
                              _t("Refresh the page to view.") +
                              "</a>");
            if (oldForceMobileConsole != _forceMobileConsole)
                addFormNoticeNoEscape(_t("Mobile console option saved.") +
                              " <a href=\"configui\">" +
                              _t("Refresh the page to view.") +
                              "</a>");
        } else {
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs."));
        }
    }

    private void addUser() {
        String name = getJettyString("name");
        if (name == null || name.length() <= 0) {
            addFormError(_t("No user name entered"));
            return;
        }
        // XSS filters # and ; but not =
        // We store the username as the part of an option key, so we can't handle '='
        if (name.contains("=")) {
            addFormError("User name may not contain '='");
            return;
        }
        byte[] b1 = DataHelper.getUTF8(name);
        byte[] b2 = DataHelper.getASCII(name);
        if (!DataHelper.eq(b1, b2))
            addFormError(_t("Warning: User names outside the ISO-8859-1 character set are not recommended. Support is not standardized and varies by browser."));
        String pw = getJettyString("nofilter_pw");
        if (pw == null || pw.length() <= 0) {
            addFormError(_t("No password entered"));
            return;
        }
        ConsolePasswordManager mgr = new ConsolePasswordManager(_context);
        // rfc 2617
        if (mgr.saveMD5(RouterConsoleRunner.PROP_CONSOLE_PW, RouterConsoleRunner.JETTY_REALM, name, pw)) {
            if (!_context.getBooleanProperty(RouterConsoleRunner.PROP_PW_ENABLE))
                _context.router().saveConfig(RouterConsoleRunner.PROP_PW_ENABLE, "true");
            addFormNotice(_t("Added user {0}", name));
            addFormNotice(_t("To recover from a forgotten or non-working password, stop I2P, edit the file {0}, delete the line {1}, and restart I2P.",
                             _context.router().getConfigFilename(), RouterConsoleRunner.PROP_PW_ENABLE + "=true"));
            addFormError(_t("Restart required to take effect"));
        } else {
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs."));
        }
    }

    private void delUser() {
        ConsolePasswordManager mgr = new ConsolePasswordManager(_context);
        boolean success = false;
        for (Object o : _settings.keySet()) {
            if (!(o instanceof String))
                continue;
            String k = (String) o;
            if (!k.startsWith("delete_"))
                continue;
            k = k.substring(7);
            if (mgr.remove(RouterConsoleRunner.PROP_CONSOLE_PW, k)) {
                addFormNotice(_t("Removed user {0}", k));
                success = true;
            } else {
                addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs."));
            }
        }
        if (success)
            addFormError(_t("Restart required to take effect"));
    }
}
