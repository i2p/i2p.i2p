package net.i2p.router.web.helpers;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppState;
import net.i2p.data.DataHelper;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.router.startup.LoadClientAppsJob;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.router.web.PluginStarter;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.router.web.WebAppStarter;
import net.i2p.util.Addresses;

public class ConfigClientsHelper extends HelperBase {
    private String _edit;

    /** from ClientListenerRunner */
    public static final String BIND_ALL_INTERFACES = "i2cp.tcp.bindAllInterfaces";
    /** from ClientManager */
    public static final String PROP_DISABLE_EXTERNAL = "i2cp.disableInterface";
    public static final String PROP_ENABLE_SSL = "i2cp.SSL";
    /** from ClientMessageEventListener */
    public static final String PROP_AUTH = "i2cp.auth";
    public static final String PROP_ENABLE_CLIENT_CHANGE = "routerconsole.enableClientChange";
    public static final String PROP_ENABLE_PLUGIN_INSTALL = "routerconsole.enablePluginInstall";

    public ConfigClientsHelper() {}

    /** @since 0.9.14.1 */
    public boolean isClientChangeEnabled() {
        return _context.getBooleanProperty(PROP_ENABLE_CLIENT_CHANGE) || isAdvanced();
    }

    /** @since 0.9.14.1 */
    public boolean isPluginInstallEnabled() {
        return PluginStarter.pluginsEnabled(_context) &&
               (_context.getBooleanPropertyDefaultTrue(PROP_ENABLE_PLUGIN_INSTALL) || isAdvanced());
    }

    /** @since 0.9.15 */
    public boolean isPluginUpdateEnabled() {
        return !PluginStarter.getPlugins().isEmpty();
    }

    /** @since 0.8.3 */
    public String getPort() {
        return _context.getProperty(ClientManagerFacadeImpl.PROP_CLIENT_PORT,
                                    Integer.toString(ClientManagerFacadeImpl.DEFAULT_PORT));
    }

    /** @since 0.8.3 */
    public String i2cpModeChecked(int mode) {
        boolean disabled =  _context.getBooleanProperty(PROP_DISABLE_EXTERNAL);
        boolean ssl =  _context.getBooleanProperty(PROP_ENABLE_SSL);
        if ((mode == 0 && disabled) ||
            (mode == 1 && (!disabled) && (!ssl)) ||
            (mode == 2 && (!disabled) && ssl))
            return CHECKED;
        return "";
    }

    /** @since 0.8.3 */
    public String getAuth() {
        boolean enabled =  _context.getBooleanProperty(PROP_AUTH);
        if (enabled)
            return CHECKED;
        return "";
    }

    /** @since 0.8.3 */
    public String[] intfcAddresses() {
        ArrayList<String> al = new ArrayList<String>(Addresses.getAllAddresses());
        return al.toArray(new String[al.size()]);
    }

    /** @since 0.8.3 */
    public boolean isIFSelected(String addr) {
        boolean bindAll = _context.getBooleanProperty(BIND_ALL_INTERFACES);
        if (bindAll && addr.equals("0.0.0.0") || addr.equals("::"))
            return true;
        String host = _context.getProperty(ClientManagerFacadeImpl.PROP_CLIENT_HOST, 
                                           ClientManagerFacadeImpl.DEFAULT_HOST);
        return (host.equals(addr));
    }

    public void setEdit(String edit) {
         if (edit == null)
             return;
        String xStart = _t("Edit");
        if (edit.startsWith(xStart + "<span class=hide> ") &&
            edit.endsWith("</span>")) {
            // IE sucks
            _edit = edit.substring(xStart.length() + 18, edit.length() - 7);
        } else if (edit.startsWith("Edit ")) {
            _edit = edit.substring(5);
        } else if (edit.startsWith(xStart + ' ')) {
            _edit = edit.substring(xStart.length() + 1);
        } else if ((_t("Add Client")).equals(edit)) {
            _edit = "new";
        }
    }

    /** clients */
    public String getForm1() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table id=\"clientconfig\">\n" +
                   "<tr><th align=\"right\">").append(_t("Client")).append("</th><th>")
           .append(_t("Run at Startup?")).append("</th><th>")
           .append(_t("Control")).append("</th><th align=\"left\">")
           .append(_t("Class and arguments")).append("</th></tr>\n");
        
        boolean allowEdit = isClientChangeEnabled();
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        List<CAC> cacs = new ArrayList<CAC>(clients.size());
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = clients.get(cur);
            String xname = ca.clientName;
            if (xname.length() > 0)
                xname = _t(xname);
            cacs.add(new CAC(cur, ca, xname));
        }
        Collections.sort(cacs, new CACComparator());
        for (CAC cac : cacs) {
            ClientAppConfig ca = cac.config;
            int cur = cac.index;
            boolean isConsole = ca.className.equals("net.i2p.router.web.RouterConsoleRunner");
            boolean showStart;
            boolean showStop;
            if (isConsole) {
                showStart = false;
                showStop = false;
            } else {
                ClientApp clientApp = _context.routerAppManager().getClientApp(ca.className, LoadClientAppsJob.parseArgs(ca.args));
                showStart = clientApp == null;
                showStop = clientApp != null && clientApp.getState() == ClientAppState.RUNNING;
            }
            renderForm(buf, ""+cur, ca.clientName,
                       // urlify, enabled
                       false, !ca.disabled,
                       // read only, preventDisable
                       // dangerous, but allow editing the console args too
                       //"webConsole".equals(ca.clientName) || "Web console".equals(ca.clientName),
                       false, RouterConsoleRunner.class.getName().equals(ca.className),
                       // description
                       DataHelper.escapeHTML(ca.className + ((ca.args != null) ? " " + ca.args : "")),
                       // edit
                       allowEdit && (""+cur).equals(_edit),
                       // show edit button, show update button
                       // Don't allow edit if it's running, or else we would lose the "handle" to the ClientApp to stop it.
                       allowEdit && !showStop, false, 
                       // show stop button
                       showStop,
                       // show delete button, show start button
                       allowEdit && !isConsole, showStart);
        }
        
        if (allowEdit && "new".equals(_edit))
            renderForm(buf, "" + clients.size(), "", false, false, false, false, "", true, false, false, false, false, false);
        buf.append("</table>\n");
        return buf.toString();
    }

    /** @since 0.9.20 */
    private static class CAC {
        public final int index;
        public final ClientAppConfig config;
        public final String xname;

        public CAC(int idx, ClientAppConfig cfg, String xn) {
            index = idx; config = cfg; xname = xn;
        }
    }

    /** @since 0.9.20 */
    private class CACComparator implements Comparator<CAC> {
         private static final long serialVersionUID = 1L;
         private final Collator coll;

         public CACComparator() {
             super();
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(CAC l, CAC r) {
             return coll.compare(l.xname, r.xname);
        }
    }

    /** webapps */
    public String getForm2() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table id=\"webappconfig\">\n" +
                   "<tr><th align=\"right\">").append(_t("WebApp")).append("</th><th>")
           .append(_t("Run at Startup?")).append("</th><th>")
           .append(_t("Control")).append("</th><th align=\"left\">")
           .append(_t("Description")).append("</th></tr>\n");
        Properties props = RouterConsoleRunner.webAppProperties(_context);
        Set<String> keys = new TreeSet<String>(props.stringPropertyNames());
        for (String name : keys) {
            if (name.startsWith(RouterConsoleRunner.PREFIX) && name.endsWith(RouterConsoleRunner.ENABLED)) {
                String app = name.substring(RouterConsoleRunner.PREFIX.length(), name.lastIndexOf(RouterConsoleRunner.ENABLED));
                String val = props.getProperty(name);
                boolean isRunning = WebAppStarter.isWebAppRunning(app);
                String desc;
                // use descriptions already tagged elsewhere
                if (app.equals("routerconsole"))
                    desc = _t("I2P Router Console");
                else if (app.equals("i2psnark"))
                    desc = _t("Torrents");
                else if (app.equals("i2ptunnel"))
                    desc = _t("Hidden Services Manager");
                else if (app.equals("imagegen"))
                    desc = _t("Identification Image Generator");
                else if (app.equals("susidns"))
                    desc = _t("Addressbook");
                else if (app.equals("susimail"))
                    desc = _t("Email");
                else
                    desc = DataHelper.escapeHTML(app) + ".war";
                renderForm(buf, app, app,
                           RouterConsoleRunner.ROUTERCONSOLE.equals(app) || (isRunning && !"addressbook".equals(app)),
                           "true".equals(val), RouterConsoleRunner.ROUTERCONSOLE.equals(app),
                           RouterConsoleRunner.ROUTERCONSOLE.equals(app), desc,
                           false, false, false, isRunning, false, !isRunning);
            }
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    public boolean showPlugins() {
        return PluginStarter.pluginsEnabled(_context);
    }

    /** plugins */
    public String getForm3() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table id=\"pluginconfig\">\n" +
                   "<tr><th align=\"right\">").append(_t("Plugin")).append("</th><th>")
           .append(_t("Run at Startup?")).append("</th><th>")
           .append(_t("Control")).append("</th><th align=\"left\">")
           .append(_t("Description")).append("</th></tr>\n");
        Properties props = PluginStarter.pluginProperties();
        Set<String> keys = new TreeSet<String>(props.stringPropertyNames());
        for (String name : keys) {
            if (name.startsWith(PluginStarter.PREFIX) && name.endsWith(PluginStarter.ENABLED)) {
                String app = name.substring(PluginStarter.PREFIX.length(), name.lastIndexOf(PluginStarter.ENABLED));
                String val = props.getProperty(name);
                if (val.equals(PluginStarter.DELETED))
                    continue;
                Properties appProps = PluginStarter.pluginProperties(_context, app);
                if (appProps.isEmpty())
                    continue;
                StringBuilder desc = new StringBuilder(256);
                desc.append("<table border=\"0\">")
                    .append("<tr><td><b>").append(_t("Version")).append("</b></td><td>").append(stripHTML(appProps, "version"))
                    .append("<tr><td><b>")
                    .append(_t("Signed by")).append("</b></td><td>");
                String s = stripHTML(appProps, "signer");
                if (s != null) {
                    if (s.indexOf('@') > 0)
                        desc.append("<a href=\"mailto:").append(s).append("\">").append(s).append("</a>");
                    else
                        desc.append(s);
                }
                s = stripHTML(appProps, "date");
                if (s != null) {
                    long ms = 0;
                    try {
                        ms = Long.parseLong(s);
                    } catch (NumberFormatException nfe) {}
                    if (ms > 0) {
                        String date = (new SimpleDateFormat("yyyy-MM-dd HH:mm")).format(new Date(ms));
                        desc.append("<tr><td><b>")
                            .append(_t("Date")).append("</b></td><td>").append(date);
                    }
                }
                s = stripHTML(appProps, "author");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_t("Author")).append("</b></td><td>");
                    if (s.indexOf('@') > 0)
                        desc.append("<a href=\"mailto:").append(s).append("\">").append(s).append("</a>");
                    else
                        desc.append(s);
                }
                s = stripHTML(appProps, "description_" + Messages.getLanguage(_context));
                if (s == null)
                    s = stripHTML(appProps, "description");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_t("Description")).append("</b></td><td>").append(s);
                }
                s = stripHTML(appProps, "license");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_t("License")).append("</b></td><td>").append(s);
                }
                s = stripHTML(appProps, "websiteURL");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_t("Website")).append("</b></td><td><a href=\"")
                        .append(s).append("\" target=\"_blank\">").append(s).append("</a>");
                }
                String updateURL = stripHTML(appProps, "updateURL.su3");
                if (updateURL == null)
                    updateURL = stripHTML(appProps, "updateURL");
                if (updateURL != null) {
                    desc.append("<tr><td><b>")
                        .append(_t("Update link")).append("</b></td><td><a href=\"")
                        .append(updateURL).append("\">").append(updateURL).append("</a>");
                }
                desc.append("</table>");
                boolean isRunning = PluginStarter.isPluginRunning(app, _context);
                boolean enableStop = isRunning && !Boolean.parseBoolean(appProps.getProperty("disableStop"));
                boolean enableStart = !isRunning;
                renderForm(buf, app, app, false,
                           "true".equals(val), false, false, desc.toString(), false, false,
                           updateURL != null, enableStop, true, enableStart);
            }
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    /**
     *  Misnamed, renders a single line in a table for a single client/webapp/plugin.
     *
     *  @param name will be escaped here
     *  @param ro trumps edit and showEditButton
     *  @param escapedDesc description, must be HTML escaped, except for plugins
     */
    private void renderForm(StringBuilder buf, String index, String name, boolean urlify,
                            boolean enabled, boolean ro, boolean preventDisable, String escapedDesc, boolean edit,
                            boolean showEditButton, boolean showUpdateButton, boolean showStopButton,
                            boolean showDeleteButton, boolean showStartButton) {
        String escapedName = DataHelper.escapeHTML(name);
        buf.append("<tr><td align=\"right\">");
        if (urlify) {
            String link = "/";
            if (! RouterConsoleRunner.ROUTERCONSOLE.equals(name))
                link += escapedName + "/";
            buf.append("<a href=\"").append(link).append("\">").append(_t(escapedName)).append("</a>");
        } else if (edit && !ro) {
            buf.append("<input type=\"text\" name=\"nofilter_name").append(index).append("\" value=\"");
            if (name.length() > 0)
                buf.append(_t(escapedName));
            buf.append("\" >");
        } else {
            if (name.length() > 0)
                buf.append("<label for=\"").append(index).append("\">").append(_t(escapedName)).append("</label>");
        }
        buf.append("</td><td align=\"center\"><input type=\"checkbox\" class=\"optbox\" id=\"").append(index).append("\" name=\"").append(index).append(".enabled\"");
        if (enabled) {
            buf.append(CHECKED);
            if (ro || preventDisable)
                buf.append("disabled=\"disabled\" ");
        }
        buf.append("></td><td align=\"center\">");

        if (showStartButton && (!ro) && !edit) {
            buf.append("<button type=\"submit\" title=\"").append(_t("Start")).append("\" class=\"control accept\" name=\"action\" value=\"Start ").append(index).append("\" >")
               .append(_t("Start")).append("<span class=hide> ").append(index).append("</span></button>");
        }
        if (showStopButton && (!edit))
            buf.append("<button type=\"submit\" title=\"").append(_t("Stop")).append("\" class=\"control stop\" name=\"action\" value=\"Stop ").append(index).append("\" >")
               .append(_t("Stop")).append("<span class=hide> ").append(index).append("</span></button>");
        if (isClientChangeEnabled() && showEditButton && (!edit) && !ro)
            buf.append("<button type=\"submit\" title=\"").append(_t("Edit")).append("\" class=\"control add\" name=\"edit\" value=\"Edit ").append(index).append("\" >")
               .append(_t("Edit")).append("<span class=hide> ").append(index).append("</span></button>");
        if (showUpdateButton && (!edit) && !ro) {
            buf.append("<button type=\"submit\" title=\"").append(_t("Check for updates")).append("\" class=\"control check\" name=\"action\" value=\"Check ").append(index).append("\" >")
               .append(_t("Check for updates")).append("<span class=hide> ").append(index).append("</span></button>");
            buf.append("<button type=\"submit\" title=\"").append(_t("Update")).append("\" class=\"control download\" name=\"action\" value=\"Update ").append(index).append("\" >")
                .append(_t("Update")).append("<span class=hide> ").append(index).append("</span></button>");
        }
        if (showDeleteButton && (!edit) && !ro) {
            buf.append("<button type=\"submit\" title=\"").append(_t("Delete")).append("\" class=\"control delete\" name=\"action\" value=\"Delete ").append(index)
               .append("\" onclick=\"if (!confirm('")
               .append(_t("Are you sure you want to delete {0}?", _t(escapedName)))
               .append("')) { return false; }\">")
               .append(_t("Delete")).append("<span class=hide> ").append(index).append("</span></button>");
        }
        buf.append("</td><td align=\"left\">");
        if (edit && !ro) {
            buf.append("<input type=\"text\" size=\"80\" spellcheck=\"false\" name=\"nofilter_desc").append(index).append("\" value=\"");
            buf.append(escapedDesc);
            buf.append("\" >");
        } else {
            buf.append(escapedDesc);
        }
        buf.append("</td></tr>\n");
    }

    /**
     *  Like in DataHelper but doesn't convert null to ""
     *  There's a lot worse things a plugin could do but...
     */
    public static String stripHTML(Properties props, String key) {
        return PluginStarter.stripHTML(props, key);
    }
}
