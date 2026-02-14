package net.i2p.util;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 *  A "synthetic" queue in that it doesn't actually queue anything.
 *  Actual queueing is assumed to be "dowstream" of this.
 *
 *  Maintains an average estimated "queue size" assuming a constant output rate
 *  declared in the constructor. The queue size is measured in bytes.
 *
 *  With offer(), will return true for "accepted" or false for "dropped",
 *  based on the RED algorithm which uses the current average queue size
 *  and the offered data size to calculate a drop probability.
 *  Bandwidth is not directly used in the RED algorithm, except to
 *  synthetically calculate an average queue size assuming the
 *  queue is being drained precisely at that rate, byte-by-byte
 *  (not per-packet).
 *
 *  addSample() unconditionally accepts the packet.
 *
 *  Also maintains a Westwood+ bandwidth estimator.
 *  The bandwidth and queue size estimates are only updated if the
 *  packet is "accepted".
 *
 *  The average queue size is calculated in the same manner as the
 *  bandwidth, with an update every WESTWOOD_RTT_MIN ms.
 *  Both estimators use
 *  a first stage anti-aliasing low pass filter based on RTT,
 *  and the time-varying Westwood filter based on inter-arrival time.
 *
 *  Ref: Random Early Detection Gateways for Congestion Avoidance
 *  Sally Floyd and Van Jacobson
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
 *  @since 0.9.50 adapted from streaming; moved from transport in 0.9.62
 */
public class SyntheticREDQueue implements BandwidthEstimator {

    private final I2PAppContext _context;
    private final Log _log;

    private long _tAck;
    // bw_est, bw_ns_est
    private float _bKFiltered, _bK_ns_est;
    // bk
    private int _acked;

    // RED vars
    // pkts since last dropped pkt
    private int _count = -1;
    // smoothed average queue size in bytes
    private float _avgQSize;
    // last sample queue size in bytes
    private float _qSize;
    // current interval newly queued in bytes, since the last updateQSize()
    private int _newDataSize;
    // last time _avgQSize calculated
    private long _tQSize;
    // min queue size threshold, in bytes, to start dropping
    private final int _minth;
    // max queue size, in bytes, before dropping everything
    private final int _maxth;
    // bandwidth in bytes per second, as passed to the constructor.
    private final int _bwBps;
    // bandwidth in bytes per ms. The queue is drained at this rate.
    private final float _bwBpms;
    // As in RED paper
    private static final float MAXP = 0.02f;

    // As in kernel tcp_westwood.c
    private static final int DECAY_FACTOR = 8;
    private static final int WESTWOOD_RTT_MIN = 500;
    // denominator of time, 1/x seconds of traffic in the queue
    private static final int DEFAULT_LOW_THRESHOLD = 13;
    // denominator of time, 1/x seconds of traffic in the queue
    private static final int DEFAULT_HIGH_THRESHOLD = 3;

    /**
     *  Default thresholds.
     *  Min: 100 ms of traffic; max: 500 ms.
     *
     *  @param bwBps the output rate of the queue in Bps
     */
    public SyntheticREDQueue(I2PAppContext ctx, int bwBps) {
        // the goal is to drop here rather than let the traffic
        // get through to UDP-Sender CoDel and get dropped there,
        // when we're at the default 80% share or below.
        // That CoDel starts dropping when above 100 ms latency for 500 ms.
        // let's try the same 100 ms of traffic here.
        this(ctx, bwBps, bwBps / DEFAULT_LOW_THRESHOLD, bwBps / DEFAULT_HIGH_THRESHOLD);
    }

    /**
     *  Specified queue size thresholds.
     *  offer() drops a 1024 byte packet at 2% probability just lower than maxThKB,
     *  and at 100% probability higher than maxThKB.
     *
     *  @param bwBps the output rate of the queue in Bps
     *  @param minThB the minimum queue size to start dropping in Bytes
     *  @param maxThB the queue size to drop everything in Bytes
     */
    SyntheticREDQueue(I2PAppContext ctx, int bwBps, int minThB, int maxThB) {
        _log = ctx.logManager().getLog(SyntheticREDQueue.class);
        _context = ctx;
        // assume we're about to send something
        _tAck = ctx.clock().now();
        _acked = -1;
        _minth = minThB;
        _maxth = maxThB;
        _bwBps = bwBps;
        _bwBpms = bwBps / 1000f;
        _tQSize = _tAck;
        if (_log.shouldDebug())
            _log.debug("Configured " + bwBps + " BPS, min: " + minThB + " B, max: " + maxThB + " B");
    }

    /**
     *
     * Nominal bandwidth limit in bytes per second, as passed to the constructor.
     *
     */
    public int getMaxBandwidth() {
        return _bwBps;
    }

    /**
     * Unconditional, never drop.
     * The queue size and bandwidth estimates will be updated.
     */
    public void addSample(int size) {
        offer(size, 0);
    }

    /**
     * Should we drop this packet?
     * If accepted, the queue size and bandwidth estimates will be updated.
     *
     * @param size how many bytes to be offered
     * @param factor how to adjust the size for the drop probability calculation,
     *        or 1.0 for standard probability. 0 to prevent dropping.
     *        Does not affect bandwidth calculations.
     * @return true for accepted, false for drop
     */
    public boolean offer(int size, float factor) {
        long now = _context.clock().now();
        return addSample(size, factor, now);
    }

    private synchronized boolean addSample(int acked, float factor, long now) {
        if (_acked < 0) {
            // first sample
            // use time since constructed as the RTT
            long deltaT = Math.max(now - _tAck, WESTWOOD_RTT_MIN);
            float bkdt = ((float) acked) / deltaT;
            _bKFiltered = bkdt;
            _bK_ns_est = bkdt;
            _acked = 0;
            _tAck = now;
            _tQSize = now;
            _newDataSize = acked;
            if (_log.shouldDebug())
                _log.debug("first sample bytes: " + acked + " deltaT: " + deltaT + ' ' + this);
            return true;
        } else {
            // update queue size average if necessary
            // the current sample is not included in the calculation
            long deltaT = now - _tQSize;
            if (deltaT > WESTWOOD_RTT_MIN)
                updateQSize(now, deltaT);
            if (factor > 0) {
                // drop calculation
                if (_avgQSize > _maxth) {
                    if (_log.shouldWarn())
                        _log.warn("drop bytes (qsize): " + acked + ' ' + this);
                    _count = 0;
                    return false;
                }
                if (_avgQSize > _minth) {
                    _count++;
                    float pb = (acked / 1024f) * factor * MAXP * (_avgQSize - _minth) / (_maxth - _minth);
                    float pa = pb / (1 - (_count * pb));
                    float rand = _context.random().nextFloat();
                    if (rand < pa) {
                        if (_log.shouldWarn())
                            _log.warn("drop bytes (prob): " + acked + " factor " + factor + " prob: " + pa + " deltaT: " + deltaT + ' ' + this);
                        _count = 0;
                        return false;
                    }
                    _count = -1;
                }
            }
            // accepted
            _newDataSize += acked;
            _acked += acked;
            // update bandwidth estimate if necessary
            deltaT = now - _tAck;
            if (deltaT >= WESTWOOD_RTT_MIN)
                computeBWE(now, (int) deltaT);
            if (_log.shouldDebug())
                _log.debug("accept bytes: " + acked + " factor " + factor + ' ' + this);
            return true;
        }
    }

    /**
     * @return the current bandwidth estimate in bytes/ms.
     */
    public float getBandwidthEstimate() {
        long now = _context.clock().now();
        // anti-aliasing filter
        // As in kernel tcp_westwood.c
        // and the Westwood+ paper
        synchronized(this) {
            long deltaT = now - _tAck;
            if (deltaT >= WESTWOOD_RTT_MIN)
                return computeBWE(now, (int) deltaT);
            return _bKFiltered;
        }
    }

    /**
     * @return the current queue size estimate in bytes.
     */
    public float getQueueSizeEstimate() {
        long now = _context.clock().now();
        // anti-aliasing filter
        // As in kernel tcp_westwood.c
        // and the Westwood+ paper
        synchronized(this) {
            long deltaT = now - _tQSize;
            if (deltaT >= WESTWOOD_RTT_MIN)
                updateQSize(now, deltaT);
            return _avgQSize;
        }
    }

    private synchronized float computeBWE(final long now, final int rtt) {
        if (_acked < 0)
            return 0.0f; // nothing ever sampled
        updateBK(now, _acked, rtt);
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

    private void decayQueue(int rtt) {
        _qSize -= rtt * _bwBpms;
        if (_qSize < 1)
            _qSize = 0;
        _avgQSize = westwood_do_filter(_avgQSize, _qSize);
    }

    /**
     * Here we insert virtual null samples if necessary as in Westwood,
     * And use a very simple EWMA (exponential weighted moving average)
     * time-varying filter, as in kernel tcp_westwood.c
     * 
     * @param time the time of the measurement
     * @param packets number of bytes acked
     * @param rtt current rtt
     */
    private void updateBK(long time, int packets, int rtt) {
        long deltaT = time - _tAck;
        if (rtt < WESTWOOD_RTT_MIN)
            rtt = WESTWOOD_RTT_MIN;
        if (deltaT > 2 * rtt) {
            // Decay with virtual null samples as in the Westwood paper
            int numrtts = Math.min((int) ((deltaT / rtt) - 1), 2 * DECAY_FACTOR);
            for (int i = 0; i < numrtts; i++) {
                if (_bKFiltered <= 0)
                    break;
                decay();
            }
            deltaT -= numrtts * rtt;
            //if (_log.shouldDebug())
            //    _log.debug("decayed " + numrtts + " times, new _bK_ns_est: " + _bK_ns_est + ' ' + this);
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
        //if (_log.shouldDebug())
        //    _log.debug("computeBWE bytes: " + packets + " deltaT: " + deltaT +
        //               " bk/deltaT: " + bkdt + " _bK_ns_est: " + _bK_ns_est + ' ' + this);
    }


    /**
     * Here we insert virtual null samples if necessary as in Westwood,
     * And use a very simple EWMA (exponential weighted moving average)
     * time-varying filter, as in kernel tcp_westwood.c
     * 
     * @param time the time of the measurement
     * @param deltaT at least WESTWOOD_RTT_MIN
     */
    private void updateQSize(long time, long deltaT) {
        long origDT = deltaT;
        if (deltaT > 2 * WESTWOOD_RTT_MIN) {
            // Decay with virtual null samples as in the Westwood paper
            int numrtts = Math.min((int) ((deltaT / WESTWOOD_RTT_MIN) - 1), 2 * DECAY_FACTOR);
            for (int i = 0; i < numrtts; i++) {
                if (_avgQSize <= 0)
                    break;
                decayQueue(WESTWOOD_RTT_MIN);
            }
            deltaT -= numrtts * WESTWOOD_RTT_MIN;
            //if (_log.shouldDebug())
            //    _log.debug("decayed " + numrtts + " times, new _bK_ns_est: " + _bK_ns_est + ' ' + this);
        }
        int origNDS = _newDataSize;
        float newQSize = _newDataSize;
        if (_newDataSize > 0) {
            newQSize -= deltaT * _bwBpms;
            if (newQSize < 1)
                newQSize = 0;
            _qSize = westwood_do_filter(_qSize, newQSize);
            _avgQSize = westwood_do_filter(_avgQSize, _qSize);
            _newDataSize = 0;
        } else {
            decayQueue((int) deltaT);
        }
        _tQSize = time;
        if (_log.shouldDebug())
            _log.debug("computeQS deltaT: " + origDT +
                       " newData: " + origNDS +
                       " newQsize: " + newQSize + " qSize: " + _qSize + ' ' + this);
    }

    /**
     *  As in kernel tcp_westwood.c
     */
    private static float westwood_do_filter(float a, float b) {
        return (((DECAY_FACTOR - 1) * a) + b) / DECAY_FACTOR;
    }

    /**
     * Clear all data in the limiters
     *
     * @since 0.9.69
     */
    public synchronized void clear() {
        _tAck = _context.clock().now();
        _acked = -1;
        _tQSize = _tAck;
        _avgQSize = 0;
        _bKFiltered = 0;
        _bK_ns_est = 0;
    }

    @Override
    public synchronized String toString() {
        return "SREDQ[" +
                //" _bKFiltered " + _bKFiltered +
                //" _tAck " + _tAck + "; " +
                //" _tQSize " + _tQSize +
                ' ' + DataHelper.formatSize2Decimal((long) (_bKFiltered * 1000), false) +
                "Bps, avg_qsize " +
                DataHelper.formatSize2((long) _avgQSize, false) +
                "B, limit " +
                DataHelper.formatSize2Decimal((long) _bwBps, false) +
                "Bps]";
    }
}
