package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 *  A Westwood+ bandwidth estimator with
 *  a first stage anti-aliasing low pass filter based on RTT,
 *  and the time-varying Westwood filter based on inter-arrival time.
 *
 *  Ref: TCP Westwood: End-to-End Congestion Control for Wired/Wireless Networks
 *  Casetti et al
 *  (Westwood)
 *
 *  Ref: End-to-End Bandwidth Estimation for Congestion Control in Packet Networks
 *  Grieco and Mascolo
 *  (Westwood+)
 *
 *  Adapted from: Linux kernel tcp_westwood.c (GPLv2)
 *
 *  @since 0.9.46
 */
class SimpleBandwidthEstimator implements BandwidthEstimator {

    private final I2PAppContext _context;
    private final Log _log;
    private final ConnectionOptions _opts;

    private long _tAck;
    // bw_est, bw_ns_est
    private float _bKFiltered, _bK_ns_est;
    // bk
    private int _acked;

    // As in kernel tcp_westwood.c
    // Should probably match ConnectionOptions.TCP_ALPHA
    private static final int DECAY_FACTOR = 8;
    private static final int WESTWOOD_RTT_MIN = 500;

    SimpleBandwidthEstimator(I2PAppContext ctx, ConnectionOptions opts) {
        _log = ctx.logManager().getLog(SimpleBandwidthEstimator.class);
        _context = ctx;
        _opts = opts;
        // assume we're about to send something
        _tAck = ctx.clock().now();
        _acked = -1;
    }

    /**
     * Records an arriving ack.
     * @param acked how many packets were acked with this ack
     */
    public synchronized void addSample(int acked) {
        long now = _context.clock().now();
        if (_acked < 0) {
            // first sample
            // use time since constructed as the RTT
            // getRTT() would return zero here.
            long deltaT = Math.max(now - _tAck, WESTWOOD_RTT_MIN);
            float bkdt = ((float) acked) / deltaT;
            _bKFiltered = bkdt;
            _bK_ns_est = bkdt;
            _acked = 0;
            _tAck = now;
            if (_log.shouldDebug())
                _log.debug("first sample packets: " + acked + " deltaT: " + deltaT + ' ' + this);
        } else {
            _acked += acked;
            // anti-aliasing filter
            // As in kernel tcp_westwood.c
            // and the Westwood+ paper
            if (now - _tAck >= Math.max(_opts.getRTT(), WESTWOOD_RTT_MIN))
                computeBWE(now);
        }
    }

    /**
     * @return the current bandwidth estimate in packets/ms.
     */
    public synchronized float getBandwidthEstimate() {
        long now = _context.clock().now();
        // anti-aliasing filter
        // As in kernel tcp_westwood.c
        // and the Westwood+ paper
        if (now - _tAck >= Math.max(_opts.getRTT(), WESTWOOD_RTT_MIN))
            return computeBWE(now);
        return _bKFiltered;
    }

    private synchronized float computeBWE(final long now) {
        if (_acked < 0)
            return 0.0f; // nothing ever sampled
        updateBK(now, _acked);
        _acked = 0;
        return _bKFiltered;
    }

    /**
     * Optimized version of updateBK with packets == 0
     */
    private void decay() {
        _bK_ns_est *= (DECAY_FACTOR - 1) / (float) DECAY_FACTOR;
        _bKFiltered = westwood_do_filter(_bKFiltered, _bK_ns_est);
    }

    /**
     * Here we insert virtual null samples if necessary as in Westwood,
     * And use a very simple EWMA (exponential weighted moving average)
     * time-varying filter, as in kernel tcp_westwood.c
     * 
     * @param time the time of the measurement
     * @param packets number of packets acked
     */
    private void updateBK(long time, int packets) {
        long deltaT = time - _tAck;
        int rtt = Math.max(_opts.getRTT(), WESTWOOD_RTT_MIN);
        if (deltaT > 2 * rtt) {
            // Decay with virtual null samples as in the Westwood paper
            int numrtts = Math.min((int) ((deltaT / rtt) - 1), 2 * DECAY_FACTOR);
            for (int i = 0; i < numrtts; i++) {
                decay();
            }
            deltaT -= numrtts * rtt;
            if (_log.shouldDebug())
                _log.debug("decayed " + numrtts + " times, new _bK_ns_est: " + _bK_ns_est + ' ' + this);
        }
        float bkdt;
        if (packets > 0) {
            // As in kernel tcp_westwood.c
            bkdt = ((float) packets) / deltaT;
            _bK_ns_est = westwood_do_filter(_bK_ns_est, bkdt);
            _bKFiltered = westwood_do_filter(_bKFiltered, _bK_ns_est);
        } else {
            bkdt = 0;
            decay();
        }
        _tAck = time;
        if (_log.shouldDebug())
            _log.debug("computeBWE packets: " + packets + " deltaT: " + deltaT +
                       " bk/deltaT: " + bkdt + " _bK_ns_est: " + _bK_ns_est + ' ' + this);
    }

    /**
     *  As in kernel tcp_westwood.c
     */
    private static float westwood_do_filter(float a, float b) {
        return (((DECAY_FACTOR - 1) * a) + b) / DECAY_FACTOR;
    }

    @Override
    public synchronized String toString() {
        return "SBE[" +
                " _bKFiltered " + _bKFiltered +
                " _tAck " + _tAck + "; " +
                DataHelper.formatSize2Decimal((long) (_bKFiltered * 1000 * _opts.getMaxMessageSize()), false) +
                "Bps]";
    }
}
