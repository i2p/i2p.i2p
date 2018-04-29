package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.web.App;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NavHelper;
import net.i2p.router.web.PluginStarter;
import net.i2p.router.web.WebAppStarter;
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

    // No commas allowed in text strings!
    static final String DEFAULT_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "book_addresses.png" + S +
        _x("Configure Bandwidth") + S + _x("I2P Bandwidth Configuration") + S + "/config" + S + I + "info/bandwidth.png" + S +
        // FIXME wasn't escaped
        _x("Configure UI") + S + _x("Select console theme & language & set optional console password").replace("&", "&amp;") + S + "/configui" + S + I + "info/ui.png" + S +
        _x("Customize Home Page") + S + _x("I2P Home Page Configuration") + S + "/confighome" + S + I + "home_page.png" + S +
        _x("Customize Sidebar") + S + _x("Customize the sidebar by adding or removing or repositioning elements") + S + "/configsidebar" + S + I + "info/sidebar.png" + S +
        _x("Email") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "email.png" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help" + S + I + "support.png" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "plugin.png" + S +
        _x("Router Console") + S + _x("I2P Router Console") + S + "/console" + S + I + "info/console.png" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "i2psnark.png" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "server_32x32.png" + S +
        "";

    // No commas allowed in text strings!
    static final String DEFAULT_FAVORITES =
        "anoncoin.i2p" + S + _x("The Anoncoin project") + S + "http://anoncoin.i2p/" + S + I + "anoncoin_32.png" + S +
        _x("I2P Bug Reports") + S + _x("Bug tracker") + S + "http://trac.i2p2.i2p/report/1" + S + I + "bug.png" + S +
        //"colombo-bt.i2p" + S + _x("The Italian Bittorrent Resource") + S + "http://colombo-bt.i2p/" + S + I + "colomboicon.png" + S +
        _x("Dev Forum") + S + _x("Development forum") + S + "http://zzz.i2p/" + S + I + "group_gear.png" + S +
        //_x("diftracker") + S + _x("Bittorrent tracker") + S + "http://diftracker.i2p/" + S + I + "magnet.png" + S +
        "echelon.i2p" + S + _x("I2P Applications") + S + "http://echelon.i2p/" + S + I + "box_open.png" + S +
        "exchanged.i2p" + S + _x("Anonymous cryptocurrency exchange") + S + "http://exchanged.i2p/" + S + I + "exchanged.png" + S +
        _x("I2P FAQ") + S + _x("Frequently Asked Questions") + S + "http://i2p-projekt.i2p/faq" + S + I + "question.png" + S +
        _x("I2P Forum") + S + _x("Community forum") + S + "http://i2pforum.i2p/" + S + I + "group.png" + S +
        //"git.repo.i2p" + S + _x("A public anonymous Git hosting site - supports pulling via Git and HTTP and pushing via SSH") + S + "http://git.repo.i2p/" + S + I + "git-logo.png" + S +
        //"hiddengate [ru]" + S + _x("Russian I2P-related wiki") + S + "http://hiddengate.i2p/" + S + I + "hglogo32.png" + S +
        _x("I2P Wiki") + S + _x("Anonymous wiki - share the knowledge") + S + "http://i2pwiki.i2p/" + S + I + "i2pwiki_logo.png" + S +
        //"Ident " + _x("Microblog") + S + _x("Your premier microblogging service on I2P") + S + "http://id3nt.i2p/" + S + I + "ident_icon_blue.png" + S +
        //_x("Javadocs") + S + _x("Technical documentation") + S + "http://i2p-javadocs.i2p/" + S + I + "education.png" + S +
        //"jisko.i2p" + S + _x("Simple and fast microblogging website") + S + "http://jisko.i2p/" + S + I + "jisko_console_icon.png" + S +
        //_x("Key Server") + S + _x("OpenPGP Keyserver") + S + "http://keys.i2p/" + S + I + "education.png" + S +
        //"killyourtv.i2p" + S + _x("Debian and Tahoe-LAFS repositories") + S + "http://killyourtv.i2p/" + S + I + "television_delete.png" + S +
        //_x("Open4You") + S + _x("Free eepsite hosting with PHP and MySQL") + S + "http://open4you.i2p/" + S + I + "open4you-logo.png" + S +
        _x("Pastebin") + S + _x("Encrypted I2P Pastebin") + S + "http://zerobin.i2p/" + S + I + "paste_plain.png" + S +
        _x("Planet I2P") + S + _x("I2P News") + S + "http://planet.i2p/" + S + I + "world.png" + S +
        _x("I2P Plugins") + S + _x("Add-on directory") + S + "http://i2pwiki.i2p/index.php?title=Plugins" + S + I + "info/plugin_link.png" + S +
        //_x("Postman's Tracker") + S + _x("Bittorrent tracker") + S + "http://tracker2.postman.i2p/" + S + I + "magnet.png" + S +
        _x("Project Website") + S + _x("I2P home page") + S + "http://i2p-projekt.i2p/" + S + I + "info_rhombus.png" + S +
        //_x("lenta news [ru]") + S + _x("Russian News Feed") + S + "http://lenta.i2p/" + S + I + "lenta_main_logo.png" + S +
        //"Salt" + S + "salt.i2p" + S + "http://salt.i2p/" + S + I + "salt_console.png" + S +
        "stats.i2p" + S + _x("I2P Network Statistics") + S + "http://stats.i2p/cgi-bin/dashboard.cgi" + S + I + "chart_line.png" + S +
        _x("I2P Technical Docs") + S + _x("Technical documentation") + S + "http://i2p-projekt.i2p/how" + S + I + "education.png" + S +
        _x("The Tin Hat") + S + _x("Privacy guides and tutorials") + S + "http://secure.thetinhat.i2p/" + S + I + "thetinhat.png" + S +
        _x("Trac Wiki") + S + S + "http://trac.i2p2.i2p/" + S + I + "billiard_marker.png" + S +
        //_x("Ugha's Wiki") + S + S + "http://ugha.i2p/" + S + I + "billiard_marker.png" + S +
        //"sponge.i2p" + S + _x("Seedless and the Robert BitTorrent applications") + S + "http://sponge.i2p/" + S + I + "user_astronaut.png" + S +
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
        return getChecked(PROP_OLDHOME);
    }

    public String getProxyStatus() {
        int port = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY);
        if (port <= 0)
            return _t("The HTTP proxy is not up");
        return "<img src=\"http://console.i2p/onepixel.png?" + _context.random().nextInt() + "\"" +
               " alt=\"" + _t("Your browser is not properly configured to use the HTTP proxy at {0}",
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

    private static final String SS = Character.toString(S);

    static Collection<App> buildApps(RouterContext ctx, String config) {
        String[] args = DataHelper.split(config, SS);
        Set<App> apps = new TreeSet<App>(new AppComparator());
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
        String[] args = DataHelper.split(config, SS);
        Set<App> apps = new TreeSet<App>(new AppComparator());
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

    private String renderApps(Collection<App> apps) {
        String website = _t("Web Server");
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=\"appgroup\">");
        PortMapper pm = _context.portMapper();
        for (App app : apps) {
            String url;
            if (app.name.equals(website) && app.url.equals("http://127.0.0.1:7658/")) {
                int port = pm.getPort(PortMapper.SVC_EEPSITE);
                int sslPort = pm.getPort(PortMapper.SVC_HTTPS_EEPSITE);
                if (port <= 0 && sslPort <= 0)
                    continue;
                // fixup eepsite link
                if (sslPort > 0) {
                    url = "https://" + pm.getActualHost(PortMapper.SVC_HTTPS_EEPSITE, "127.0.0.1") +
                      ':' + sslPort + '/';
                } else {
                    url = "http://" + pm.getActualHost(PortMapper.SVC_EEPSITE, "127.0.0.1") +
                      ':' + port + '/';
                }
            } else {
                url = app.url;
                // check for disabled webapps and other things
                if (url.equals("/dns")) {
                    if (!pm.isRegistered("susidns"))
                        continue;
                } else if (url.equals("/webmail")) {
                    if (!pm.isRegistered("susimail"))
                        continue;
                } else if (url.equals("/torrents")) {
                    if (!pm.isRegistered("i2psnark"))
                        continue;
                } else if (url.equals("/configplugins")) {
                    if (!PluginStarter.pluginsEnabled(_context))
                        continue;
                }
            }
            buf.append("\n<div class=\"app\">\n" +
                       "<div class=\"appimg\">" +
                       // usability: add tabindex -1 so we avoid 2 tabs per app
                       "<a href=\"").append(url).append("\" tabindex=\"-1\">" +
                       "<img alt=\"\" title=\"").append(app.desc).append("\" src=\"").append(app.icon).append("\"></a>" +
                       "</div>\n" +
                       "<table><tr><td>" +
                       "<div class=\"applabel\">" +
                       "<a href=\"").append(url).append("\" title=\"").append(app.desc).append("\">").append(app.name).append("</a>" +
                       "</div>" +
                       "</td></tr></table>\n" +
                       "</div>");
        }
        buf.append("</div>\n");
        return buf.toString();
    }

    private String renderConfig(Collection<App> apps) {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table class=\"homelinkedit\"><tr><th title=\"")
           .append(_t("Mark for deletion"))
           .append("\">")
           .append(_t("Remove"))
           .append("</th><th></th><th>")
           .append(_t("Name"))
           .append("</th><th>")
           .append(_t("URL"))
           .append("</th></tr>\n");
        for (App app : apps) {
            buf.append("<tr><td align=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"delete_")
               .append(app.name)
               .append("\" id=\"")
               .append(app.name)
               .append("\"></td><td align=\"center\">");
            if (app.icon != null) {
                buf.append("<img height=\"16\" alt=\"\" src=\"").append(app.icon).append("\">");
            }
            buf.append("</td><td align=\"left\"><label for=\"")
               .append(app.name)
               .append("\">")
               .append(DataHelper.escapeHTML(app.name))
               .append("</label></td><td align=\"left\"><a href=\"");
            String url = DataHelper.escapeHTML(app.url);
            buf.append(url)
               .append("\">");
            // truncate before escaping
            if (app.url.length() > 50)
                buf.append(DataHelper.escapeHTML(app.url.substring(0, 48))).append("&hellip;");
            else
                buf.append(url);
            buf.append("</a></td></tr>\n");
        }
        buf.append("<tr id=\"addnew\"><td colspan=\"2\" align=\"center\"><b>")
           .append(_t("Add")).append(":</b>" +
                   "</td><td align=\"left\"><input type=\"text\" name=\"nofilter_name\"></td>" +
                   "<td align=\"left\"><input type=\"text\" size=\"40\" name=\"nofilter_url\"></td></tr>");
        buf.append("</table>\n");
        return buf.toString();
    }

    /** ignore case, current locale */
    private static class AppComparator implements Comparator<App>, Serializable {
        public int compare(App l, App r) {
            return l.name.toLowerCase().compareTo(r.name.toLowerCase());
        }
    }
}
