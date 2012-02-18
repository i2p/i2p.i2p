package net.i2p.router.web;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Map;

import net.i2p.data.DataHelper;

/**
 *  Simple home page configuration.
 *
 *  @since 0.9
 */
public class ConfigHomeHandler extends FormHandler {

    private Map _settings;
    
    @Override
    protected void processForm() {
        if (_action == null) return;
        String group = getJettyString("group");
        boolean deleting = _action.equals(_("Delete selected"));
        boolean adding = _action.equals(_("Add item"));
        boolean restoring = _action.equals(_("Restore defaults"));
        if (_action.equals(_("Save")) && "0".equals(group)) {
            boolean old = _context.getBooleanProperty(HomeHelper.PROP_OLDHOME);
            boolean nnew = getJettyString("oldHome") != null;
            if (old != nnew) {
                _context.router().saveConfig(HomeHelper.PROP_OLDHOME, "" + nnew);
                addFormNotice(_("Home page changed"));
            }
        } else if (adding || deleting || restoring) {
            String prop;
            String dflt;
            if ("1".equals(group)) {
                prop = HomeHelper.PROP_FAVORITES;
                dflt = HomeHelper.DEFAULT_FAVORITES;
            } else if ("2".equals(group)) {
                prop = HomeHelper.PROP_SERVICES;
                dflt = HomeHelper.DEFAULT_SERVICES;
            } else if ("3".equals(group)) {
                prop = SearchHelper.PROP_ENGINES;
                dflt = SearchHelper.ENGINES_DEFAULT;
            } else {
                addFormError("Bad group");
                return;
            }
            if (restoring) {
                _context.router().saveConfig(prop, dflt);
                addFormNotice(_("Restored default settings"));
                return;
            }
            String config = _context.getProperty(prop, dflt);
            Collection<HomeHelper.App> apps;
            if ("3".equals(group))
                apps = HomeHelper.buildSearchApps(config);
            else
                apps = HomeHelper.buildApps(_context, config);
            if (adding) {
                String name = getJettyString("name");
                if (name == null || name.length() <= 0) {
                    addFormError(_("No name entered"));
                    return;
                }
                String url = getJettyString("url");
                if (url == null || url.length() <= 0) {
                    addFormError(_("No URL entered"));
                    return;
                }
                name = DataHelper.escapeHTML(name).replace(",", "&#44;");   // HomeHelper.S
                url = DataHelper.escapeHTML(url).replace(",", "&#44;");
                HomeHelper.App app = new HomeHelper.App(name, "", url, "/themes/console/images/itoopie_sm.png");
                apps.add(app);
                addFormNotice(_("Added") + ": " + app.name);
            } else {
                // deleting
                Set<String> toDelete = new HashSet();
                for (Object o : _settings.keySet()) {
                     if (!(o instanceof String))
                         continue;
                     String k = (String) o;
                     if (!k.startsWith("delete_"))
                         continue;
                     k = k.substring(7);
                     toDelete.add(k);
                }
                for (Iterator<HomeHelper.App> iter = apps.iterator(); iter.hasNext(); ) {
                    HomeHelper.App app = iter.next();
                    if (toDelete.contains(app.name)) {
                        iter.remove();
                        addFormNotice(_("Removed") + ": " + app.name);
                    }
                }
            }
            HomeHelper.saveApps(_context, prop, apps, !("3".equals(group)));
        } else {
            addFormError(_("Unsupported"));
        }
    }

    public void setSettings(Map settings) { _settings = new HashMap(settings); }

    /** curses Jetty for returning arrays */
    private String getJettyString(String key) {
        String[] arr = (String[]) _settings.get(key);
        if (arr == null)
            return null;
        return arr[0].trim();
    }
}
