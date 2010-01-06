package net.i2p.router.web;

import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.router.startup.ClientAppConfig;

public class ConfigClientsHelper extends HelperBase {
    private String _edit;

    public ConfigClientsHelper() {}
    
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
        buf.append("<tr><th align=\"right\">" + _("Client") + "</th><th>" + _("Run at Startup?") + "</th><th>" + _("Start Now") + "</th><th align=\"left\">" + _("Class and arguments") + "</th></tr>\n");
        
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = clients.get(cur);
            renderForm(buf, ""+cur, ca.clientName, false, !ca.disabled,
                       "webConsole".equals(ca.clientName) || "Web console".equals(ca.clientName),
                       ca.className + ((ca.args != null) ? " " + ca.args : ""), (""+cur).equals(_edit), true);
        }
        
        if ("new".equals(_edit))
            renderForm(buf, "" + clients.size(), "", false, false, false, "", true, false);
        buf.append("</table>\n");
        return buf.toString();
    }

    public String getForm2() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">" + _("WebApp") + "</th><th>" + _("Run at Startup?") + "</th><th>" + _("Start Now") + "</th><th align=\"left\">" + _("Description") + "</th></tr>\n");
        Properties props = RouterConsoleRunner.webAppProperties();
        Set<String> keys = new TreeSet(props.keySet());
        for (Iterator<String> iter = keys.iterator(); iter.hasNext(); ) {
            String name = iter.next();
            if (name.startsWith(RouterConsoleRunner.PREFIX) && name.endsWith(RouterConsoleRunner.ENABLED)) {
                String app = name.substring(RouterConsoleRunner.PREFIX.length(), name.lastIndexOf(RouterConsoleRunner.ENABLED));
                String val = props.getProperty(name);
                renderForm(buf, app, app, !"addressbook".equals(app),
                           "true".equals(val), RouterConsoleRunner.ROUTERCONSOLE.equals(app), app + ".war", false, false);
            }
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    /** ro trumps edit and showEditButton */
    private void renderForm(StringBuilder buf, String index, String name, boolean urlify,
                            boolean enabled, boolean ro, String desc, boolean edit, boolean showEditButton) {
        buf.append("<tr><td class=\"mediumtags\" align=\"right\" width=\"25%\">");
        if (urlify && enabled) {
            String link = "/";
            if (! RouterConsoleRunner.ROUTERCONSOLE.equals(name))
                link += name + "/";
            buf.append("<a href=\"").append(link).append("\">").append(_(name)).append("</a>");
        } else if (edit && !ro) {
            buf.append("<input type=\"text\" name=\"name").append(index).append("\" value=\"");
            buf.append(_(name));
            buf.append("\" >");
        } else {
            buf.append(_(name));
        }
        buf.append("</td><td align=\"center\" width=\"10%\"><input type=\"checkbox\" class=\"optbox\" name=\"").append(index).append(".enabled\" value=\"true\" ");
        if (enabled) {
            buf.append("checked=\"true\" ");
            if (ro)
                buf.append("disabled=\"true\" ");
        }
        buf.append("/></td><td align=\"center\" width=\"15%\">");
        if ((!enabled) && !edit) {
            buf.append("<button type=\"submit\" name=\"action\" value=\"Start ").append(index).append("\" >" + _("Start") + "<span class=hide> ").append(index).append("</span></button>");
        }
        if (showEditButton && (!edit) && !ro) {
            buf.append("<button type=\"submit\" name=\"edit\" value=\"Edit ").append(index).append("\" >" + _("Edit") + "<span class=hide> ").append(index).append("</span></button>");
            buf.append("<button type=\"submit\" name=\"action\" value=\"Delete ").append(index).append("\" >" + _("Delete") + "<span class=hide> ").append(index).append("</span></button>");
        }
        buf.append("</td><td align=\"left\" width=\"50%\">");
        if (edit && !ro) {
            buf.append("<input type=\"text\" size=\"80\" name=\"desc").append(index).append("\" value=\"");
            buf.append(desc);
            buf.append("\" >");
        } else {
            buf.append(desc);
        }
        buf.append("</td></tr>\n");
    }
}
