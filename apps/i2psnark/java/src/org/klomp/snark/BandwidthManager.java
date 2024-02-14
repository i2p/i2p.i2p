package org.klomp.snark;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SyntheticREDQueue;

/**
 *  Bandwidth and bandwidth limits
 *
 *  Maintain three bandwidth estimators:
 *  Sent, received, and requested.
 *
 *  There are three layers of BandwidthListeners:
 *<pre>
 *  BandwidthManager (total)
 *      PeerCoordinator (per-torrent)
 *          Peer/WebPeer (per-connection)
 *</pre>
 *
 *  Here at the top, we use SyntheticRedQueues for accurate
 *  and current moving averages of up, down, and requested bandwidth.
 *
 *  At the lower layers, simple weighted moving averages of
 *  three buckets of 40 seconds each (2 minutes total) are used
 *  for up and down, and requested is delegated here.
 *
 *  The lower layers must report to the next-higher layer.
 *
 *  At the Peer layer, we report inbound piece data per-read,
 *  not per-piece, to get a smoother inbound estimate.
 *
 *  Only the following data are counted by the BandwidthListeners:
 *<ul><li>Pieces (both Peer and WebPeer)
 *<li>ut_metadata
 *</ul>
 *
 *  No overhead at any layer is accounted for.
 *
 *  @since 0.9.62
 */
public class BandwidthManager implements BandwidthListener {

    private final I2PAppContext _context;
    private final Log _log;
    private SyntheticREDQueue _up, _down, _req;

    BandwidthManager(I2PAppContext ctx, int upLimit, int downLimit) {
        _context = ctx;
        _log = ctx.logManager().getLog(BandwidthManager.class);
        _up = new SyntheticREDQueue(ctx, upLimit);
        _down = new SyntheticREDQueue(ctx, downLimit);
        // Allow down limit a little higher based on testing
        // Allow req limit a little higher still because it uses RED
        // so it actually kicks in sooner.
        _req = new SyntheticREDQueue(ctx, downLimit * 110 / 100);
    }

    /**
     * Current limit in Bps
     */
    void setUpBWLimit(long upLimit) {
        int limit = (int) Math.min(upLimit, Integer.MAX_VALUE);
        if (limit != getUpBWLimit())
            _up = new SyntheticREDQueue(_context, limit);
    }

    /**
     * Current limit in Bps
     */
    void setDownBWLimit(long downLimit) {
        int limit = (int) Math.min(downLimit, Integer.MAX_VALUE);
        if (limit != getDownBWLimit()) {
            _down = new SyntheticREDQueue(_context, limit);
            _req = new SyntheticREDQueue(_context, limit * 110 / 100);
        }
    }

    /**
     * The average rate in Bps
     */
    long getRequestRate() {
        return (long) (1000f * _req.getBandwidthEstimate());
    }

    // begin BandwidthListener interface


    /**
     * The average rate in Bps
     */
    public long getUploadRate() {
        return (long) (1000f * _up.getBandwidthEstimate());
    }

    /**
     * The average rate in Bps
     */
    public long getDownloadRate() {
        return (long) (1000f * _down.getBandwidthEstimate());
    }

    /**
     * We unconditionally sent this many bytes
     */
    public void uploaded(int size) {
        _up.addSample(size);
    }

    /**
     * We received this many bytes
     */
    public void downloaded(int size) {
        _down.addSample(size);
    }

    /**
     * Should we send this many bytes?
     * Do NOT call uploaded() if this returns true.
     */
    public boolean shouldSend(int size) {
        boolean rv = _up.offer(size, 1.0f);
        if (!rv && _log.shouldWarn())
            _log.warn("Deny sending " + size + " bytes, upload rate " + DataHelper.formatSize(getUploadRate()) + "Bps");
        return rv;
    }

    /**
     * Should we request this many bytes?
     *
     * @param peer ignored
     */
    public boolean shouldRequest(Peer peer, int size) {
        boolean rv = !overDownBWLimit() && _req.offer(size, 1.0f);
        if (!rv && _log.shouldWarn())
            _log.warn("Deny requesting " + size + " bytes, download rate " + DataHelper.formatSize(getDownloadRate()) + "Bps" +
                      ", request rate " + DataHelper.formatSize(getRequestRate()) + "Bps");
        return rv;
    }

    /**
     * Current limit in BPS
     */
    public long getUpBWLimit() {
        return _up.getMaxBandwidth();
    }

    /**
     * Current limit in BPS
     */
    public long getDownBWLimit() {
        return _down.getMaxBandwidth();
    }

    /**
     * Are we currently over the limit?
     */
    public boolean overUpBWLimit() {
        return getUploadRate() > getUpBWLimit();
    }

    /**
     * Are we currently over the limit?
     */
    public boolean overDownBWLimit() {
        return getDownloadRate() > getDownBWLimit();
    }

    /**
     *  In HTML for debug page
     */
    @Override
    public String toString() {
        return "<br><b>Bandwidth Limiters</b><br><b>Up:</b> " + _up +
               "<br><b>Down:</b> " + _down +
               "<br><b>Req:</b> " + _req +
               "<br>";
    }
}
