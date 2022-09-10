package net.i2p.router.web.helpers;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger;         // debug
import java.text.Collator;
import java.text.DecimalFormat;      // debug
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.PublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.util.HashDistance;   // debug
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import static net.i2p.router.sybil.Util.biLog2;
import net.i2p.router.transport.GeoIP;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.router.web.WebAppStarter;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

class NetDbRenderer {
    private final RouterContext _context;

    public NetDbRenderer (RouterContext ctx) {
        _context = ctx;
    }

    /**
     *  Inner class, can't be Serializable
     */
    private class LeaseSetComparator implements Comparator<LeaseSet> {
         public int compare(LeaseSet l, LeaseSet r) {
             Hash dl = l.getHash();
             Hash dr = r.getHash();
             boolean locall = _context.clientManager().isLocal(dl);
             boolean localr = _context.clientManager().isLocal(dr);
             if (locall && !localr) return -1;
             if (localr && !locall) return 1;
             return dl.toBase32().compareTo(dr.toBase32());
        }
    }

    /** for debugging @since 0.7.14 */
    private static class LeaseSetRoutingKeyComparator implements Comparator<LeaseSet>, Serializable {
         private final Hash _us;
         public LeaseSetRoutingKeyComparator(Hash us) {
             _us = us;
         }
         public int compare(LeaseSet l, LeaseSet r) {
             return HashDistance.getDistance(_us, l.getRoutingKey()).compareTo(HashDistance.getDistance(_us, r.getRoutingKey()));
        }
    }

    private static class RouterInfoComparator implements Comparator<RouterInfo>, Serializable {
         public int compare(RouterInfo l, RouterInfo r) {
             return l.getIdentity().getHash().toBase64().compareTo(r.getIdentity().getHash().toBase64());
        }
    }

    /**
     *  One String must be non-null
     *
     *  @param page zero-based
     *  @param routerPrefix may be null. "." for our router only
     *  @param version may be null
     *  @param country may be null
     *  @param family may be null
     */
    public void renderRouterInfoHTML(Writer out, int pageSize, int page,
                                     String routerPrefix, String version,
                                     String country, String family, String caps,
                                     String ip, String sybil, int port, SigType type, EncType etype,
                                     String mtu, String ipv6, String ssucaps,
                                     String tr, int cost) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        List<Hash> sybils = sybil != null ? new ArrayList<Hash>(128) : null;
        if (".".equals(routerPrefix)) {
            buf.append("<table><tr><td class=\"infohelp\">")
               .append(_t("Never reveal your router identity to anyone, as it is uniquely linked to your IP address in the network database."))
               .append("</td></tr></table>");
            renderRouterInfo(buf, _context.router().getRouterInfo(), true, true);
        } else if (routerPrefix != null && routerPrefix.length() >= 44) {
            // easy way, full hash
            byte[] h = Base64.decode(routerPrefix);
            if (h != null && h.length == Hash.HASH_LENGTH) {
                Hash hash = new Hash(h);
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(hash);
                boolean banned = false;
                if (ri == null) {
                    banned = _context.banlist().isBanlisted(hash);
                    if (!banned) {
                        // remote lookup
                        LookupWaiter lw = new LookupWaiter();
                        _context.netDb().lookupRouterInfo(hash, lw, lw, 8*1000);
                        // just wait right here in the middle of the rendering, sure
                        synchronized(lw) {
                            try { lw.wait(9*1000); } catch (InterruptedException ie) {}
                        }
                        ri = _context.netDb().lookupRouterInfoLocally(hash);
                    }
                }
                if (ri != null) {
                   renderRouterInfo(buf, ri, false, true);
                } else {
                    buf.append("<div class=\"netdbnotfound\">");
                    buf.append(_t("Router")).append(' ');
                    buf.append(routerPrefix);
                    buf.append(' ').append(banned ? "is banned" : _t("not found in network database"));
                    buf.append("</div>");
                }
            } else {
                buf.append("<div class=\"netdbnotfound\">");
                buf.append("Bad Base64 router hash").append(' ');
                buf.append(DataHelper.escapeHTML(routerPrefix));
                buf.append("</div>");
            }
        } else {
            StringBuilder ubuf = new StringBuilder();
            if (routerPrefix != null)
                ubuf.append("&amp;r=").append(routerPrefix);
            if (version != null)
                ubuf.append("&amp;v=").append(version);
            if (country != null)
                ubuf.append("&amp;c=").append(country);
            if (family != null)
                ubuf.append("&amp;fam=").append(family);
            if (caps != null)
                ubuf.append("&amp;caps=").append(caps);
            if (tr != null)
                ubuf.append("&amp;tr=").append(tr);
            if (type != null)
                ubuf.append("&amp;type=").append(type);
            if (etype != null)
                ubuf.append("&amp;etype=").append(etype);
            if (ip != null)
                ubuf.append("&amp;ip=").append(ip);
            if (port != 0)
                ubuf.append("&amp;port=").append(port);
            if (mtu != null)
                ubuf.append("&amp;mtu=").append(mtu);
            if (ipv6 != null)
                ubuf.append("&amp;ipv6=").append(ipv6);
            if (ssucaps != null)
                ubuf.append("&amp;ssucaps=").append(ssucaps);
            if (cost != 0)
                ubuf.append("&amp;cost=").append(cost);
            if (sybil != null)
                ubuf.append("&amp;sybil=").append(sybil);
            if (page > 0) {
                buf.append("<div class=\"netdbnotfound\">" +
                           "<a href=\"/netdb?pg=").append(page)
                   .append("&amp;ps=").append(pageSize).append(ubuf).append("\">");
                buf.append(_t("Previous Page"));
                buf.append("</a>&nbsp;&nbsp;&nbsp;");
                buf.append(_t("Page")).append(' ').append(page + 1);
                buf.append("</div>");
            }
            boolean notFound = true;
            Set<RouterInfo> routers = _context.netDb().getRouters();
            int ipMode = 0;
            String ipArg = ip;  // save for error message
            if (ip != null) {
                if (ip.endsWith("/24")) {
                    ipMode = 1;
                } else if (ip.endsWith("/16")) {
                    ipMode = 2;
                } else if (ip.endsWith("/8")) {
                    ipMode = 3;
                } else if (ip.indexOf(':') > 0) {
                    ipMode = 4;
                }
                if (ipMode > 0 && ipMode < 4) {
                    for (int i = 0; i < ipMode; i++) {
                        int last = ip.substring(0, ip.length() - 1).lastIndexOf('.');
                        if (last > 0)
                            ip = ip.substring(0, last + 1);
                    }
                }
            }
            String familyArg = family;  // save for error message
            if (family != null)
                family = family.toLowerCase(Locale.US);
            int toSkip = pageSize * page;
            int skipped = 0;
            int written = 0;
            boolean morePages = false;
            outerloop:
            for (RouterInfo ri : routers) {
                Hash key = ri.getIdentity().getHash();
                if ((routerPrefix != null && key.toBase64().startsWith(routerPrefix)) ||
                    (version != null && version.equals(ri.getVersion())) ||
                    (country != null && country.equals(_context.commSystem().getCountry(key))) ||
                    // 'O' will catch PO and XO also
                    (caps != null && hasCap(ri, caps)) ||
                    (type != null && type == ri.getIdentity().getSigType()) ||
                    (etype != null && etype == ri.getIdentity().getEncType())) {
                    if (skipped < toSkip) {
                        skipped++;
                        continue;
                    }
                    if (written++ >= pageSize) {
                        morePages = true;
                        break;
                    }
                    renderRouterInfo(buf, ri, false, true);
                    if (sybil != null)
                        sybils.add(key);
                    notFound = false;
                } else if (tr != null) {
                    boolean found;
                    if (tr.equals("NTCP_1")) {
                        RouterAddress ra = ri.getTargetAddress("NTCP");
                        found = ra != null && ra.getOption("v") == null;
                    } else if (tr.equals("NTCP_2")) {
                        RouterAddress ra = ri.getTargetAddress("NTCP");
                        found = ra != null && ra.getOption("v") != null;
                    } else if (tr.equals("SSU_1")) {
                        RouterAddress ra = ri.getTargetAddress("SSU");
                        found = ra != null && ra.getOption("v") == null;
                    } else if (tr.equals("SSU_2")) {
                        RouterAddress ra = ri.getTargetAddress("SSU");
                        found = ra != null && ra.getOption("v") != null;
                    } else {
                        RouterAddress ra = ri.getTargetAddress(tr);
                        found = ra != null;
                    }
                    if (!found)
                        continue;
                    if (skipped < toSkip) {
                        skipped++;
                        continue;
                    }
                    if (written++ >= pageSize) {
                        morePages = true;
                        break;
                    }
                    renderRouterInfo(buf, ri, false, true);
                    if (sybil != null)
                        sybils.add(key);
                    notFound = false;
                } else if (family != null) {
                    String rifam = ri.getOption("family");
                    if (rifam != null && rifam.toLowerCase(Locale.US).contains(family)) {
                        if (skipped < toSkip) {
                            skipped++;
                            continue;
                        }
                        if (written++ >= pageSize) {
                            morePages = true;
                            break outerloop;
                        }
                        renderRouterInfo(buf, ri, false, true);
                        if (sybil != null)
                            sybils.add(key);
                        notFound = false;
                    }
                } else if (ip != null) {
                    for (RouterAddress ra : ri.getAddresses()) {
                        if (ipMode == 0) {
                            if (ip.equals(ra.getHost())) {
                                if (skipped < toSkip) {
                                    skipped++;
                                    break;
                                }
                                if (written++ >= pageSize) {
                                    morePages = true;
                                    break outerloop;
                                }
                                renderRouterInfo(buf, ri, false, true);
                                if (sybil != null)
                                    sybils.add(key);
                                notFound = false;
                                break;
                            }
                        } else {
                            String host = ra.getHost();
                            if (host != null && host.startsWith(ip)) {
                                if (skipped < toSkip) {
                                    skipped++;
                                    break;
                                }
                                if (written++ >= pageSize) {
                                    morePages = true;
                                    break outerloop;
                                }
                                renderRouterInfo(buf, ri, false, true);
                                if (sybil != null)
                                    sybils.add(key);
                                notFound = false;
                                break;
                            }
                        }
                    }
                } else if (port != 0) {
                    for (RouterAddress ra : ri.getAddresses()) {
                        if (port == ra.getPort()) {
                            if (skipped < toSkip) {
                                skipped++;
                                break;
                            }
                            if (written++ >= pageSize) {
                                morePages = true;
                                break outerloop;
                            }
                            renderRouterInfo(buf, ri, false, true);
                            if (sybil != null)
                                sybils.add(key);
                            notFound = false;
                            break;
                        }
                    }
                } else if (mtu != null) {
                    for (RouterAddress ra : ri.getAddresses()) {
                        if (mtu.equals(ra.getOption("mtu"))) {
                            if (skipped < toSkip) {
                                skipped++;
                                break;
                            }
                            if (written++ >= pageSize) {
                                morePages = true;
                                break outerloop;
                            }
                            renderRouterInfo(buf, ri, false, true);
                            if (sybil != null)
                                sybils.add(key);
                            notFound = false;
                            break;
                        }
                    }
                } else if (ipv6 != null) {
                    for (RouterAddress ra : ri.getAddresses()) {
                        String host = ra.getHost();
                        if (host != null && host.startsWith(ipv6)) {
                            if (skipped < toSkip) {
                                skipped++;
                                break;
                            }
                            if (written++ >= pageSize) {
                                morePages = true;
                                break outerloop;
                            }
                            renderRouterInfo(buf, ri, false, true);
                            if (sybil != null)
                                sybils.add(key);
                            notFound = false;
                            break;
                        }
                    }
                } else if (ssucaps != null) {
                    for (RouterAddress ra : ri.getAddresses()) {
                        if (!"SSU".equals(ra.getTransportStyle()))
                            continue;
                        String racaps = ra.getOption("caps");
                        if (racaps == null)
                            continue;
                        if (racaps.contains(ssucaps)) {
                            if (skipped < toSkip) {
                                skipped++;
                                break;
                            }
                            if (written++ >= pageSize) {
                                morePages = true;
                                break outerloop;
                            }
                            renderRouterInfo(buf, ri, false, true);
                            if (sybil != null)
                                sybils.add(key);
                            notFound = false;
                            break;
                        }
                    }
                } else if (cost != 0) {
                    for (RouterAddress ra : ri.getAddresses()) {
                        if (cost == ra.getCost()) {
                            if (skipped < toSkip) {
                                skipped++;
                                break;
                            }
                            if (written++ >= pageSize) {
                                morePages = true;
                                break outerloop;
                            }
                            renderRouterInfo(buf, ri, false, true);
                            if (sybil != null)
                                sybils.add(key);
                            notFound = false;
                            break;
                        }
                    }
                }
            }
            if (notFound) {
                buf.append("<div class=\"netdbnotfound\">");
                buf.append(_t("Router")).append(' ');
                if (routerPrefix != null)
                    buf.append(routerPrefix).append(' ');
                if (version != null)
                    buf.append(_t("Version")).append(' ').append(version).append(' ');
                if (country != null)
                    buf.append(_t("Country")).append(' ').append(country).append(' ');
                if (familyArg != null)
                    buf.append(_t("Family")).append(' ').append(familyArg).append(' ');
                if (ipArg != null)
                    buf.append("IP ").append(ipArg).append(' ');
                if (ipv6 != null)
                    buf.append("IP ").append(ipv6).append(' ');
                if (port != 0)
                    buf.append(_t("Port")).append(' ').append(port).append(' ');
                if (mtu != null)
                    buf.append(_t("MTU")).append(' ').append(mtu).append(' ');
                if (cost != 0)
                    buf.append("Cost ").append(cost).append(' ');
                if (type != null)
                    buf.append("Type ").append(type).append(' ');
                if (etype != null)
                    buf.append("Type ").append(etype).append(' ');
                if (caps != null)
                    buf.append("Caps ").append(caps).append(' ');
                if (ssucaps != null)
                    buf.append("Caps ").append(ssucaps).append(' ');
                if (tr != null)
                    buf.append(_t("Transport")).append(' ').append(tr).append(' ');
                buf.append(_t("not found in network database"));
                buf.append("</div>");
            } else if (page > 0 || morePages) {
                buf.append("<div class=\"netdbnotfound\">");
                if (page > 0) {
                    buf.append("<a href=\"/netdb?pg=").append(page)
                       .append("&amp;ps=").append(pageSize).append(ubuf).append("\">");
                    buf.append(_t("Previous Page"));
                    buf.append("</a>&nbsp;&nbsp;&nbsp;");
                }
                buf.append(_t("Page")).append(' ').append(page + 1);
                if (morePages) {
                    buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?pg=").append(page + 2)
                       .append("&amp;ps=").append(pageSize).append(ubuf).append("\">");
                    buf.append(_t("Next Page"));
                    buf.append("</a>");
                }
                buf.append("</div>");
            }
        }
        out.write(buf.toString());
        out.flush();
        if (sybil != null)
            SybilRenderer.renderSybilHTML(out, _context, sybils, sybil);
    }

    /**
     *  @since 0.9.48
     */
    private class LookupWaiter extends JobImpl {
        public LookupWaiter() { super(_context); }
        public void runJob() {
            synchronized(this) {
                notifyAll();
            }
        }
        public String getName() { return "Console netdb lookup"; }
    }

    /**
     *  Special handling for 'O' cap
     *  @param caps non-null
     *  @since 0.9.38
     */
    private static boolean hasCap(RouterInfo ri, String caps) {
        String ricaps = ri.getCapabilities();
        if (caps.equals("O")) {
            return ricaps.contains(caps) &&
                   !ricaps.contains("P") &&
                   !ricaps.contains("X");
        } else {
            return ricaps.contains(caps);
        }
    }

    /**
     *  @param debug @since 0.7.14 sort by distance from us, display
     *               median distance, and other stuff, useful when floodfill
     */
    public void renderLeaseSetHTML(Writer out, boolean debug) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        if (debug)
            buf.append("<p id=\"debugmode\">Debug mode - Sorted by hash distance, closest first</p>\n");
        Hash ourRKey;
        Set<LeaseSet> leases;
        DecimalFormat fmt;
        if (debug) {
            ourRKey = _context.routerHash();
            leases = new TreeSet<LeaseSet>(new LeaseSetRoutingKeyComparator(ourRKey));
            fmt = new DecimalFormat("#0.00");
        } else {
            ourRKey = null;
            leases = new TreeSet<LeaseSet>(new LeaseSetComparator());
            fmt = null;
        }
        leases.addAll(_context.netDb().getLeases());
        int medianCount = 0;
        int rapCount = 0;
        BigInteger median = null;
        int c = 0;


        // Summary
        FloodfillNetworkDatabaseFacade netdb = (FloodfillNetworkDatabaseFacade)_context.netDb();
        if (debug) {
            buf.append("<table id=\"leasesetdebug\">\n");
        } else {
            buf.append("<table id=\"leasesetsummary\">\n");
        }
        buf.append("<tr><th colspan=\"3\">Leaseset Summary</th>")
           .append("<th><a href=\"/configadvanced\" title=\"").append(_t("Manually Configure Floodfill Participation")).append("\">[")
           .append(_t("Configure Floodfill Participation"))
           .append("]</a></th></tr>\n")
           .append("<tr><td><b>Total Leasesets:</b></td><td colspan=\"3\">").append(leases.size()).append("</td></tr>\n");
        if (debug) {
            buf.append("<tr><td><b>Published (RAP) Leasesets:</b></td><td colspan=\"3\">").append(netdb.getKnownLeaseSets()).append("</td></tr>\n")
               .append("<tr><td><b>Mod Data:</b></td><td>").append(DataHelper.getUTF8(_context.routerKeyGenerator().getModData())).append("</td>")
               .append("<td><b>Last Changed:</b></td><td>").append(DataHelper.formatTime(_context.routerKeyGenerator().getLastChanged())).append("</td></tr>\n")
               .append("<tr><td><b>Next Mod Data:</b></td><td>").append(DataHelper.getUTF8(_context.routerKeyGenerator().getNextModData())).append("</td>")
               .append("<td><b>Change in:</b></td><td>").append(DataHelper.formatDuration(_context.routerKeyGenerator().getTimeTillMidnight())).append("</td></tr>\n");
        }
        int ff = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
        buf.append("<tr><td><b>Known Floodfills:</b></td><td colspan=\"3\">").append(ff).append("</td></tr>\n")
           .append("<tr><td><b>Currently Floodfill?</b></td><td colspan=\"3\">").append(netdb.floodfillEnabled() ? "yes" : "no").append("</td></tr>\n");
        buf.append("</table>\n");

        if (leases.isEmpty()) {
            //if (!debug)
            //    buf.append("<div id=\"noleasesets\"><i>").append(_t("No Leasesets currently active.")).append("</i></div>");
        } else {
          if (debug) {
            // Find the center of the RAP leasesets
            for (LeaseSet ls : leases) {
                if (ls.getReceivedAsPublished())
                    rapCount++;
            }
            medianCount = rapCount / 2;
          }

          boolean linkSusi = _context.portMapper().isRegistered("susidns");
          long now = _context.clock().now();
          buf.append("<div class=\"leasesets_container\">");
          for (LeaseSet ls : leases) {
            // warning - will be null for non-local encrypted
            Destination dest = ls.getDestination();
            Hash key = ls.getHash();
            buf.append("<table class=\"leaseset\">\n")
               .append("<tr><th><b>").append(_t("LeaseSet")).append(":</b>&nbsp;<code>").append(key.toBase64()).append("</code>");
            int type = ls.getType();
            if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 || _context.keyRing().get(key) != null)
                buf.append(" <b>(").append(_t("Encrypted")).append(")</b>");
            buf.append("</th>");
            if (_context.clientManager().isLocal(key)) {
                buf.append("<th><a href=\"tunnels#" + key.toBase64().substring(0,4) + "\">" + _t("Local") + "</a> ");
                boolean unpublished = ! _context.clientManager().shouldPublishLeaseSet(key);
                if (unpublished)
                    buf.append("<b>").append(_t("Unpublished")).append("</b> ");
                buf.append("<b>").append(_t("Destination")).append(":</b> ");
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
                if (in != null && in.getDestinationNickname() != null)
                    buf.append(DataHelper.escapeHTML(in.getDestinationNickname()));
                else
                    buf.append(dest.toBase64().substring(0, 6));
                buf.append("</th></tr>\n");
                // we don't show a b32 or addressbook links if encrypted
                if (type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                    buf.append("<tr><td");
                    // If the dest is published but not in the addressbook, an extra
                    // <td> is appended with an "Add to addressbook" link, so this
                    // <td> should not span 2 columns.
                    String host = null;
                    if (!unpublished) {
                        host = _context.namingService().reverseLookup(dest);
                    }
                    if (unpublished || host != null || !linkSusi) {
                        buf.append(" colspan=\"2\"");
                    }
                    buf.append(">");
                    String b32 = key.toBase32();
                    buf.append("<a href=\"http://").append(b32).append("\">").append(b32).append("</a></td>");
                    if (linkSusi && !unpublished && host == null) {
                        buf.append("<td class=\"addtobook\" colspan=\"2\">").append("<a title=\"").append(_t("Add to address book"))
                           .append("\" href=\"/susidns/addressbook.jsp?book=private&amp;destination=")
                           .append(dest.toBase64()).append("#add\">").append(_t("Add to local address book")).append("</a></td>");
                    } // else probably a client
                }
            } else {
                buf.append("<th><b>").append(_t("Destination")).append(":</b> ");
                String host = (dest != null) ? _context.namingService().reverseLookup(dest) : null;
                if (host != null) {
                    buf.append("<a href=\"http://").append(host).append("/\">").append(host).append("</a></th>");
                } else {
                    String b32 = key.toBase32();
                    buf.append("<code>");
                    if (dest != null)
                        buf.append(dest.toBase64().substring(0, 6));
                    else
                        buf.append("n/a");
                    buf.append("</code></th>" +
                               "</tr>\n<tr><td");
                    if (!linkSusi)
                        buf.append(" colspan=\"2\"");
                    buf.append("><a href=\"http://").append(b32).append("\">").append(b32).append("</a></td>\n");
                    if (linkSusi && dest != null) {
                       buf.append("<td class=\"addtobook\"><a title=\"").append(_t("Add to address book"))
                       .append("\" href=\"/susidns/addressbook.jsp?book=private&amp;destination=")
                       .append(dest.toBase64()).append("#add\">").append(_t("Add to local address book")).append("</a></td>");
                    }
                }
            }
            long exp;
            if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
                exp = ls.getLatestLeaseDate() - now;
            } else {
                LeaseSet2 ls2 = (LeaseSet2) ls;
                long pub = now - ls2.getPublished();
                buf.append("</tr>\n<tr><td colspan=\"2\">\n<b>")
                   .append(_t("Published {0} ago", DataHelper.formatDuration2(pub)))
                   .append("</b>");
                exp = ((LeaseSet2)ls).getExpires()-now;
            }
            buf.append("</tr>\n<tr><td colspan=\"2\">\n<b>");
            if (exp > 0)
                buf.append(_t("Expires in {0}", DataHelper.formatDuration2(exp)));
            else
                buf.append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exp)));
            buf.append("</b></td></tr>\n");
            if (debug) {
                buf.append("<tr><td colspan=\"2\">");
                buf.append("<b>RAP?</b> ").append(ls.getReceivedAsPublished());
                buf.append("&nbsp;&nbsp;<b>RAR?</b> ").append(ls.getReceivedAsReply());
                BigInteger dist = HashDistance.getDistance(ourRKey, ls.getRoutingKey());
                if (ls.getReceivedAsPublished()) {
                    if (c++ == medianCount)
                        median = dist;
                }
                buf.append("&nbsp;&nbsp;<b>Distance: </b>").append(fmt.format(biLog2(dist)));
                buf.append("&nbsp;&nbsp;<b>Type: </b>").append(type);
                if (type != DatabaseEntry.KEY_TYPE_LEASESET) {
                    LeaseSet2 ls2 = (LeaseSet2) ls;
                    buf.append("&nbsp;&nbsp;<b>Unpublished? </b>").append(ls2.isUnpublished());
                    if (ls2.isOffline()) {
                        buf.append("&nbsp;&nbsp;<b>Offline signed: </b>");
                        exp = ls2.getTransientExpiration() - now;
                        if (exp > 0)
                            buf.append(_t("Expires in {0}", DataHelper.formatDuration2(exp)));
                        else
                            buf.append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exp)));
                        buf.append("&nbsp;&nbsp;<b>Type: </b>").append(ls2.getTransientSigningKey().getType());
                    }
                }
                buf.append("</td></tr>\n<tr><td colspan=\"2\">");
                //buf.append(dest.toBase32()).append("<br>");
                buf.append("<b>Signature type:</b> ");
                if (dest != null && type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                    buf.append(dest.getSigningPublicKey().getType());
                } else {
                    // encrypted, show blinded key type
                    buf.append(ls.getSigningKey().getType());
                }
                if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
                    buf.append("</td></tr>\n<tr><td colspan=\"2\"><b>Encryption Key:</b> ELGAMAL_2048 ")
                       .append(ls.getEncryptionKey().toBase64().substring(0, 20))
                       .append("&hellip;");
                } else if (type == DatabaseEntry.KEY_TYPE_LS2) {
                    LeaseSet2 ls2 = (LeaseSet2) ls;
                    for (PublicKey pk : ls2.getEncryptionKeys()) {
                        buf.append("</td></tr>\n<tr><td colspan=\"2\"><b>Encryption Key:</b> ");
                        EncType etype = pk.getType();
                        if (etype != null)
                            buf.append(etype);
                        else
                            buf.append("Unsupported type ").append(pk.getUnknownTypeCode());
                        buf.append(' ')
                           .append(pk.toBase64().substring(0, 20))
                           .append("&hellip;");
                    }
                }
                buf.append("</td></tr>\n<tr><td colspan=\"2\">");
                buf.append("<b>Routing Key:</b> ").append(ls.getRoutingKey().toBase64());
                buf.append("</td></tr>");

            }
            buf.append("<tr><td colspan=\"2\"><ul class=\"netdb_leases\">");
            boolean isMeta = ls.getType() == DatabaseEntry.KEY_TYPE_META_LS2;
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                Lease lease = ls.getLease(i);
                buf.append("<li><b>").append(_t("Lease")).append(' ').append(i + 1).append(":</b> <span class=\"tunnel_peer\">");
                buf.append(_context.commSystem().renderPeerHTML(lease.getGateway()));
                buf.append("</span> ");
                if (!isMeta) {
                    buf.append("<span class=\"netdb_tunnel\">").append(_t("Tunnel")).append(" <span class=\"tunnel_id\">")
                       .append(lease.getTunnelId().getTunnelId()).append("</span></span> ");
                }
                if (debug) {
                    long exl = lease.getEndTime() - now;
                    if (exl > 0)
                        buf.append("<b class=\"netdb_expiry\">").append(_t("Expires in {0}", DataHelper.formatDuration2(exl))).append("</b>");
                    else
                        buf.append("<b class=\"netdb_expiry\">").append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exl))).append("</b>");
                }
                buf.append("</li>");
            }
            buf.append("</ul></td></tr>\n");
            buf.append("</table>\n");
            out.write(buf.toString());
            buf.setLength(0);
          } // for each
          if (debug) {
              buf.append("<table id=\"leasesetdebug\"><tr><td><b>Network data (only valid if floodfill):</b></td><td colspan=\"3\">");
              //buf.append("</b></p><p><b>Center of Key Space (router hash): " + ourRKey.toBase64());
              if (median != null) {
                  double log2 = biLog2(median);
                  buf.append("</td></tr>")
                     .append("<tr><td><b>Median distance (bits):</b></td><td colspan=\"3\">").append(fmt.format(log2)).append("</td></tr>\n");
                  // 2 for 4 floodfills... -1 for median
                  // this can be way off for unknown reasons
                  int total = (int) Math.round(Math.pow(2, 2 + 256 - 1 - log2));
                  buf.append("<tr><td><b>Estimated total floodfills:</b></td><td colspan=\"3\">").append(total).append("</td></tr>\n");
                  buf.append("<tr><td><b>Estimated total leasesets:</b></td><td colspan=\"3\">").append(total * rapCount / 4);
              } else {
                  buf.append("<i>Not floodfill or no data.</i>");
              }
              buf.append("</td></tr></table>\n");
          } // median table
          buf.append("</div>");
        }  // !empty
        out.write(buf.toString());
        out.flush();
    }

    /**
     *  @param mode 0: charts only; 1: full routerinfos; 2: abbreviated routerinfos
     */
    public void renderStatusHTML(Writer out, int pageSize, int page, int mode) throws IOException {
        if (!_context.netDb().isInitialized()) {
            out.write("<div id=\"notinitialized\">");
            out.write(_t("Not initialized"));
            out.write("</div>");
            out.flush();
            return;
        }
        Log log = _context.logManager().getLog(NetDbRenderer.class);
        long start = System.currentTimeMillis();

        boolean full = mode == 1;
        boolean shortStats = mode == 2;
        boolean showStats = full || shortStats;  // this means show the router infos
        Hash us = _context.routerHash();

        Set<RouterInfo> routers = new TreeSet<RouterInfo>(new RouterInfoComparator());
        routers.addAll(_context.netDb().getRouters());
        int toSkip = pageSize * page;
        boolean nextpg = routers.size() > toSkip + pageSize;
        StringBuilder buf = new StringBuilder(8192);
        if (showStats && (page > 0 || nextpg)) {
            buf.append("<div class=\"netdbnotfound\">");
            if (page > 0) {
                buf.append("<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page)
                   .append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Previous Page"));
                buf.append("</a>&nbsp;&nbsp;&nbsp;");
            }
            buf.append(_t("Page")).append(' ').append(page + 1);
            if (nextpg) {
                buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page + 2)
                   .append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Next Page"));
                buf.append("</a>");
            }
            buf.append("</div>");
        }
        if (showStats && page == 0) {
            RouterInfo ourInfo = _context.router().getRouterInfo();
            renderRouterInfo(buf, ourInfo, true, true);
            out.write(buf.toString());
            buf.setLength(0);
        }

        ObjectCounter<String> versions = new ObjectCounter<String>();
        ObjectCounter<String> countries = new ObjectCounter<String>();
        int[] transportCount = new int[TNAMES.length];

        int skipped = 0;
        int written = 0;
        boolean morePages = false;
        for (RouterInfo ri : routers) {
            Hash key = ri.getIdentity().getHash();
            boolean isUs = key.equals(us);
            if (!isUs) {
                if (showStats) {
                    if (skipped < toSkip) {
                        skipped++;
                        continue;
                    }
                    if (written++ >= pageSize) {
                        morePages = true;
                        break;
                    }
                    renderRouterInfo(buf, ri, false, full);
                    out.write(buf.toString());
                    buf.setLength(0);
                }
                String routerVersion = ri.getOption("router.version");
                if (routerVersion != null)
                    versions.increment(routerVersion);
                String country = _context.commSystem().getCountry(key);
                if(country != null)
                    countries.increment(country);
                transportCount[classifyTransports(ri)]++;
            }
        }
        if (showStats && (page > 0 || morePages)) {
            buf.append("<div class=\"netdbnotfound\">");
            if (page > 0) {
                buf.append("<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page).append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Previous Page"));
                buf.append("</a>&nbsp;&nbsp;&nbsp;");
            }
            buf.append(_t("Page")).append(' ').append(page + 1);
            if (morePages) {
                buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page + 2).append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Next Page"));
                buf.append("</a>");
            }
            buf.append("</div>");
        }
        if (log.shouldWarn()) {
            long end = System.currentTimeMillis();
            log.warn("part 1 took " + (end - start));
            start = end;
        }

     //
     // don't bother to reindent
     //
     if (!showStats) {

        // the summary table
        buf.append("<table id=\"netdboverview\" border=\"0\" cellspacing=\"30\"><tr><th colspan=\"3\">")
           .append(_t("Network Database Router Statistics"))
           .append("</th></tr><tr><td style=\"vertical-align: top;\">");
        // versions table
        List<String> versionList = new ArrayList<String>(versions.objects());
        if (!versionList.isEmpty()) {
            Collections.sort(versionList, Collections.reverseOrder(new VersionComparator()));
            buf.append("<table id=\"netdbversions\">\n");
            buf.append("<tr><th>" + _t("Version") + "</th><th>" + _t("Count") + "</th></tr>\n");
            for (String routerVersion : versionList) {
                int num = versions.count(routerVersion);
                String ver = DataHelper.stripHTML(routerVersion);
                buf.append("<tr><td align=\"center\"><a href=\"/netdb?v=").append(ver).append("\">").append(ver);
                buf.append("</a></td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }
        buf.append("</td><td style=\"vertical-align: top;\">");
        out.write(buf.toString());
        buf.setLength(0);
        if (log.shouldWarn()) {
            long end = System.currentTimeMillis();
            log.warn("part 2 took " + (end - start));
            start = end;
        }

        // transports table
        boolean showTransports = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        if (showTransports) {
            buf.append("<table id=\"netdbtransports\">\n");
            buf.append("<tr><th align=\"left\">" + _t("Transports") + "</th><th>" + _t("Count") + "</th></tr>\n");
            for (int i = 0; i < TNAMES.length; i++) {
                int num = transportCount[i];
                if (num > 0) {
                    buf.append("<tr><td>").append(_t(TNAMES[i]));
                    buf.append("</td><td align=\"center\">").append(num).append("</td></tr>\n");
                }
            }
            buf.append("</table>\n");
            buf.append("</td><td style=\"vertical-align: top;\">");
            out.write(buf.toString());
            buf.setLength(0);
            if (log.shouldWarn()) {
                long end = System.currentTimeMillis();
                log.warn("part 3 took " + (end - start));
                start = end;
            }
        }

        // country table
        List<String> countryList = new ArrayList<String>(countries.objects());
        if (!countryList.isEmpty()) {
            Collections.sort(countryList, new CountryComparator());
            buf.append("<table id=\"netdbcountrylist\">\n");
            buf.append("<tr><th align=\"left\">" + _t("Country") + "</th><th>" + _t("Count") + "</th></tr>\n");
            for (String country : countryList) {
                int num = countries.count(country);
                buf.append("<tr><td><a href=\"/netdb?c=").append(country).append("\">");
                buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append("\"");
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\">");
                buf.append(getTranslatedCountry(country));
                buf.append("</a></td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            // https://db-ip.com/db/download/ip-to-country-lite
            String geoDir = _context.getProperty(GeoIP.PROP_GEOIP_DIR, GeoIP.GEOIP_DIR_DEFAULT);
            File geoFile = new File(geoDir);
            if (!geoFile.isAbsolute())
                geoFile = new File(_context.getBaseDir(), geoDir);
            geoFile = new File(geoFile, GeoIP.GEOIP2_FILE_DEFAULT);
            if (geoFile.exists()) {
                // we'll assume we are using it, ignore case where Debian file is newer
                buf.append("<tr><td colspan=\"2\"><font size=\"-2\"><a href=\"https://db-ip.com/\">IP Geolocation by DB-IP</a></font></td></tr>");
            }
            buf.append("</table>\n");
        }

        buf.append("</td></tr></table>");
        if (log.shouldWarn()) {
            long end = System.currentTimeMillis();
            log.warn("part 4 took " + (end - start));
        }

     //
     // don't bother to reindent
     //
     } // if !showStats

        out.write(buf.toString());
        out.flush();
    }
    
    /**
     * Countries now in a separate bundle
     * @param code two-letter country code
     * @since 0.9.9
     */
    private String getTranslatedCountry(String code) {
        String name = _context.commSystem().getCountryName(code);
        return Translate.getString(name, _context, Messages.COUNTRY_BUNDLE_NAME);
    }

    /**
     *  Sort by translated country name using rules for the current language setting
     *  Inner class, can't be Serializable
     */
    private class CountryComparator implements Comparator<String> {
         private static final long serialVersionUID = 1L;
         private final Collator coll;

         public CountryComparator() {
             super();
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(String l, String r) {
             return coll.compare(getTranslatedCountry(l),
                                 getTranslatedCountry(r));
        }
    }

    /**
     *  Sort by style, then host
     *  @since 0.9.38
     */
    static class RAComparator implements Comparator<RouterAddress> {
         private static final long serialVersionUID = 1L;

         public int compare(RouterAddress l, RouterAddress r) {
             int rv = l.getTransportStyle().compareTo(r.getTransportStyle());
             if (rv != 0)
                 return rv;
             String lh = l.getHost();
             String rh = r.getHost();
             if (lh == null)
                 return (rh == null) ? 0 : -1;
             if (rh == null)
                 return 1;
             return lh.compareTo(rh);
        }
    }

    /**
     *  Be careful to use stripHTML for any displayed routerInfo data
     *  to prevent vulnerabilities
     */
    private void renderRouterInfo(StringBuilder buf, RouterInfo info, boolean isUs, boolean full) {
        String hash = info.getIdentity().getHash().toBase64();
        buf.append("<table class=\"netdbentry\">" +
                   "<tr id=\"").append(hash.substring(0, 6)).append("\"><th colspan=\"2\"");
        if (isUs) {
            buf.append(" id=\"our-info\"><b>").append(_t("Our Router Identity")).append(":</b> <code>")
               .append(hash).append("</code></th><th>");
        } else {
            buf.append("><b>").append(_t("Router")).append(":</b> <code>")
               .append(hash).append("</code></th><th>");
            String country = _context.commSystem().getCountry(info.getIdentity().getHash());
            if (country != null) {
                buf.append("<a href=\"/netdb?c=").append(country).append("\">");
                buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append('\"');
                buf.append(" title=\"").append(getTranslatedCountry(country)).append('\"');
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ").append("</a>");
            }
            if (!full) {
                buf.append("<a title=\"").append(_t("View extended router info"))
                   .append("\" class=\"viewfullentry\" href=\"netdb?r=").append(hash.substring(0, 6))
                   .append("\" >[").append(_t("Full entry")).append("]</a>");
            }
        }
        buf.append("</th></tr>\n<tr>");
        long age = _context.clock().now() - info.getPublished();
        if (isUs && _context.router().isHidden()) {
            buf.append("<td><b>").append(_t("Hidden")).append(", ").append(_t("Updated")).append(":</b></td>")
               .append("<td colspan=\"2\"><span class=\"netdb_info\">")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</span>");
        } else if (age > 0) {
            buf.append("<td><b>").append(_t("Published")).append(":</b></td>")
               .append("<td colspan=\"2\"><span class=\"netdb_info\">")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</span>");
        } else {
            // shouldnt happen
            buf.append("<td><b>").append(_t("Published")).append("</td><td colspan=\"2\">:</b> in ")
               .append(DataHelper.formatDuration2(0-age)).append("<span class=\"netdb_info\">???</span>");
        }
        if (full) {
            buf.append("</td></tr><tr><td><b>").append(_t("Signing Key")).append(":</b></td><td colspan=\"2\">")
               .append(info.getIdentity().getSigningPublicKey().getType());
            buf.append("</td></tr><tr><td><b>").append(_t("Encryption Key")).append(":</b></td><td colspan=\"2\">")
               .append(info.getIdentity().getPublicKey().getType());
        }
        buf.append("</td></tr>\n<tr>")
           .append("<td><b>").append(_t("Addresses")).append(":</b></td><td colspan=\"2\"");
        Collection<RouterAddress> addrs = info.getAddresses();
        if (addrs.isEmpty()) {
            buf.append('>').append(_t("none"));
        } else {
            buf.append(" class=\"netdb_addresses\">");
            if (addrs.size() > 1) {
                // addrs is unmodifiable
                List<RouterAddress> laddrs = new ArrayList<RouterAddress>(addrs);
                Collections.sort(laddrs, new RAComparator());
                addrs = laddrs;
            }
            boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
            for (RouterAddress addr : addrs) {
                String style = addr.getTransportStyle();
                buf.append("<br><b class=\"netdb_transport\">").append(DataHelper.stripHTML(style)).append(":</b>");
                if (debug) {
                    int cost = addr.getCost();
                    if (!((style.equals("SSU") && cost == 5) || (style.startsWith("NTCP") && cost == 10)))
                        buf.append("&nbsp;<span class=\"netdb_name\">").append(_t("cost")).append("</span>: <span class=\"netdb_info\">").append("" + cost).append("</span>&nbsp;");
                }
                Map<Object, Object> p = addr.getOptionsMap();
                for (Map.Entry<Object, Object> e : p.entrySet()) {
                    String name = (String) e.getKey();
                    String val = (String) e.getValue();
                    buf.append(" <span class=\"nowrap\"><span class=\"netdb_name\">").append(_t(DataHelper.stripHTML(name)))
                       .append(":</span> <span class=\"netdb_info\">").append(DataHelper.stripHTML(val)).append("</span></span>&nbsp;");
                }
            }
        }
        buf.append("</td></tr>\n");
        if (full) {
            buf.append("<tr><td><b>").append(_t("Stats")).append(":</b><td colspan=\"2\"><code>");
            Map<Object, Object> p = info.getOptionsMap();
            for (Map.Entry<Object, Object> e : p.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append(DataHelper.stripHTML(key)).append(" = ").append(DataHelper.stripHTML(val)).append("<br>\n");
            }
            buf.append("</code></td></tr>\n");
            String family = info.getOption("family");
            if (family != null) {
                FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
                if (fkc != null) {
                    String f = DataHelper.stripHTML(family);
                    buf.append("<tr><td><b>").append(_t("Family"))
                       .append(":</b><td colspan=\"2\"><span class=\"netdb_info\">")
                       .append(fkc.verify(info) == FamilyKeyCrypto.Result.STORED_KEY ? "Verified" : "Unverified")
                       .append(" <a href=\"/netdb?fam=")
                       .append(f)
                       .append("\">")
                       .append(f)
                       .append("</a></span></td></tr>\n");
                }
            }
        }
        buf.append("</table>\n");
    }

    private static final int SSU = 1;
    private static final int SSUI = 2;
    private static final int NTCP = 4;
    private static final int IPV6 = 8;
    private static final String[] TNAMES = { _x("Hidden or starting up"), _x("SSU"), _x("SSU with introducers"), "",
                                  _x("NTCP"), _x("NTCP and SSU"), _x("NTCP and SSU with introducers"), "",
                                  "", _x("IPv6 SSU"), _x("IPv6 Only SSU, introducers"), _x("IPv6 SSU, introducers"),
                                  _x("IPv6 NTCP"), _x("IPv6 NTCP, SSU"), _x("IPv6 Only NTCP, SSU, introducers"), _x("IPv6 NTCP, SSU, introducers") };
    /**
     *  what transport types
     */
    private static int classifyTransports(RouterInfo info) {
        int rv = 0;
        for (RouterAddress addr : info.getAddresses()) {
            String style = addr.getTransportStyle();
            if (style.equals("NTCP2") || style.equals("NTCP")) {
                rv |= NTCP;
            } else if (style.equals("SSU") || style.equals("SSU2")) {
                if (addr.getOption("itag0") != null)
                    rv |= SSUI;
                else
                    rv |= SSU;
            }
            String host = addr.getHost();
            if (host != null && host.contains(":")) {
                rv |= IPV6;
            } else {
                String caps = addr.getOption("caps");
                if (caps != null && caps.contains("6"))
                    rv |= IPV6;
            }
        }
        // map invalid values with "" in TNAMES
        if (rv == 3)
            rv = 2;
        else if (rv == 7)
            rv = 6;
        else if (rv == 8)
            rv = 0;
        return rv;
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** tag only */
    private static final String _x(String s) {
        return s;
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
