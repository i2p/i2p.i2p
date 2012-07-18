package net.i2p.router.web;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.router.RouterContext;
import net.i2p.util.PortMapper;

/**
 *  For /home and /confighome
 *
 *  @since 0.9
 */
public class HomeHelper extends HelperBase {
    
    private static final char S = ',';
    private static final String I = "/themes/console/images/";
    static final String PROP_SERVICES = "routerconsole.services";
    static final String PROP_FAVORITES = "routerconsole.favorites";
    static final String PROP_OLDHOME = "routerconsole.oldHomePage";
    private static final String PROP_SEARCH = "routerconsole.showSearch";

    static final String DEFAULT_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "book_addresses.png" + S +
        _x("Configure Bandwidth") + S + _x("I2P Bandwidth Configuration") + S + "/config" + S + I + "wrench_orange.png" + S +
        _x("Configure Language") + S + _x("Console Language Selection") + S + "/configui" + S + I + "wrench_orange.png" + S +
        _x("Customize Home Page") + S + _x("I2P Home Page Configuration") + S + "/confighome" + S + I + "wrench_orange.png" + S +
        _x("Email") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "email.png" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help" + S + I + "help.png" + S +
        _x("Router Console") + S + _x("I2P Router Console") + S + "/console" + S + I + "wrench_orange.png" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "film.png" + S +
        _x("Website") + S + _x("Local web server") + S + "http://127.0.0.1:7658/" + S + I + "server.png" + S +
        "";

    static final String DEFAULT_FAVORITES =
        _x("Bug Reports") + S + _x("Bug tracker") + S + "http://trac.i2p2.i2p/report/1" + S + I + "bug.png" + S +
        _x("Dev Forum") + S + _x("Development forum") + S + "http://zzz.i2p/" + S + I + "itoopie_sm.png" + S +
        _x("diftracker") + S + _x("Bittorrent tracker") + S + "http://diftracker.i2p/" + S + I + "itoopie_sm.png" + S +
        "echelon.i2p" + S + _x("I2P Applications") + S + "http://echelon.i2p/" + S + I + "itoopie_sm.png" + S +
        _x("FAQ") + S + _x("Frequently Asked Questions") + S + "http://www.i2p2.i2p/faq" + S + I + "help.png" + S +
        _x("Forum") + S + _x("Community forum") + S + "http://forum.i2p/" + S + I + "itoopie_sm.png" + S +
        _x("Anonymous Git Hosting") + S + _x("A public anonymous Git hosting site - supports pulling via Git and HTTP and pushing via SSH") + S + "http://git.repo.i2p/" + S + I + "git-logo.png" + S +
        "Ident " + _x("Microblog") + S + _x("Your premier microblogging service on I2P") + S + "http://id3nt.i2p/" + S + I + "ident_icon_blue.png" + S +
        _x("Javadocs") + S + _x("Technical documentation") + S + "http://i2p-javadocs.i2p/" + S + I + "book.png" + S +
        _x("Key Server") + S + _x("OpenPGP Keyserver") + S + "http://keys.i2p/" + S + I + "book.png" + S +
        _x("killyourtv.i2p") + S + _x("Debian and Tahoe-LAFS repositories") + S + "http://killyourtv.i2p/" + S + I + "itoopie_sm.png" + S +
        _x("Pastebin") + S + _x("I2P Pastebin") + S + "http://pastethis.i2p/" + S + I + "itoopie_sm.png" + S +
        "Planet I2P" + S + _x("I2P News") + S + "http://planet.i2p/" + S + I + "itoopie_sm.png" + S +
        _x("Plugins") + S + _x("Add-on directory") + S + "http://plugins.i2p/" + S + I + "plugin.png" + S +
        _x("Postman's Tracker") + S + _x("Bittorrent tracker") + S + "http://tracker2.postman.i2p/" + S + I + "itoopie_sm.png" + S +
        _x("Project Website") + S + _x("I2P home page") + S + "http://www.i2p2.i2p/" + S + I + "help.png" + S +
        "stats.i2p" + S + _x("I2P Netowrk Statistics") + S + "http://stats.i2p/cgi-bin/dashboard.cgi" + S + I + "itoopie_sm.png" + S +
        _x("Technical Docs") + S + _x("Technical documentation") + S + "http://www.i2p2.i2p/how" + S + I + "book.png" + S +
        _x("Trac Wiki") + S + S + "http://trac.i2p2.i2p/" + S + I + "itoopie_sm.png" + S +
        _x("Ugha's Wiki") + S + S + "http://ugha.i2p/" + S + I + "itoopie_sm.png" + S +
        _x("Sponge's main site") + S + _x("Seedless and the Robert BitTorrent applications") + S + "http://sponge.i2p/" + S + I + "itoopie_sm.png" + S +
        "";


    public boolean shouldShowWelcome() {
        return _context.getProperty(Messages.PROP_LANG) == null;
    }

    public boolean shouldShowSearch() {
        return _context.getBooleanProperty(PROP_SEARCH);
    }

    public String getServices() {
        List<App> plugins = NavHelper.getClientApps(_context);
        return homeTable(PROP_SERVICES, DEFAULT_SERVICES, plugins);
    }

    public String getFavorites() {
        return homeTable(PROP_FAVORITES, DEFAULT_FAVORITES, null);
    }

    public String getConfigServices() {
        return configTable(PROP_SERVICES, DEFAULT_SERVICES);
    }

    public String getConfigFavorites() {
        return configTable(PROP_FAVORITES, DEFAULT_FAVORITES);
    }

    public String getConfigSearch() {
        return configTable(SearchHelper.PROP_ENGINES, SearchHelper.ENGINES_DEFAULT);
    }

    public String getConfigHome() {
        boolean oldHome = _context.getBooleanProperty(PROP_OLDHOME);
        return oldHome ? "checked=\"true\"" : "";
    }

    public String getProxyStatus() {
        int port = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY);
        if (port <= 0)
            return _("The HTTP proxy is not up");
        return "<img src=\"http://console.i2p/onepixel.png?" + _context.random().nextInt() + "\"" +
               " alt=\"" + _("Your browser is not properly configured to use the HTTP proxy at {0}",
                             _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST) + ':' + port) +
               "\">";
    }

    private String homeTable(String prop, String dflt, Collection<App> toAdd) {
        String config = _context.getProperty(prop, dflt);
        Collection<App> apps = buildApps(_context, config);
        if (toAdd != null)
            apps.addAll(toAdd);
        return renderApps(apps);
    }

    private String configTable(String prop, String dflt) {
        String config = _context.getProperty(prop, dflt);
        Collection<App> apps;
        if (prop.equals(SearchHelper.PROP_ENGINES))
            apps = buildSearchApps(config);
        else
            apps = buildApps(_context, config);
        return renderConfig(apps);
    }

    static Collection<App> buildApps(RouterContext ctx, String config) {
        String[] args = config.split("" + S);
        Set<App> apps = new TreeSet(new AppComparator());
        for (int i = 0; i < args.length - 3; i += 4) {
            String name = Messages.getString(args[i], ctx);
            String desc = Messages.getString(args[i+1], ctx);
            String url = args[i+2];
            String icon = args[i+3];
            apps.add(new App(name, desc, url, icon));
        }
        return apps;
    }

    static Collection<App> buildSearchApps(String config) {
        String[] args = config.split("" + S);
        Set<App> apps = new TreeSet(new AppComparator());
        for (int i = 0; i < args.length - 1; i += 2) {
            String name = args[i];
            String url = args[i+1];
            apps.add(new App(name, null, url, null));
        }
        return apps;
    }

    static void saveApps(RouterContext ctx, String prop, Collection<App> apps, boolean full) {
        StringBuilder buf = new StringBuilder(1024);
        for (App app : apps) {
            buf.append(app.name).append(S);
            if (full)
                buf.append(app.desc).append(S);
            buf.append(app.url).append(S);
            if (full)
                buf.append(app.icon).append(S);
        }
        ctx.router().saveConfig(prop, buf.toString());
    }

    private static String renderApps(Collection<App> apps) {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=\"appgroup\">");
        for (App app : apps) {
            buf.append("<div class=\"app\">" +
                       "<div class=\"appimg\">" +
                       "<a href=\"").append(app.url).append("\">" +
                       "<img class=\"");
            // toopie is 54x68, not 16x16, needs special alignment and sizing
            if (app.icon.endsWith("/itoopie_sm.png"))
                buf.append("app2p");
            else
                buf.append("app");
            buf.append("\" alt=\"\" title=\"").append(app.desc).append("\" src=\"").append(app.icon).append("\"></a>\n" +
                       "</div>" +
                       "<table class=\"app\"><tr class=\"app\"><td class=\"app\">" +
                       "<div class=\"applabel\">" +
                       "<a href=\"").append(app.url).append("\" title=\"").append(app.desc).append("\">").append(app.name).append("</a>" +
                       "</div>" +
                       "</td></tr></table>" +
                       "</div>\n");
        }
        buf.append("</div>\n");
        return buf.toString();
    }

    private String renderConfig(Collection<App> apps) {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table><tr><th>")
           .append(_("Remove"))
           .append("</th><th colspan=\"2\">")
           .append(_("Name"))
           .append("</th><th>")
           .append(_("URL"))
           .append("</th></tr>\n");
        for (App app : apps) {
            buf.append("<tr><td align=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"delete_")
               .append(app.name)
               .append("\"></td><td align=\"center\">");
            if (app.icon != null) {
                buf.append("<img height=\"16\" alt=\"\" src=\"").append(app.icon).append("\">");
            }
            buf.append("</td><td align=\"left\">")
               .append(app.name)
               .append("</td><td align=\"left\"><a href=\"")
               .append(app.url.replace("&", "&amp;"))
               .append("\">")
               .append(app.url.replace("&", "&amp;"))
               .append("</a></td></tr>\n");
        }
        buf.append("<tr><td colspan=\"2\" align=\"center\"><b>")
           .append(_("Add")).append(":</b>" +
                   "</td><td align=\"left\"><input type=\"text\" name=\"name\"></td>" +
                   "<td align=\"left\"><input type=\"text\" size=\"40\" name=\"url\"></td></tr>");
        buf.append("</table>\n");
        return buf.toString();
    }

    static class App {
        public final String name;
        public final String desc;
        public final String url;
        public final String icon;

        public App(String name, String desc, String url, String icon) {
            this.name = name;
            this.desc = desc;
            this.url = url;
            this.icon = icon;
        }
    }

    /** ignore case, current locale */
    private static class AppComparator implements Comparator<App> {
        public int compare(App l, App r) {
            return l.name.toLowerCase().compareTo(r.name.toLowerCase());
        }
    }
}
