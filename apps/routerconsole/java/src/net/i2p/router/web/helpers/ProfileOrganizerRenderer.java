package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import static net.i2p.router.web.helpers.TunnelRenderer.range;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;

/**
 * Helper class to refactor the HTML rendering from out of the ProfileOrganizer
 *
 */
class ProfileOrganizerRenderer {
    private final RouterContext _context;
    private final ProfileOrganizer _organizer;

    public ProfileOrganizerRenderer(ProfileOrganizer organizer, RouterContext context) {
        _context = context;
        _organizer = organizer;
    }

    /**
     *  @param mode 0 = high cap; 1 = all; 2 = floodfill
     */
    public void renderStatusHTML(Writer out, int mode) throws IOException {
        boolean full = mode == 1;
        Set<Hash> peers = _organizer.selectAllPeers();

        long now = _context.clock().now();
        long hideBefore = now - 90*60*1000;

        Set<PeerProfile> order = new TreeSet<PeerProfile>(mode == 2 ? new HashComparator() : new ProfileComparator());
        int older = 0;
        int standard = 0;
        for (Hash peer : peers) {
            if (_organizer.getUs().equals(peer)) continue;
            PeerProfile prof = _organizer.getProfileNonblocking(peer);
            if (prof == null)
                continue;
            if (mode == 2) {
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null && info.getCapabilities().indexOf('f') >= 0)
                    order.add(prof);
                continue;
            }
            if (prof.getLastSendSuccessful() <= hideBefore) {
                older++;
                continue;
            }
            if ((!full) && !_organizer.isHighCapacity(peer)) {
                standard++;
                continue;
            }
            order.add(prof);
        }

        int fast = 0;
        int reliable = 0;
        int integrated = 0;
        StringBuilder buf = new StringBuilder(16*1024);

      ////
      //// don't bother reindenting
      ////
      if (mode < 2) {

        //buf.append("<h2>").append(_t("Peer Profiles")).append("</h2>\n<p>");
        buf.append("<p id=\"profiles_overview\" class=\"infohelp\">");
        buf.append(ngettext("Showing 1 recent profile.", "Showing {0} recent profiles.", order.size())).append('\n');
        if (older > 0)
            buf.append(ngettext("Hiding 1 older profile.", "Hiding {0} older profiles.", older)).append('\n');
        if (standard > 0)
            buf.append("<a href=\"/profiles?f=1\">").append(ngettext("Hiding 1 standard profile.", "Hiding {0} standard profiles.", standard)).append("</a>\n");
        buf.append("</p>");
                   buf.append("<div class=\"widescroll\"><table id=\"profilelist\">");
                   buf.append("<tr>");
                   buf.append("<th>").append(_t("Peer")).append("</th>");
                   buf.append("<th>").append(_t("Groups")).append("</th>");
                   buf.append("<th>").append(_t("Caps")).append("</th>");
                   buf.append("<th>").append(_t("Version")).append("</th>");
                   buf.append("<th>").append(_t("Speed")).append("</th>");
                   buf.append("<th>").append(_t("Capacity")).append("</th>");
                   buf.append("<th>").append(_t("Integration")).append("</th>");
                   buf.append("<th>").append(_t("Status")).append("</th>");
                   buf.append("<th>").append(_t("View/Edit")).append("</th>");
                   buf.append("</tr>");
        int prevTier = 1;
        for (PeerProfile prof : order) {
            Hash peer = prof.getPeer();

            int tier = 0;
            boolean isIntegrated = false;
            if (_organizer.isFast(peer)) {
                tier = 1;
                fast++;
                reliable++;
            } else if (_organizer.isHighCapacity(peer)) {
                tier = 2;
                reliable++;
            } else if (_organizer.isFailing(peer)) {
            } else {
                tier = 3;
            }

            if (_organizer.isWellIntegrated(peer)) {
                isIntegrated = true;
                integrated++;
            }

            if (tier != prevTier)
                buf.append("<tr><td colspan=\"9\"><hr></td></tr>\n");
            prevTier = tier;

            buf.append("<tr><td align=\"center\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer));
            // debug
            //if(prof.getIsExpandedDB())
            //   buf.append(" ** ");
            buf.append("</td><td align=\"center\">");

            switch (tier) {
                case 1: buf.append(_t("Fast, High Capacity")); break;
                case 2: buf.append(_t("High Capacity")); break;
                case 3: buf.append(_t("Standard")); break;
                default: buf.append(_t("Failing")); break;
            }
            if (isIntegrated) buf.append(", ").append(_t("Integrated"));
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null) {
                // prevent HTML injection in the caps and version
                buf.append("<td align=\"right\">").append(DataHelper.stripHTML(info.getCapabilities()));
            } else {
                buf.append("<td align=\"right\"><i>").append(_t("unknown")).append("</i></td>");
            }
            buf.append("<td align=\"right\">");
            String v = info != null ? info.getOption("router.version") : null;
            if (v != null)
                buf.append(DataHelper.stripHTML(v));
            buf.append("</td><td align=\"right\">").append(num(prof.getSpeedValue()));
            long bonus = prof.getSpeedBonus();
            if (bonus != 0) {
                if (bonus > 0)
                    buf.append(" (+");
                else
                    buf.append(" (");
                buf.append(bonus).append(')');
            }
            buf.append("</td><td align=\"right\">").append(num(prof.getCapacityValue()));
            bonus = prof.getCapacityBonus();
            if (bonus != 0) {
                if (bonus > 0)
                    buf.append(" (+");
                else
                    buf.append(" (");
                buf.append(bonus).append(')');
            }
            buf.append("</td><td align=\"right\">").append(num(prof.getIntegrationValue()));
            buf.append("</td><td align=\"center\">");
            boolean ok = true;
            if (_context.banlist().isBanlisted(peer)) {
                buf.append(_t("Banned"));
                ok = false;
            }
            if (prof.getIsFailing()) {
                buf.append(' ').append(_t("Failing"));
                ok = false;
            }
            if (_context.commSystem().wasUnreachable(peer)) {
                buf.append(' ').append(_t("Unreachable"));
                ok = false;
            }
            RateAverages ra = RateAverages.getTemp();
            Rate failed = prof.getTunnelHistory().getFailedRate().getRate(30*60*1000);
            long fails = failed.computeAverages(ra, false).getTotalEventCount();
            if (ok && fails == 0) {
                buf.append(_t("OK"));
            } else if (fails > 0) {
                Rate accepted = prof.getTunnelCreateResponseTime().getRate(30*60*1000);
                long total = fails + accepted.computeAverages(ra, false).getTotalEventCount();
                if (total / fails <= 10)   // hide if < 10%
                    buf.append(' ').append(fails).append('/').append(total).append(' ').append(_t("Test Fails"));
            }

            buf.append("&nbsp;</td>");
            //buf.append("<td nowrap align=\"center\"><a target=\"_blank\" href=\"dumpprofile.jsp?peer=")
            //   .append(peer.toBase64().substring(0,6)).append("\">").append(_t("profile")).append("</a>");
            buf.append("<td nowrap align=\"center\"><a href=\"viewprofile?peer=")
               .append(peer.toBase64()).append("\">").append(_t("profile")).append("</a>");
            buf.append("&nbsp;<a title=\"").append(_t("Configure peer")).append("\" href=\"configpeer?peer=").append(peer.toBase64()).append("\">+-</a></td>\n");
            buf.append("</tr>");
            // let's not build the whole page in memory (~500 bytes per peer)
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("</table></div>");

      ////
      //// don't bother reindenting
      ////
      } else {

        //buf.append("<h2><a name=\"flood\"></a>").append(_t("Floodfill and Integrated Peers"))
        //   .append(" (").append(integratedPeers.size()).append(")</h2>\n");
        buf.append("<div class=\"widescroll\"><table id=\"floodfills\">");
        buf.append("<tr class=\"smallhead\">");
        buf.append("<th>").append(_t("Peer")).append("</th>");
        buf.append("<th>").append(_t("Caps")).append("</th>");
        buf.append("<th>").append(_t("Integ. Value")).append("</th>");
        buf.append("<th>").append(_t("Last Heard About")).append("</th>");
        buf.append("<th>").append(_t("Last Heard From")).append("</th>");
        buf.append("<th>").append(_t("Last Good Send")).append("</th>");
        buf.append("<th>").append(_t("Last Bad Send")).append("</th>");
        buf.append("<th>").append(_t("10m Resp. Time")).append("</th>");
        buf.append("<th>").append(_t("1h Resp. Time")).append("</th>");
        buf.append("<th>").append(_t("1d Resp. Time")).append("</th>");
        buf.append("<th>").append(_t("Last Good Lookup")).append("</th>");
        buf.append("<th>").append(_t("Last Bad Lookup")).append("</th>");
        buf.append("<th>").append(_t("Last Good Store")).append("</th>");
        buf.append("<th>").append(_t("Last Bad Store")).append("</th>");
        buf.append("<th>").append(_t("1h Fail Rate")).append("</th>");
        buf.append("<th>").append(_t("1d Fail Rate")).append("</th>");
        buf.append("</tr>");
        RateAverages ra = RateAverages.getTemp();
        for (PeerProfile prof : order) {
            Hash peer = prof.getPeer();

            buf.append("<tr><td align=\"center\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer));
            buf.append("</td>");
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null)
                buf.append("<td align=\"center\">").append(DataHelper.stripHTML(info.getCapabilities())).append("</td>");
            else
                buf.append("<td>&nbsp;</td>");
            buf.append("<td align=\"right\">").append(num(prof.getIntegrationValue())).append("</td>");
            buf.append("<td align=\"right\">").append(formatInterval(now, prof.getLastHeardAbout())).append("</td>");
            buf.append("<td align=\"right\">").append(formatInterval(now, prof.getLastHeardFrom())).append("</td>");
            buf.append("<td align=\"right\">").append(formatInterval(now, prof.getLastSendSuccessful())).append("</td>");
            buf.append("<td align=\"right\">").append(formatInterval(now, prof.getLastSendFailed())).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 10*60*1000l, ra)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 60*60*1000l, ra)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 24*60*60*1000l, ra)).append("</td>");
            DBHistory dbh = prof.getDBHistory();
            if (dbh != null) {
                buf.append("<td align=\"right\">").append(formatInterval(now, dbh.getLastLookupSuccessful())).append("</td>");
                buf.append("<td align=\"right\">").append(formatInterval(now, dbh.getLastLookupFailed())).append("</td>");
                buf.append("<td align=\"right\">").append(formatInterval(now, dbh.getLastStoreSuccessful())).append("</td>");
                buf.append("<td align=\"right\">").append(formatInterval(now, dbh.getLastStoreFailed())).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 60*60*1000l, ra)).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 24*60*60*1000l, ra)).append("</td>");
            } else {
                for (int i = 0; i < 6; i++)
                    buf.append("<td align=\"right\">").append(_t(NA));
            }
            buf.append("</tr>\n");
        }
        buf.append("</table></div>");

      ////
      //// don't bother reindenting
      ////
      }
      if (mode < 2) {

        buf.append("<h3 class=\"tabletitle\">").append(_t("Thresholds")).append("</h3>\n")
           .append("<table id=\"thresholds\"><tbody>")
           .append("<tr><th><b>")
           .append(_t("Speed")).append(": </b>").append(num(_organizer.getSpeedThreshold()))
           .append("</th><th><b>")
           .append(_t("Capacity")).append(": </b>").append(num(_organizer.getCapacityThreshold()))
           .append("</th><th><b>")
           .append(_t("Integration")).append(": </b>").append(num(_organizer.getIntegrationThreshold()))
           .append("</th></tr><tr><td>")
           .append(fast).append(' ').append(_t("fast peers"))
           .append("</td><td>")
           .append(reliable).append(' ').append(_t("high capacity peers"))
           .append("</td><td>")
           .append(integrated).append(' ').append(_t(" well integrated peers"))
           .append("</td></tr></tbody></table>\n");
        buf.append("<h3 class=\"tabletitle\">").append(_t("Definitions")).append("</h3>\n")
           .append("<table id=\"profile_defs\"><tbody>");
        buf.append("<tr><td><b>")
           .append(_t("groups")).append(":</b></td><td>").append(_t("as determined by the profile organizer"))
           .append("</td></tr>");
        buf.append("<tr><td><b>")
           .append(_t("caps")).append(":</b></td><td>").append(_t("capabilities in the netDb, not used to determine profiles"))
           .append("</td></tr>");
        buf.append("<tr id=\"capabilities_key\"><td colspan=\"2\"><table><tbody>");
        buf.append("<tr><td>&nbsp;</td>")
           .append("<td><b>B</b></td><td>").append(_t("SSU Testing")).append("</td>")
           .append("<td><b>C</b></td><td>").append(_t("SSU Introducer")).append("</td>")
           .append("<td>&nbsp;</td></tr>");
        buf.append("<tr><td>&nbsp;</td>")
           .append("<td><b>f</b></td><td>").append(_t("Floodfill")).append("</td>")
           .append("<td><b>H</b></td><td>").append(_t("Hidden")).append("</td>")
           .append("<td>&nbsp;</td></tr>");
        buf.append("<tr><td>&nbsp;</td>")
           .append("<td><b>K</b></td><td>").append(_t("Under {0} shared bandwidth", Router.MIN_BW_L + " KBps")).append("</td>")
           .append("<td><b>L</b></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_L, Router.MIN_BW_M))).append("</td>")
           .append("<td>&nbsp;</td></tr>");
        buf.append("<tr><td>&nbsp;</td>")
           .append("<td><b>M</b></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_M, Router.MIN_BW_N))).append("</td>")
           .append("<td><b>N</b></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_N, Router.MIN_BW_O))).append("</td>")
           .append("<td>&nbsp;</td></tr>");
        buf.append("<tr><td>&nbsp;</td>")
           .append("<td><b>O</b></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_O, Router.MIN_BW_P))).append("</td>")
           .append("<td><b>P</b></td><td>").append(_t("{0} shared bandwidth", range(Router.MIN_BW_P, Router.MIN_BW_X))).append("</td>")
           .append("<td>&nbsp;</td></tr>");
        buf.append("<tr><td>&nbsp;</td>")
           .append("<td><b>R</b></td><td>").append(_t("Reachable")).append("</td>")
           .append("<td><b>U</b></td><td>").append(_t("Unreachable")).append("</td>")
           .append("<td>&nbsp;</td></tr>");
        buf.append("<tr><td>&nbsp;</td>")
           .append("<td><b>X</b></td><td>").append(_t("Over {0} shared bandwidth", Math.round(Router.MIN_BW_X * 1.024f) + " KBps")).append("</td>")
           .append("<td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td></tr>");
        buf.append("<tr><td>&nbsp;</td><td colspan=\"5\">").append(_t("Note: For P and X bandwidth tiers, O is included for the purpose of backward compatibility in the NetDB."))
           .append("</tr>");
        buf.append("</tbody></table></td></tr>"); // profile_defs
        buf.append("<tr><td><b>")
           .append(_t("speed"))
           .append(":</b></td><td>")
           .append(_t("peak throughput (bytes per second) over a 1 minute period that the peer has sustained in a single tunnel"))
           .append("</td></tr>");
        buf.append("<tr><td><b>")
           .append(_t("capacity"))
           .append(":</b></td><td>")
           .append(_t("how many tunnels can we ask them to join in an hour?"))
           .append("</td></tr>");
        buf.append("<tr><td><b>")
           .append(_t("integration"))
           .append(":</b></td><td>")
           .append(_t("how many new peers have they told us about lately?"))
           .append("</td></tr>");
        buf.append("<tr><td><b>")
           .append(_t("status"))
           .append(":</b></td><td>")
           .append(_t("is the peer banned, or unreachable, or failing tunnel tests?"))
           .append("</td></tr>");
        buf.append("</tbody></table>\n"); // thresholds

      ////
      //// don't bother reindenting
      ////
      }  // mode < 2

        out.write(buf.toString());
        out.flush();
    }

    private class ProfileComparator extends HashComparator {
        public int compare(PeerProfile left, PeerProfile right) {
            if (_context.profileOrganizer().isFast(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return -1; // fast comes first
                }
            } else if (_context.profileOrganizer().isHighCapacity(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1; 
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return -1;
                }
            } else if (_context.profileOrganizer().isFailing(left.getPeer())) {
                if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return super.compare(left, right);
                } else {
                    return 1;
                }
            } else {
                // left is not failing
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return -1;
                } else {
                    return super.compare(left, right);
                }
            }
        }
    }

    /**
     *  Used for floodfill-only page
     *  As of 0.9.29, sorts in true binary order, not base64 string
     *  @since 0.9.8
     */
    private static class HashComparator implements Comparator<PeerProfile>, Serializable {
        public int compare(PeerProfile left, PeerProfile right) {
            return DataHelper.compareTo(left.getPeer().getData(), right.getPeer().getData());
        }

    }

    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    private final static String NA = HelperBase._x("n/a");

    private String avg (PeerProfile prof, long rate, RateAverages ra) {
            RateStat rs = prof.getDbResponseTime();
            if (rs == null)
                return _t(NA);
            Rate r = rs.getRate(rate);
            if (r == null)
                return _t(NA);
            r.computeAverages(ra, false);
            if (ra.getTotalEventCount() == 0)
                return _t(NA);
            return DataHelper.formatDuration2(Math.round(ra.getAverage()));
    }

    private String davg (DBHistory dbh, long rate, RateAverages ra) {
            RateStat rs = dbh.getFailedLookupRate();
            if (rs == null)
                return "0%";
            Rate r = rs.getRate(rate);
            if (r == null)
                return "0%";
            r.computeAverages(ra, false);
            if (ra.getTotalEventCount() <= 0)
                return "0%";
            double avg = 0.5 + 100 * ra.getAverage();
            return ((int) avg) + "%";
    }

    /** @since 0.9.21 */
    private String formatInterval(long now, long then) {
        if (then <= 0)
            return _t(NA);
        // avoid 0 or negative
        if (now <= then)
            return DataHelper.formatDuration2(1);
        return DataHelper.formatDuration2(now - then);
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /** translate (ngettext) @since 0.8.5 */
    public String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }

}
