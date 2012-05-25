package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
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
        long hideBefore = now - 2*60*60*1000;
        
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
        StringBuilder buf = new StringBuilder(16*1024);
        buf.append("<h2>Peer Profiles</h2>\n");
        buf.append("<p>Showing ").append(order.size()).append(" recent profiles, hiding ").append(peers.size()-order.size()).append(" older profiles</p>");
        buf.append("<table>");
        buf.append("<tr>");
        buf.append("<th>Peer</th>");
        buf.append("<th>Groups (Caps)</th>");
        buf.append("<th>Speed</th>");
        buf.append("<th>Capacity</th>");
        buf.append("<th>Integration</th>");
        buf.append("<th>Failing?</th>");
        buf.append("<th>&nbsp;</th>");
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
            
            buf.append("<tr><td align=\"center\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer));
            buf.append("</td><td align=\"center\">");
            
            switch (tier) {
                case 1: buf.append("Fast, High Capacity"); break;
                case 2: buf.append("High Capacity"); break;
                case 3: buf.append("Not Failing"); break;
                default: buf.append("Failing"); break;
            }
            if (isIntegrated) buf.append(", Integrated");
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null) {
                buf.append(" (").append(info.getCapabilities());
                String v = info.getOption("router.version");
                if (v != null)
                    buf.append(' ').append(v);
                buf.append(')');
            }
            
            buf.append("<td align=\"right\">").append(num(prof.getSpeedValue()));
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
            if (_context.shitlist().isShitlisted(peer)) buf.append("Banned");
            if (prof.getIsFailing()) buf.append(" Failing");
            if (_context.commSystem().wasUnreachable(peer)) buf.append(" Unreachable");
            Rate failed = prof.getTunnelHistory().getFailedRate().getRate(30*60*1000);
            long fails = failed.getCurrentEventCount() + failed.getLastEventCount();
            if (fails > 0) {
                Rate accepted = prof.getTunnelCreateResponseTime().getRate(30*60*1000);
                long total = fails + accepted.getCurrentEventCount() + accepted.getLastEventCount();
                if (total / fails <= 10)   // hide if < 10%
                    buf.append(' ').append(fails).append('/').append(total).append(" Test Fails");
            }
            buf.append("&nbsp</td>");
            buf.append("<td nowrap align=\"center\"><a target=\"_blank\" href=\"dumpprofile.jsp?peer=").append(peer.toBase64().substring(0,6)).append("\">profile</a>");
            buf.append("&nbsp;<a href=\"configpeer.jsp?peer=").append(peer.toBase64()).append("\">+-</a></td>\n");
            buf.append("</tr>");
        }
        buf.append("</table>");

        buf.append("<h2>Floodfill and Integrated Peers</h2>\n");
        buf.append("<table>");
        buf.append("<tr>");
        buf.append("<th class=\"smallhead\">Peer</th>");
        buf.append("<th class=\"smallhead\">Caps</th>");
        buf.append("<th class=\"smallhead\">Integ. Value</th>");
        buf.append("<th class=\"smallhead\">Last Heard About</th>");
        buf.append("<th class=\"smallhead\">Last Heard From</th>");
//        buf.append("<th class=\"smallhead\">Last Successful Send</th>");
        buf.append("<th class=\"smallhead\">Last Good Send</th>");        
//        buf.append("<th class=\"smallhead\">Last Failed Send</th>");
        buf.append("<th class=\"smallhead\">Last Bad Send</th>");
        buf.append("<th class=\"smallhead\">10m Resp. Time</th>");
        buf.append("<th class=\"smallhead\">1h Resp. Time</th>");
        buf.append("<th class=\"smallhead\">1d Resp. Time</th>");
//        buf.append("<th class=\"smallhead\">Successful Lookups</th>"); 
        buf.append("<th class=\"smallhead\">Good Lookups</th>"); 
//        buf.append("<th>Failed Lookups</th>");
        buf.append("<th class=\"smallhead\">Bad Lookups</th>");        
        buf.append("<th class=\"smallhead\">New Stores</th>");
        buf.append("<th class=\"smallhead\">Old Stores</th>");
        buf.append("<th class=\"smallhead\">1h Fail Rate</th>");
        buf.append("<th class=\"smallhead\">1d Fail Rate</th>");
        buf.append("</tr>");
        for (Iterator iter = integratedPeers.iterator(); iter.hasNext();) {
            PeerProfile prof = (PeerProfile)iter.next();
            Hash peer = prof.getPeer();

            buf.append("<tr><td align=\"center\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer));
            buf.append("</td>");
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
                buf.append("<td align=\"right\">").append(davg(dbh, 60*60*1000l)).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 24*60*60*1000l)).append("</td>");
            }
        }
        buf.append("</table>");

        buf.append("<h3>Thresholds:</h3>");
        buf.append("<b>Speed:</b> ").append(num(_organizer.getSpeedThreshold())).append(" (").append(fast).append(" fast peers)<br />");
        buf.append("<b>Capacity:</b> ").append(num(_organizer.getCapacityThreshold())).append(" (").append(reliable).append(" high capacity peers)<br />");
        buf.append("<b>Integration:</b> ").append(num(_organizer.getIntegrationThreshold())).append(" (").append(integrated).append(" well integrated peers)");
        buf.append("<h3>Definitions:</h3><ul>");
        buf.append("<li><b>groups</b>: as determined by the profile organizer</li>");
        buf.append("<li><b>caps</b>: capabilities in the netDb, not used to determine profiles</li>");
        buf.append("<li><b>speed</b>: peak throughput (bytes per second) over a 1 minute period that the peer has sustained in a single tunnel</li>");
        buf.append("<li><b>capacity</b>: how many tunnels can we ask them to join in an hour?</li>");
        buf.append("<li><b>integration</b>: how many new peers have they told us about lately?</li>");
        buf.append("<li><b>failing?</b>: is the peer currently swamped (and if possible we should avoid nagging them)?</li>");
        buf.append("</ul></i>");
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
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    private final static String na = "n/a";

    String avg (PeerProfile prof, long rate) {
            RateStat rs = prof.getDbResponseTime();
            if (rs == null)
                return na;
            Rate r = rs.getRate(rate);
            if (r == null)
                return na;
            long c = r.getCurrentEventCount() + r.getLastEventCount();
            if (c == 0)
                return na;
            double d = r.getCurrentTotalValue() + r.getLastTotalValue();
            return Math.round(d/c) + "ms";
    }

    String davg (DBHistory dbh, long rate) {
            RateStat rs = dbh.getFailedLookupRate();
            if (rs == null)
                return na;
            Rate r = rs.getRate(rate);
            if (r == null)
                return na;
            long c = r.getCurrentEventCount() + r.getLastEventCount();
            return "" + c;
    }
}
