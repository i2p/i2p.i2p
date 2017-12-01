package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;

import net.i2p.data.DataHelper;
import net.i2p.router.transport.udp.PeerState;

/**
 *  Comparators for various columns
 *
 *  @since 0.9.31 moved from udp; 0.9.18 moved from UDPTransport
 */
class UDPSorters {

    static final int FLAG_ALPHA = 0;
    static final int FLAG_IDLE_IN = 1;
    static final int FLAG_IDLE_OUT = 2;
    static final int FLAG_RATE_IN = 3;
    static final int FLAG_RATE_OUT = 4;
    static final int FLAG_SKEW = 5;
    static final int FLAG_CWND= 6;
    static final int FLAG_SSTHRESH = 7;
    static final int FLAG_RTT = 8;
    // static final int FLAG_DEV = 9;
    static final int FLAG_RTO = 10;
    static final int FLAG_MTU = 11;
    static final int FLAG_SEND = 12;
    static final int FLAG_RECV = 13;
    static final int FLAG_RESEND = 14;
    static final int FLAG_DUP = 15;
    static final int FLAG_UPTIME = 16;
    static final int FLAG_DEBUG = 99;
    
    static Comparator<PeerState> getComparator(int sortFlags) {
        Comparator<PeerState> rv;
        switch (Math.abs(sortFlags)) {
            case FLAG_IDLE_IN:
                rv = new IdleInComparator();
                break;
            case FLAG_IDLE_OUT:
                rv = new IdleOutComparator();
                break;
            case FLAG_RATE_IN:
                rv = new RateInComparator();
                break;
            case FLAG_RATE_OUT:
                rv = new RateOutComparator();
                break;
            case FLAG_UPTIME:
                rv = new UptimeComparator();
                break;
            case FLAG_SKEW:
                rv = new SkewComparator();
                break;
            case FLAG_CWND:
                rv = new CwndComparator();
                break;
            case FLAG_SSTHRESH:
                rv = new SsthreshComparator();
                break;
            case FLAG_RTT:
                rv = new RTTComparator();
                break;
            //case FLAG_DEV:
            //    rv = new DevComparator();
            //    break;
            case FLAG_RTO:
                rv = new RTOComparator();
                break;
            case FLAG_MTU:
                rv = new MTUComparator();
                break;
            case FLAG_SEND:
                rv = new SendCountComparator();
                break;
            case FLAG_RECV:
                rv = new RecvCountComparator();
                break;
            case FLAG_RESEND:
                rv = new ResendComparator();
                break;
            case FLAG_DUP:
                rv = new DupComparator();
                break;
            case FLAG_ALPHA:
            default:
                rv = new AlphaComparator();
                break;
        }
        if (sortFlags < 0)
            rv = Collections.reverseOrder(rv);
        return rv;
    }

    static class AlphaComparator extends PeerComparator {
    }

    static class IdleInComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = r.getLastReceiveTime() - l.getLastReceiveTime();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }

    static class IdleOutComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = r.getLastSendTime() - l.getLastSendTime();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }

    static class RateInComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getReceiveBps() - r.getReceiveBps();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return rv;
        }
    }

    static class RateOutComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getSendBps() - r.getSendBps();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return rv;
        }
    }

    static class UptimeComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = r.getKeyEstablishedTime() - l.getKeyEstablishedTime();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }

    static class SkewComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = Math.abs(l.getClockSkew()) - Math.abs(r.getClockSkew());
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }

    static class CwndComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getSendWindowBytes() - r.getSendWindowBytes();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return rv;
        }
    }

    static class SsthreshComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getSlowStartThreshold() - r.getSlowStartThreshold();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return rv;
        }
    }

    static class RTTComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getRTT() - r.getRTT();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return rv;
        }
    }

 /***
    static class DevComparator extends PeerComparator {
        static final DevComparator _instance = new DevComparator();
        public static final DevComparator instance() { return _instance; }
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getRTTDeviation() - r.getRTTDeviation();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
  ****/

    /** */
    static class RTOComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getRTO() - r.getRTO();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return rv;
        }
    }

    static class MTUComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            int rv = l.getMTU() - r.getMTU();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return rv;
        }
    }

    static class SendCountComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsTransmitted() - r.getPacketsTransmitted();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }

    static class RecvCountComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsReceived() - r.getPacketsReceived();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }

    static class ResendComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsRetransmitted() - r.getPacketsRetransmitted();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }

    static class DupComparator extends PeerComparator {
        @Override
        public int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsReceivedDuplicate() - r.getPacketsReceivedDuplicate();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    
    static class PeerComparator implements Comparator<PeerState>, Serializable {
        public int compare(PeerState l, PeerState r) {
            return DataHelper.compareTo(l.getRemotePeer().getData(), r.getRemotePeer().getData());
        }
    }

    static void appendSortLinks(StringBuilder buf, String urlBase, int sortFlags, String descr, int ascending) {
        if (ascending == FLAG_ALPHA) {  // 0
            buf.append(" <span class=\"sortdown\"><a href=\"").append(urlBase).append("?sort=0" +
                       "#udpcon\" title=\"").append(descr).append("\"><img src=\"/themes/console/images/inbound.png\" alt=\"V\"></a></span>");
        } else if (sortFlags == ascending) {
            buf.append(" <span class=\"sortdown\"><a href=\"").append(urlBase).append("?sort=").append(0-ascending);
            buf.append("#udpcon\" title=\"").append(descr).append("\"><img src=\"/themes/console/images/inbound.png\" alt=\"V\"></a></span>" +
                       "<span class=\"sortupactive\"><b><img src=\"/themes/console/images/outbound.png\" alt=\"^\"></b></span>");
        } else if (sortFlags == 0 - ascending) {
            buf.append(" <span class=\"sortdownactive\"><b><img src=\"/themes/console/images/inbound.png\" alt=\"V\"></b></span><span class=\"sortup\"><a href=\"")
               .append(urlBase).append("?sort=").append(ascending);
            buf.append("#udpcon\" title=\"").append(descr).append("\"><img src=\"/themes/console/images/outbound.png\" alt=\"^\"></a></span>");
        } else {
            buf.append(" <span class=\"sortdown\"><a href=\"").append(urlBase).append("?sort=").append(0-ascending);
            buf.append("#udpcon\" title=\"").append(descr).append("\"><img src=\"/themes/console/images/inbound.png\" alt=\"V\"></a></span>" +
                       "<span class=\"sortup\"><a href=\"").append(urlBase).append("?sort=").append(ascending);
            buf.append("#udpcon\" title=\"").append(descr).append("\"><img src=\"/themes/console/images/outbound.png\" alt=\"^\"></a></span>");
        }
    }
}
