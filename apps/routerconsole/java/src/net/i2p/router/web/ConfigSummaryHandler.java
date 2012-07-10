package net.i2p.router.web;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataHelper;

/**
 *  Simple summary bar configuration.
 *
 *  @since 0.9.1
 */
public class ConfigSummaryHandler extends FormHandler {

    private Map _settings;
    
    @Override
    protected void processForm() {
        if (_action == null) return;
        String group = getJettyString("group");
        boolean deleting = _action.equals(_("Delete selected"));
        boolean adding = _action.equals(_("Add item"));
        boolean saving = _action.equals(_("Save order"));
        boolean moving = _action.startsWith("move_");
        if (_action.equals(_("Save")) && "0".equals(group)) {
            try {
                int refreshInterval = Integer.parseInt(getJettyString("refreshInterval"));
                if (refreshInterval >= CSSHelper.MIN_REFRESH) {
                    _context.router().saveConfig(CSSHelper.PROP_REFRESH, "" + refreshInterval);
                    addFormNotice(_("Refresh interval changed"));
                } else
                    addFormError(_("Refresh interval must be at least {0} seconds", CSSHelper.MIN_REFRESH));
            } catch (java.lang.NumberFormatException e) {
                addFormError(_("Refresh interval must be a number"));
                return;
            }
        } else if (_action.equals(_("Restore full default"))) {
            _context.router().saveConfig(SummaryHelper.PROP_SUMMARYBAR + "default", SummaryHelper.DEFAULT_FULL);
            addFormNotice(_("Full summary bar default restored.") + " " +
                          _("Summary bar will refresh shortly."));
        } else if (_action.equals(_("Restore minimal default"))) {
            _context.router().saveConfig(SummaryHelper.PROP_SUMMARYBAR + "default", SummaryHelper.DEFAULT_MINIMAL);
            addFormNotice(_("Minimal summary bar default restored.") + " " +
                          _("Summary bar will refresh shortly."));
        } else if (adding || deleting || saving || moving) {
            Map<Integer, String> sections = new TreeMap<Integer, String>();
            for (Object o : _settings.keySet()) {
                if (!(o instanceof String))
                    continue;
                String k = (String) o;
                if (!k.startsWith("order_"))
                    continue;
                String v = getJettyString(k);
                k = k.substring(6);
                k = k.substring(k.indexOf('_') + 1);
                try {
                    int order = Integer.parseInt(v);
                    sections.put(order, k);
                } catch (java.lang.NumberFormatException e) {
                    addFormError(_("Order must be an integer"));
                    return;
                }
            }
            if (adding) {
                String name = getJettyString("name");
                if (name == null || name.length() <= 0) {
                    addFormError(_("No section selected"));
                    return;
                }
                String order = getJettyString("order");
                if (order == null || order.length() <= 0) {
                    addFormError(_("No order entered"));
                    return;
                }
                name = DataHelper.escapeHTML(name).replace(",", "&#44;");
                order = DataHelper.escapeHTML(order).replace(",", "&#44;");
                try {
                    int ki = Integer.parseInt(order);
                    sections.put(ki, name);
                    addFormNotice(_("Added") + ": " + name);
                } catch (java.lang.NumberFormatException e) {
                    addFormError(_("Order must be an integer"));
                    return;
                }
            } else if (deleting) {
                Set<Integer> toDelete = new HashSet();
                for (Object o : _settings.keySet()) {
                    if (!(o instanceof String))
                        continue;
                    String k = (String) o;
                    if (!k.startsWith("delete_"))
                        continue;
                    k = k.substring(7);
                    try {
                        int ki = Integer.parseInt(k);
                        toDelete.add(ki);
                    } catch (java.lang.NumberFormatException e) {
                        continue;
                    }
                }
                for (Iterator<Integer> iter = sections.keySet().iterator(); iter.hasNext(); ) {
                    int i = iter.next();
                    if (toDelete.contains(i)) {
                        String removedName = sections.get(i);
                        iter.remove();
                        addFormNotice(_("Removed") + ": " + removedName);
                    }
                }
            } else if (moving) {
                String parts[] = _action.split("_");
                try {
                    int from = Integer.parseInt(parts[1]);
                    int to = 0;
                    if ("up".equals(parts[2]))
                        to = from - 1;
                    if ("down".equals(parts[2]))
                        to = from + 1;
                    if ("bottom".equals(parts[2]))
                        to = sections.size() - 1;
                    int n = -1;
                    if ("down".equals(parts[2]) || "bottom".equals(parts[2]))
                        n = 1;
                    for (int i = from; n * i < n * to; i += n) {
                        String temp = sections.get(i + n);
                        sections.put(i + n, sections.get(i));
                        sections.put(i, temp);
                    }
                    addFormNotice(_("Moved") + ": " + sections.get(to));
                } catch (java.lang.NumberFormatException e) {
                    addFormError(_("Order must be an integer"));
                    return;
                }
            }
            SummaryHelper.saveSummaryBarSections(_context, "default", sections);
            addFormError(_("Saved order of sections.") + " " +
                         _("Summary bar will refresh shortly."));
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

    public void setMovingAction() {
        for (Object o : _settings.keySet()) {
            if (!(o instanceof String))
                continue;
            String k = (String) o;
            if (k.startsWith("move_") && k.endsWith(".x") && _settings.get(k) != null) {
                _action = k.substring(0, k.length() - 2);
                break;
            }
        }
    }
}
