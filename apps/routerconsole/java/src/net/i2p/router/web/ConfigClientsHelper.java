package net.i2p.router.web;

import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.router.RouterContext;
import net.i2p.router.startup.ClientAppConfig;

public class ConfigClientsHelper {
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public ConfigClientsHelper() {}
    
    public String getForm1() {
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<table border=\"1\">\n");
        buf.append("<tr><td>Client</td><td>Run at Startup?</td><td>Start Now</td><td>Class and arguments</td></tr>\n");
        
        List clients = ClientAppConfig.getClientApps(_context);
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = (ClientAppConfig) clients.get(cur);
            renderForm(buf, ""+cur, ca.clientName, false, !ca.disabled, "webConsole".equals(ca.clientName), ca.className + " " + ca.args);
        }
        
        buf.append("</table>\n");
        return buf.toString();
    }

    public String getForm2() {
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<table border=\"1\">\n");
        buf.append("<tr><td>WebApp</td><td>Run at Startup?</td><td>Start Now</td><td>Description</td></tr>\n");
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

    private void renderForm(StringBuffer buf, String index, String name, boolean urlify, boolean enabled, boolean ro, String desc) {
        buf.append("<tr><td>");
        if (urlify && enabled) {
            String link = "/";
            if (! RouterConsoleRunner.ROUTERCONSOLE.equals(name))
                link += name + "/";
            buf.append("<a href=\"").append(link).append("\">").append(name).append("</a>");
        } else {
            buf.append(name);
        }
        buf.append("</td><td align=\"center\"><input type=\"checkbox\" name=\"").append(index).append(".enabled\" value=\"true\" ");
        if (enabled) {
            buf.append("checked=\"true\" ");
            if (ro)
                buf.append("disabled=\"true\" ");
        }
        buf.append("/></td><td>&nbsp");
        if (!enabled) {
            buf.append("<button type=\"submit\" name=\"action\" value=\"Start ").append(index).append("\" />Start</button>");
        }
        buf.append("&nbsp</td><td>").append(desc).append("</td></tr>\n");
    }
}
