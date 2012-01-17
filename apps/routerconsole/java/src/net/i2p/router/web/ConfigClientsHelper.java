package net.i2p.router.web;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.startup.ClientAppConfig;
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
    public static final String PROP_USER = "i2cp.username";
    public static final String PROP_PW = "i2cp.password";

    public ConfigClientsHelper() {}

    /** @since 0.8.3 */
    public String getPort() {
        return _context.getProperty(ClientManagerFacadeImpl.PROP_CLIENT_PORT,
                                    Integer.toString(ClientManagerFacadeImpl.DEFAULT_PORT));
    }

    /** @since 0.8.3 */
    public String getUser() {
        return _context.getProperty(PROP_USER, "");
    }

    /** @since 0.8.3 */
    public String getPw() {
        return _context.getProperty(PROP_PW, "");
    }

    /** @since 0.8.3 */
    public String i2cpModeChecked(int mode) {
        boolean disabled =  _context.getBooleanProperty(PROP_DISABLE_EXTERNAL);
        boolean ssl =  _context.getBooleanProperty(PROP_ENABLE_SSL);
        if ((mode == 0 && disabled) ||
            (mode == 1 && (!disabled) && (!ssl)) ||
            (mode == 2 && (!disabled) && ssl))
            return "checked=\"true\"";
        return "";
    }

    /** @since 0.8.3 */
    public String getAuth() {
        boolean enabled =  _context.getBooleanProperty(PROP_AUTH);
        if (enabled)
            return "checked=\"true\"";
        return "";
    }

    /** @since 0.8.3 */
    public String[] intfcAddresses() {
        ArrayList<String> al = new ArrayList(Addresses.getAllAddresses());
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
        String xStart = _("Edit");
        if (edit.startsWith(xStart + "<span class=hide> ") &&
            edit.endsWith("</span>")) {
            // IE sucks
            _edit = edit.substring(xStart.length() + 18, edit.length() - 7);
        } else if (edit.startsWith("Edit ")) {
            _edit = edit.substring(5);
        } else if (edit.startsWith(xStart + ' ')) {
            _edit = edit.substring(xStart.length() + 1);
        } else if ((_("Add Client")).equals(edit)) {
            _edit = "new";
        }
    }

    public String getForm1() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">" + _("Client") + "</th><th>" + _("Run at Startup?") + "</th><th>" + _("Control") + "</th><th align=\"left\">" + _("Class and arguments") + "</th></tr>\n");
        
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = clients.get(cur);
            renderForm(buf, ""+cur, ca.clientName, false, !ca.disabled,
                       // dangerous, but allow editing the console args too
                       //"webConsole".equals(ca.clientName) || "Web console".equals(ca.clientName),
                       false,
                       ca.className + ((ca.args != null) ? " " + ca.args : ""), (""+cur).equals(_edit),
                       true, false, 
                       // Enable this one and comment out the false below once the stub is filled in.
                       //!ca.disabled && !("webConsole".equals(ca.clientName) || "Web console".equals(ca.clientName)),
                       false,

                       true, ca.disabled);
        }
        
        if ("new".equals(_edit))
            renderForm(buf, "" + clients.size(), "", false, false, false, "", true, false, false, false, false, false);
        buf.append("</table>\n");
        return buf.toString();
    }

    public String getForm2() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">" + _("WebApp") + "</th><th>" + _("Run at Startup?") + "</th><th>" + _("Control") + "</th><th align=\"left\">" + _("Description") + "</th></tr>\n");
        Properties props = RouterConsoleRunner.webAppProperties();
        Set<String> keys = new TreeSet(props.keySet());
        for (Iterator<String> iter = keys.iterator(); iter.hasNext(); ) {
            String name = iter.next();
            if (name.startsWith(RouterConsoleRunner.PREFIX) && name.endsWith(RouterConsoleRunner.ENABLED)) {
                String app = name.substring(RouterConsoleRunner.PREFIX.length(), name.lastIndexOf(RouterConsoleRunner.ENABLED));
                String val = props.getProperty(name);
                renderForm(buf, app, app, !"addressbook".equals(app),
                           "true".equals(val), RouterConsoleRunner.ROUTERCONSOLE.equals(app), app + ".war",
                           false, false, false, false, false, true);
            }
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    public boolean showPlugins() {
        return PluginStarter.pluginsEnabled(_context);
    }

    public String getForm3() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">" + _("Plugin") + "</th><th>" + _("Run at Startup?") + "</th><th>" + _("Control") + "</th><th align=\"left\">" + _("Description") + "</th></tr>\n");
        Properties props = PluginStarter.pluginProperties();
        Set<String> keys = new TreeSet(props.keySet());
        for (Iterator<String> iter = keys.iterator(); iter.hasNext(); ) {
            String name = iter.next();
            if (name.startsWith(PluginStarter.PREFIX) && name.endsWith(PluginStarter.ENABLED)) {
                String app = name.substring(PluginStarter.PREFIX.length(), name.lastIndexOf(PluginStarter.ENABLED));
                String val = props.getProperty(name);
                Properties appProps = PluginStarter.pluginProperties(_context, app);
                if (appProps.isEmpty())
                    continue;
                StringBuilder desc = new StringBuilder(256);
                desc.append("<table border=\"0\">")
                    .append("<tr><td><b>").append(_("Version")).append("</b></td><td>").append(stripHTML(appProps, "version"))
                    .append("<tr><td><b>")
                    .append(_("Signed by")).append("</b></td><td>");
                String s = stripHTML(appProps, "signer");
                if (s != null) {
                    if (s.indexOf("@") > 0)
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
                            .append(_("Date")).append("</b></td><td>").append(date);
                    }
                }
                s = stripHTML(appProps, "author");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_("Author")).append("</b></td><td>");
                    if (s.indexOf("@") > 0)
                        desc.append("<a href=\"mailto:").append(s).append("\">").append(s).append("</a>");
                    else
                        desc.append(s);
                }
                s = stripHTML(appProps, "description_" + Messages.getLanguage(_context));
                if (s == null)
                    s = stripHTML(appProps, "description");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_("Description")).append("</b></td><td>").append(s);
                }
                s = stripHTML(appProps, "license");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_("License")).append("</b></td><td>").append(s);
                }
                s = stripHTML(appProps, "websiteURL");
                if (s != null) {
                    desc.append("<tr><td>")
                        .append("<a href=\"").append(s).append("\">").append(_("Website")).append("</a><td>&nbsp;");
                }
                String updateURL = stripHTML(appProps, "updateURL");
                if (updateURL != null) {
                    desc.append("<tr><td>")
                        .append("<a href=\"").append(updateURL).append("\">").append(_("Update link")).append("</a><td>&nbsp;");
                }
                desc.append("</table>");
                boolean enableStop = !Boolean.valueOf(appProps.getProperty("disableStop")).booleanValue();
                enableStop &= PluginStarter.isPluginRunning(app, _context);
                boolean enableStart = !PluginStarter.isPluginRunning(app, _context);
                renderForm(buf, app, app, false,
                           "true".equals(val), false, desc.toString(), false, false,
                           updateURL != null, enableStop, true, enableStart);
            }
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    /** ro trumps edit and showEditButton */
    private void renderForm(StringBuilder buf, String index, String name, boolean urlify,
                            boolean enabled, boolean ro, String desc, boolean edit,
                            boolean showEditButton, boolean showUpdateButton, boolean showStopButton,
                            boolean showDeleteButton, boolean showStartButton) {
        String escapeddesc = DataHelper.escapeHTML(desc);
        buf.append("<tr><td class=\"mediumtags\" align=\"right\" width=\"25%\">");
        if (urlify && enabled) {
            String link = "/";
            if (! RouterConsoleRunner.ROUTERCONSOLE.equals(name))
                link += name + "/";
            buf.append("<a href=\"").append(link).append("\">").append(_(name)).append("</a>");
        } else if (edit && !ro) {
            buf.append("<input type=\"text\" name=\"name").append(index).append("\" value=\"");
            if (name.length() > 0)
                buf.append(_(name));
            buf.append("\" >");
        } else {
            if (name.length() > 0)
                buf.append(_(name));
        }
        buf.append("</td><td align=\"center\" width=\"10%\"><input type=\"checkbox\" class=\"optbox\" name=\"").append(index).append(".enabled\" value=\"true\" ");
        if (enabled) {
            buf.append("checked=\"true\" ");
            if (ro)
                buf.append("disabled=\"true\" ");
        }
        buf.append("></td><td align=\"center\" width=\"15%\">");
        // The icons were way too much, so there's an X in each button class,
        // remove if you wnat to put them back
        if (showStartButton && (!ro) && !edit) {
            buf.append("<button type=\"submit\" class=\"Xaccept\" name=\"action\" value=\"Start ").append(index).append("\" >" + _("Start") + "<span class=hide> ").append(index).append("</span></button>");
        }
        if (showEditButton && (!edit) && !ro)
            buf.append("<button type=\"submit\" class=\"Xadd\" name=\"edit\" value=\"Edit ").append(index).append("\" >" + _("Edit") + "<span class=hide> ").append(index).append("</span></button>");
        if (showStopButton && (!edit))
            buf.append("<button type=\"submit\" class=\"Xstop\" name=\"action\" value=\"Stop ").append(index).append("\" >" + _("Stop") + "<span class=hide> ").append(index).append("</span></button>");
        if (showUpdateButton && (!edit) && !ro) {
            buf.append("<button type=\"submit\" class=\"Xcheck\" name=\"action\" value=\"Check ").append(index).append("\" >" + _("Check for updates") + "<span class=hide> ").append(index).append("</span></button>");
            buf.append("<button type=\"submit\" class=\"Xdownload\" name=\"action\" value=\"Update ").append(index).append("\" >" + _("Update") + "<span class=hide> ").append(index).append("</span></button>");
        }
        if (showDeleteButton && (!edit) && !ro) {
            buf.append("<button type=\"submit\" class=\"Xdelete\" name=\"action\" value=\"Delete ").append(index)
               .append("\" onclick=\"if (!confirm('")
               .append(_("Are you sure you want to delete {0}?", _(name)))
               .append("')) { return false; }\">")
               .append(_("Delete")).append("<span class=hide> ").append(index).append("</span></button>");
        }
        buf.append("</td><td align=\"left\" width=\"50%\">");
        if (edit && !ro) {
            buf.append("<input type=\"text\" size=\"80\" name=\"desc").append(index).append("\" value=\"");
            buf.append(escapeddesc);
            buf.append("\" >");
        } else {
            buf.append(escapeddesc);
        }
        buf.append("</td></tr>\n");
    }

    /**
     *  Like in DataHelper but doesn't convert null to ""
     *  There's a lot worse things a plugin could do but...
     */
    static String stripHTML(Properties props, String key) {
        String orig = props.getProperty(key);
        if (orig == null) return null;
        String t1 = orig.replace('<', ' ');
        String rv = t1.replace('>', ' ');
        return rv;
    }
}
