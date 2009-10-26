package net.i2p.router.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.ObjectCounter;

public class NetDbRenderer {
    private RouterContext _context;

    public NetDbRenderer (RouterContext ctx) {
        _context = ctx;
    }

    private class LeaseSetComparator implements Comparator {
         public int compare(Object l, Object r) {
             Destination dl = ((LeaseSet)l).getDestination();
             Destination dr = ((LeaseSet)r).getDestination();
             boolean locall = _context.clientManager().isLocal(dl);
             boolean localr = _context.clientManager().isLocal(dr);
             if (locall && !localr) return -1;
             if (localr && !locall) return 1;
             return dl.calculateHash().toBase64().compareTo(dr.calculateHash().toBase64());
        }
    }

    private static class RouterInfoComparator implements Comparator {
         public int compare(Object l, Object r) {
             return ((RouterInfo)l).getIdentity().getHash().toBase64().compareTo(((RouterInfo)r).getIdentity().getHash().toBase64());
        }
    }

    public void renderRouterInfoHTML(Writer out, String routerPrefix) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<h2>" + _("Network Database RouterInfo Lookup") + "</h2>\n");
        if (".".equals(routerPrefix)) {
            renderRouterInfo(buf, _context.router().getRouterInfo(), true, true);
        } else {
            boolean notFound = true;
            Set routers = _context.netDb().getRouters();
            for (Iterator iter = routers.iterator(); iter.hasNext(); ) {
                RouterInfo ri = (RouterInfo)iter.next();
                Hash key = ri.getIdentity().getHash();
                if (key.toBase64().startsWith(routerPrefix)) {
                    renderRouterInfo(buf, ri, false, true);
                    notFound = false;
                }
            }
            if (notFound)
                buf.append(_("Router") + " ").append(routerPrefix).append(" " + _("not found in network database") );
        }
        out.write(buf.toString());
        out.flush();
    }

    public void renderStatusHTML(Writer out) throws IOException {
        renderStatusHTML(out, true);
    }

    public void renderLeaseSetHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<h2>" + _("Network Database Contents") + "</h2>\n");
        buf.append("<a href=\"netdb.jsp\">" + _("View") + " RouterInfo</a>");
        buf.append("<h3>LeaseSets</h3>\n");
        Set leases = new TreeSet(new LeaseSetComparator());
        leases.addAll(_context.netDb().getLeases());
        long now = _context.clock().now();
        for (Iterator iter = leases.iterator(); iter.hasNext(); ) {
            LeaseSet ls = (LeaseSet)iter.next();
            Destination dest = ls.getDestination();
            Hash key = dest.calculateHash();
            buf.append("<b>LeaseSet: ").append(key.toBase64());
            if (_context.clientManager().isLocal(dest)) {
                buf.append(" (<a href=\"tunnels.jsp#" + key.toBase64().substring(0,4) + "\">" + _("Local") + "</a> ");
                if (! _context.clientManager().shouldPublishLeaseSet(key))
                    buf.append(_("Unpublished") + " ");
                buf.append(_("Destination") + " ");
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
                if (in != null && in.getDestinationNickname() != null)
                    buf.append(in.getDestinationNickname());
                else
                    buf.append(dest.toBase64().substring(0, 6));
            } else {
                buf.append(" (" + _("Destination") + " ");
                String host = _context.namingService().reverseLookup(dest);
                if (host != null)
                    buf.append(host);
                else
                    buf.append(dest.toBase64().substring(0, 6));
            }
            buf.append(")</b><br>\n");
            long exp = ls.getEarliestLeaseDate()-now;
            if (exp > 0)
                buf.append("Expires in ").append(DataHelper.formatDuration(exp)).append("<br>\n");
            else
                buf.append("Expired ").append(DataHelper.formatDuration(0-exp)).append(" ago<br>\n");
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                buf.append("Lease ").append(i + 1).append(": " + _("Gateway") + " ");
                buf.append(_context.commSystem().renderPeerHTML(ls.getLease(i).getGateway()));
                buf.append(" " + _("Tunnel") + " ").append(ls.getLease(i).getTunnelId().getTunnelId()).append("<br>\n");
            }
            buf.append("<hr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        out.write(buf.toString());
        out.flush();
    }

    public void renderStatusHTML(Writer out, boolean full) throws IOException {
        int size = _context.netDb().getKnownRouters() * 512;
        if (full)
            size *= 4;
        StringBuilder buf = new StringBuilder(size);
        out.write("<h2>" + _("Network Database Contents") + " (<a href=\"netdb.jsp?l=1\">" + _("View") + " LeaseSets</a>)</h2>\n");
        if (!_context.netDb().isInitialized()) {
            buf.append("" + _("Not initialized") + "\n");
            out.write(buf.toString());
            out.flush();
            return;
        }
        
        Hash us = _context.routerHash();
        out.write("<a name=\"routers\" ></a><h3>" + _("Routers") + " (<a href=\"netdb.jsp");
        if (full)
            out.write("#routers\" >" + _("view without") + "");
        else
            out.write("?f=1#routers\" >" + _("view with") + "");
        out.write(" " + _("stats") + "</a>)</h3>\n");
        
        RouterInfo ourInfo = _context.router().getRouterInfo();
        renderRouterInfo(buf, ourInfo, true, true);
        out.write(buf.toString());
        buf.setLength(0);
        
        ObjectCounter<String> versions = new ObjectCounter();
        ObjectCounter<String> countries = new ObjectCounter();
        
        Set routers = new TreeSet(new RouterInfoComparator());
        routers.addAll(_context.netDb().getRouters());
        for (Iterator iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = (RouterInfo)iter.next();
            Hash key = ri.getIdentity().getHash();
            boolean isUs = key.equals(us);
            if (!isUs) {
                renderRouterInfo(buf, ri, false, full);
                out.write(buf.toString());
                buf.setLength(0);
                String routerVersion = ri.getOption("router.version");
                if (routerVersion != null)
                    versions.increment(routerVersion);
                String country = _context.commSystem().getCountry(key);
                if(country != null)
                    countries.increment(country);
            }
        }
            
        buf.append("<table border=\"0\" cellspacing=\"30\"><tr><td>");
        List<String> versionList = new ArrayList(versions.objects());
        if (versionList.size() > 0) {
            Collections.sort(versionList, Collections.reverseOrder());
            buf.append("<table>\n");
            buf.append("<tr><th>" + _("Version") + "</th><th>" + _("Count") + "</th></tr>\n");
            for (String routerVersion : versionList) {
                int num = versions.count(routerVersion);
                buf.append("<tr><td align=\"center\">").append(DataHelper.stripHTML(routerVersion));
                buf.append("</td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }
        buf.append("</td><td>");
        out.write(buf.toString());
        buf.setLength(0);
            
        List<String> countryList = new ArrayList(countries.objects());
        if (countryList.size() > 0) {
            Collections.sort(countryList);
            buf.append("<table>\n");
            buf.append("<tr><th align=\"left\">" + _("Country") + "</th><th>" + _("Count") + "</th></tr>\n");
            for (String country : countryList) {
                int num = countries.count(country);
                buf.append("<tr><td><img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase()).append("\"");
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ");
                buf.append(_context.commSystem().getCountryName(country));
                buf.append("</td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }
        buf.append("</td></tr></table>");
        out.write(buf.toString());
        out.flush();
    }
    
    /**
     *  Be careful to use stripHTML for any displayed routerInfo data
     *  to prevent vulnerabilities
     */
    private void renderRouterInfo(StringBuilder buf, RouterInfo info, boolean isUs, boolean full) {
        String hash = info.getIdentity().getHash().toBase64();
        buf.append("<table><tr><th><a name=\"").append(hash.substring(0, 6)).append("\" ></a>");
        if (isUs) {
            buf.append("<a name=\"our-info\" ></a><b>" + _("Our info") + ": ").append(hash).append("</b></th></tr><tr><td>\n");
        } else {
            buf.append("<b>" + _("Peer info for") + ":</b> ").append(hash).append("\n");
            if (full) {
                buf.append("[<a href=\"netdb.jsp\" >Back</a>]</th></tr><td>\n");
            } else {
                buf.append("[<a href=\"netdb.jsp?r=").append(hash.substring(0, 6)).append("\" >Full entry</a>]</th></tr><td>\n");
            }
        }
        
        long age = _context.clock().now() - info.getPublished();
        if (isUs && _context.router().isHidden())
            buf.append("<b>" + _("Hidden") + ", " + _("Updated") + ":</b> ").append(DataHelper.formatDuration(age)).append(" " + _("ago") + "<br>\n");
        else if (age > 0)
            buf.append("<b>" + _("Published") + ":</b> ").append(DataHelper.formatDuration(age)).append(" " + _("ago") + "<br>\n");
        else
            buf.append("<b>" + _("Published") + ":</b> in ").append(DataHelper.formatDuration(0-age)).append("???<br>\n");
        buf.append("<b>" + _("Address(es)") + ":</b> ");
        String country = _context.commSystem().getCountry(info.getIdentity().getHash());
        if(country != null) {
            buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase()).append("\"");
            buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ");
        }
        for (Iterator iter = info.getAddresses().iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            buf.append(DataHelper.stripHTML(addr.getTransportStyle())).append(": ");
            for (Iterator optIter = addr.getOptions().keySet().iterator(); optIter.hasNext(); ) {
                String name = (String)optIter.next();
                String val = addr.getOptions().getProperty(name);
                buf.append('[').append(DataHelper.stripHTML(name)).append('=').append(DataHelper.stripHTML(val)).append("] ");
            }
        }
        buf.append("</td></tr>\n");
        if (full) {
            buf.append("<tr><td>" + _("Stats") + ": <br><code>\n");
            for (Iterator iter = info.getOptions().keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = info.getOption(key);
                buf.append(DataHelper.stripHTML(key)).append(" = ").append(DataHelper.stripHTML(val)).append("<br>\n");
            }
            buf.append("</code></td></tr>\n");
        } else {
        }
        buf.append("</td></tr>\n");
    }

    /** translate a string */
    private String _(String s) {
        return Messages.getString(s, _context);
    }
}
