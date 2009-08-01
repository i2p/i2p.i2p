package net.i2p.router.web;

import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.router.RouterContext;
import net.i2p.router.startup.ClientAppConfig;

public class ConfigClientsHelper extends HelperBase {
    public ConfigClientsHelper() {}
    
    public String getForm1() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">Client</th><th>Run at Startup?</th><th>Start Now</th><th align=\"left\">Class and arguments</th></tr>\n");
        
        List clients = ClientAppConfig.getClientApps(_context);
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = (ClientAppConfig) clients.get(cur);
            renderForm(buf, ""+cur, ca.clientName, false, !ca.disabled, "webConsole".equals(ca.clientName),
                       ca.className + ((ca.args != null) ? " " + ca.args : ""));
        }
        
        buf.append("</table>\n");
        return buf.toString();
    }

    public String getForm2() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">WebApp</th><th>Run at Startup?</th><th>Start Now</th><th align=\"left\">Description</th></tr>\n");
        Properties props = RouterConsoleRunner.webAppProperties();
        Set keys = new TreeSet(props.keySet());
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (name.startsWith(RouterConsoleRunner.PREFIX) && name.endsWith(RouterConsoleRunner.ENABLED)) {
                String app = name.substring(RouterConsoleRunner.PREFIX.length(), name.lastIndexOf(RouterConsoleRunner.ENABLED));
                String val = props.getProperty(name);
                renderForm(buf, app, app, !"addressbook".equals(app), "true".equals(val), RouterConsoleRunner.ROUTERCONSOLE.equals(app), app + ".war");
            }
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    private void renderForm(StringBuilder buf, String index, String name, boolean urlify, boolean enabled, boolean ro, String desc) {
        buf.append("<tr><td class=\"mediumtags\" align=\"right\" width=\"25%\">");
        if (urlify && enabled) {
            String link = "/";
            if (! RouterConsoleRunner.ROUTERCONSOLE.equals(name))
                link += name + "/";
            buf.append("<a href=\"").append(link).append("\">").append(name).append("</a>");
        } else {
            buf.append(name);
        }
        buf.append("</td><td align=\"center\" width=\"10%\"><input type=\"checkbox\" class=\"optbox\" name=\"").append(index).append(".enabled\" value=\"true\" ");
        if (enabled) {
            buf.append("checked=\"true\" ");
            if (ro)
                buf.append("disabled=\"true\" ");
        }
        buf.append("/></td><td align=\"center\" width=\"15%\">");
        if (!enabled) {
            buf.append("<button type=\"submit\" name=\"action\" value=\"Start ").append(index).append("\" >Start<span class=hide> ").append(index).append("</span></button>");
        }
        buf.append("</td><td align=\"left\" width=\"50%\">").append(desc).append("</td></tr>\n");
    }
}
