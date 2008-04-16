package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.Writer;

import java.lang.Math;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 * Helper class to refactor the HTML rendering from out of the ProfileOrganizer
 *
 */
class ProfileOrganizerRenderer {
    private RouterContext _context;
    private ProfileOrganizer _organizer;
    private ProfileComparator _comparator;
    
    public ProfileOrganizerRenderer(ProfileOrganizer organizer, RouterContext context) {
        _context = context;
        _organizer = organizer;
        _comparator = new ProfileComparator();
    }
    public void renderStatusHTML(Writer out) throws IOException {
        Set peers = _organizer.selectAllPeers();
        
        long now = _context.clock().now();
        long hideBefore = now - 3*60*60*1000;
        
        TreeSet order = new TreeSet(_comparator);
        TreeSet integratedPeers = new TreeSet(_comparator);
        for (Iterator iter = peers.iterator(); iter.hasNext();) {
            Hash peer = (Hash)iter.next();
            if (_organizer.getUs().equals(peer)) continue;
            PeerProfile prof = _organizer.getProfile(peer);
            if (_organizer.isWellIntegrated(peer)) {
                integratedPeers.add(prof);
            } else {
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null && info.getCapabilities().indexOf("f") >= 0)
                    integratedPeers.add(prof);
            }
            if (prof.getLastSendSuccessful() <= hideBefore) continue;
            order.add(prof);
        }
        
        int fast = 0;
        int reliable = 0;
        int integrated = 0;
        int failing = 0;
        StringBuffer buf = new StringBuffer(16*1024);
        buf.append("<h2>Peer Profiles</h2>\n");
        buf.append("<table border=\"1\">");
        buf.append("<tr>");
        buf.append("<td><b>Peer</b> (").append(order.size()).append(", hiding ").append(peers.size()-order.size()).append(")</td>");
        buf.append("<td><b>Groups (Caps)</b></td>");
        buf.append("<td><b>Speed</b></td>");
        buf.append("<td><b>Capacity</b></td>");
        buf.append("<td><b>Integration</b></td>");
        buf.append("<td><b>Failing?</b></td>");
        buf.append("<td>&nbsp;</td>");
        buf.append("</tr>");
        int prevTier = 1;
        for (Iterator iter = order.iterator(); iter.hasNext();) {
            PeerProfile prof = (PeerProfile)iter.next();
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
                failing++;
            } else {
                tier = 3;
            }
            
            if (_organizer.isWellIntegrated(peer)) {
                isIntegrated = true;
                integrated++;
            }
            
            if (tier != prevTier)
                buf.append("<tr><td colspan=\"7\"><hr /></td></tr>\n");
            prevTier = tier;
            
            buf.append("<tr>");
            buf.append("<td><code>");
            if (prof.getIsFailing()) {
                buf.append("<font color=\"red\">-- ").append(peer.toBase64().substring(0,6)).append("</font>");
            } else {
                if (prof.getIsActive()) {
                    buf.append("<font color=\"blue\">++ ").append(peer.toBase64().substring(0,6)).append("</font>");
                } else {
                    buf.append("&nbsp;&nbsp;&nbsp;").append(peer.toBase64().substring(0,6));
                }
            }
            buf.append("</code></td>");
            buf.append("<td>");
            
            switch (tier) {
                case 1: buf.append("Fast, High Capacity"); break;
                case 2: buf.append("High Capacity"); break;
                case 3: buf.append("Not Failing"); break;
                default: buf.append("Failing"); break;
            }
            if (isIntegrated) buf.append(", Integrated");
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null)
                buf.append(" (" + info.getCapabilities() + ")");
            
            buf.append("<td align=\"right\">").append(num(prof.getSpeedValue()));
            //buf.append('/').append(num(prof.getOldSpeedValue()));
            buf.append("</td>");
            buf.append("<td align=\"right\">").append(num(prof.getCapacityValue())).append("</td>");
            buf.append("<td align=\"right\">").append(num(prof.getIntegrationValue())).append("</td>");
            buf.append("<td>");
            if (_context.shitlist().isShitlisted(peer)) buf.append("Shitlist");
            if (prof.getIsFailing()) buf.append(" Failing");
            buf.append("&nbsp</td>");
            //buf.append("<td><a href=\"/profile/").append(prof.getPeer().toBase64().substring(0, 32)).append("\">profile.txt</a> ");
            //buf.append("    <a href=\"#").append(prof.getPeer().toBase64().substring(0, 32)).append("\">netDb</a></td>");
            buf.append("<td nowrap><a href=\"netdb.jsp#").append(peer.toBase64().substring(0,6)).append("\">netDb</a>");
            buf.append("/<a href=\"dumpprofile.jsp?peer=").append(peer.toBase64().substring(0,6)).append("\">profile</a></td>\n");
            buf.append("</tr>");
        }
        buf.append("</table>");

        buf.append("<h2>Floodfill and Integrated Peers</h2>\n");
        buf.append("<table border=\"1\">");
        buf.append("<tr>");
        buf.append("<td><b>Peer</b></td>");
        buf.append("<td><b>Caps</b></td>");
        buf.append("<td><b>Integ. Value</b></td>");
        buf.append("<td><b>Last Heard About</b></td>");
        buf.append("<td><b>Last Heard From</b></td>");
        buf.append("<td><b>Last Successful Send</b></td>");
        buf.append("<td><b>Last Failed Send</b></td>");
        buf.append("<td><b>10m Resp. Time</b></td>");
        buf.append("<td><b>1h Resp. Time</b></td>");
        buf.append("<td><b>1d Resp. Time</b></td>");
        buf.append("<td><b>Successful Lookups</b></td>");
        buf.append("<td><b>Failed Lookups</b></td>");
        buf.append("<td><b>New Stores</b></td>");
        buf.append("<td><b>Old Stores</b></td>");
        buf.append("<td><b>1m Fail Rate</b></td>");
        buf.append("<td><b>1h Fail Rate</b></td>");
        buf.append("<td><b>1d Fail Rate</b></td>");
        buf.append("</tr>");
        for (Iterator iter = integratedPeers.iterator(); iter.hasNext();) {
            PeerProfile prof = (PeerProfile)iter.next();
            Hash peer = prof.getPeer();

            buf.append("<tr>");
            buf.append("<td><code>");
            if (prof.getIsFailing()) {
                buf.append("<font color=\"red\">-- ").append(peer.toBase64().substring(0,6)).append("</font>");
            } else {
                if (prof.getIsActive()) {
                    buf.append("<font color=\"blue\">++ ").append(peer.toBase64().substring(0,6)).append("</font>");
                } else {
                    buf.append("&nbsp;&nbsp;&nbsp;").append(peer.toBase64().substring(0,6));
                }
            }
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null)
                buf.append("<td align=\"center\">" + info.getCapabilities() + "</td>");
            else
                buf.append("<td>&nbsp;</td>");
            buf.append("</code></td>");
            buf.append("<td align=\"right\">").append(num(prof.getIntegrationValue())).append("</td>");
            long time;
            time = now - prof.getLastHeardAbout();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration(time)).append("</td>");
            time = now - prof.getLastHeardFrom();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration(time)).append("</td>");
            time = now - prof.getLastSendSuccessful();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration(time)).append("</td>");
            time = now - prof.getLastSendFailed();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration(time)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 10*60*1000l)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 60*60*1000l)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 24*60*60*1000l)).append("</td>");
            DBHistory dbh = prof.getDBHistory();
            if (dbh != null) {
                buf.append("<td align=\"right\">").append(dbh.getSuccessfulLookups()).append("</td>");
                buf.append("<td align=\"right\">").append(dbh.getFailedLookups()).append("</td>");
                buf.append("<td align=\"right\">").append(dbh.getUnpromptedDbStoreNew()).append("</td>");
                buf.append("<td align=\"right\">").append(dbh.getUnpromptedDbStoreOld()).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 60*1000l)).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 60*60*1000l)).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 24*60*60*1000l)).append("</td>");
            }
        }
        buf.append("</table>");

        buf.append("<p><i>Definitions:<ul>");
        buf.append("<li><b>groups</b>: as determined by the profile organizer</li>");
        buf.append("<li><b>caps</b>: capabilities in the netDb, not used to determine profiles</li>");
        buf.append("<li><b>speed</b>: peak throughput (bytes per second) over a 1 minute period that the peer has sustained in a single tunnel</li>");
        buf.append("<li><b>capacity</b>: how many tunnels can we ask them to join in an hour?</li>");
        buf.append("<li><b>integration</b>: how many new peers have they told us about lately?</li>");
        buf.append("<li><b>failing?</b>: is the peer currently swamped (and if possible we should avoid nagging them)?</li>");
        buf.append("</ul></i>");
        buf.append("Red peers prefixed with '--' means the peer is failing, and blue peers prefixed ");
        buf.append("with '++' means we've sent or received a message from them ");
        buf.append("in the last five minutes.</i><br />");
        buf.append("<p><b>Thresholds:</b><br />");
        buf.append("<b>Speed:</b> ").append(num(_organizer.getSpeedThreshold())).append(" (").append(fast).append(" fast peers)<br />");
        buf.append("<b>Capacity:</b> ").append(num(_organizer.getCapacityThreshold())).append(" (").append(reliable).append(" high capacity peers)<br />");
        buf.append("<b>Integration:</b> ").append(num(_organizer.getIntegrationThreshold())).append(" (").append(integrated).append(" well integrated peers)<br />");
        out.write(buf.toString());
        out.flush();
    }
    
    private class ProfileComparator implements Comparator {
        public int compare(Object lhs, Object rhs) {
            if ( (lhs == null) || (rhs == null) ) 
                throw new NullPointerException("lhs=" + lhs + " rhs=" + rhs);
            if ( !(lhs instanceof PeerProfile) || !(rhs instanceof PeerProfile) ) 
                throw new ClassCastException("lhs=" + lhs.getClass().getName() + " rhs=" + rhs.getClass().getName());
            
            PeerProfile left = (PeerProfile)lhs;
            PeerProfile right = (PeerProfile)rhs;
            
            if (_context.profileOrganizer().isFast(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return compareHashes(left, right);
                } else {
                    return -1; // fast comes first
                }
            } else if (_context.profileOrganizer().isHighCapacity(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1; 
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return compareHashes(left, right);
                } else {
                    return -1;
                }
            } else if (_context.profileOrganizer().isFailing(left.getPeer())) {
                if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return compareHashes(left, right);
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
                    return compareHashes(left, right);
                }
            }
        }
        
        private int compareHashes(PeerProfile left, PeerProfile right) {
            return left.getPeer().toBase64().compareTo(right.getPeer().toBase64());
        }
        
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }

    String avg (PeerProfile prof, long rate) {
            RateStat rs = prof.getDbResponseTime();
            if (rs == null)
                return "0ms";
            Rate r = rs.getRate(rate);
            if (r == null)
                return "0ms";
            long c = r.getCurrentEventCount() + r.getLastEventCount();
            if (c == 0)
                return "0ms";
            double d = r.getCurrentTotalValue() + r.getLastTotalValue();
            return Math.round(d/c) + "ms";
    }

    String davg (DBHistory dbh, long rate) {
            RateStat rs = dbh.getFailedLookupRate();
            if (rs == null)
                return num(0d);
            Rate r = rs.getRate(rate);
            if (r == null)
                return num(0d);
            long c = r.getCurrentEventCount() + r.getLastEventCount();
            return "" + c;
    }
}
