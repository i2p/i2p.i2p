package net.i2p.router.tunnel;

import java.io.File;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.tasks.OOMListener;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;

/**
 * Manage the IV validation for all of the router's tunnels by way of a big
 * decaying bloom filter.  
 *
 */
class BloomFilterIVValidator implements IVValidator {
    private final RouterContext _context;
    private final DecayingBloomFilter _filter;
    
    /**
     * After 2*halflife, an entry is completely forgotten from the bloom filter.
     * To avoid the issue of overlap within different tunnels, this is set 
     * higher than it needs to be.
     *
     */
    private static final int HALFLIFE_MS = 10*60*1000;
    private static final int MIN_SHARE_KBPS_TO_USE_BLOOM = 64;
    private static final int MIN_SHARE_KBPS_FOR_BIG_BLOOM = 512;
    private static final int MIN_SHARE_KBPS_FOR_HUGE_BLOOM = 1536;
    private static final int MIN_SHARE_KBPS_FOR_HUGE2_BLOOM = 4096;
    private static final int MIN_SHARE_KBPS_FOR_HUGE3_BLOOM = 8192;
    private static final long MIN_MEM_TO_USE_BLOOM = 64*1024*1024l;
    private static final long MIN_MEM_FOR_BIG_BLOOM = 128*1024*1024l;
    private static final long MIN_MEM_FOR_HUGE_BLOOM = 256*1024*1024l;
    private static final long MIN_MEM_FOR_HUGE2_BLOOM = 384*1024*1024l;
    private static final long MIN_MEM_FOR_HUGE3_BLOOM = 512*1024*1024l;
    /** for testing */
    private static final String PROP_FORCE = "router.forceDecayingBloomFilter";
    /** for testing */
    private static final String PROP_DISABLE = "router.disableDecayingBloomFilter";

    /**
     *  @param KBps share bandwidth
     */
    public BloomFilterIVValidator(RouterContext ctx, int KBps) {
        _context = ctx;
        // Select the filter based on share bandwidth and memory.
        // Note that at rates above 512KB, we increase the filter size
        // to keep acceptable false positive rates.
        // See DBF, BloomSHA1, and KeySelector for details.
        long maxMemory = SystemVersion.getMaxMemory();
        if (_context.getBooleanProperty(PROP_FORCE)) {
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV");  // 2MB fixed
        } else if (_context.getBooleanProperty(PROP_DISABLE)) {
            _filter = null;
        } else if (KBps < MIN_SHARE_KBPS_TO_USE_BLOOM || maxMemory < MIN_MEM_TO_USE_BLOOM) {
            if (KBps >= MIN_SHARE_KBPS_TO_USE_BLOOM)
                warn(maxMemory, KBps, MIN_MEM_TO_USE_BLOOM, MIN_SHARE_KBPS_TO_USE_BLOOM);
            _filter = new DecayingHashSet(ctx, HALFLIFE_MS, 16, "TunnelIVV"); // appx. 4MB max
        } else if (KBps >= MIN_SHARE_KBPS_FOR_HUGE3_BLOOM && maxMemory >= MIN_MEM_FOR_HUGE3_BLOOM) {
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", 27);  // 32MB fixed
        } else if (KBps >= MIN_SHARE_KBPS_FOR_HUGE2_BLOOM && maxMemory >= MIN_MEM_FOR_HUGE2_BLOOM) {
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", 26);  // 16MB fixed
            if (KBps >= MIN_SHARE_KBPS_FOR_HUGE3_BLOOM)
                warn(maxMemory, KBps, MIN_MEM_FOR_HUGE3_BLOOM, MIN_SHARE_KBPS_FOR_HUGE3_BLOOM);
        } else if (KBps >= MIN_SHARE_KBPS_FOR_HUGE_BLOOM && maxMemory >= MIN_MEM_FOR_HUGE_BLOOM) {
            if (KBps >= MIN_SHARE_KBPS_FOR_HUGE2_BLOOM)
                warn(maxMemory, KBps, MIN_MEM_FOR_HUGE2_BLOOM, MIN_SHARE_KBPS_FOR_HUGE2_BLOOM);
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", 25);  // 8MB fixed
        } else if (KBps >= MIN_SHARE_KBPS_FOR_BIG_BLOOM && maxMemory >= MIN_MEM_FOR_BIG_BLOOM) {
            if (KBps >= MIN_SHARE_KBPS_FOR_HUGE_BLOOM)
                warn(maxMemory, KBps, MIN_MEM_FOR_HUGE_BLOOM, MIN_SHARE_KBPS_FOR_HUGE_BLOOM);
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", 24);  // 4MB fixed
        } else {
            if (KBps >= MIN_SHARE_KBPS_FOR_BIG_BLOOM)
                warn(maxMemory, KBps, MIN_MEM_FOR_BIG_BLOOM, MIN_SHARE_KBPS_FOR_BIG_BLOOM);
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV");  // 2MB fixed
        }
        ctx.statManager().createRateStat("tunnel.duplicateIV", "Note that a duplicate IV was received", "Tunnels", 
                                         new long[] { 60*60*1000l });
    }
    
    public boolean receiveIV(byte ivData[], int ivOffset, byte payload[], int payloadOffset) {
        if (_filter == null)  // testing only
            return true;
        byte[] buf = SimpleByteCache.acquire(HopProcessor.IV_LENGTH);
        DataHelper.xor(ivData, ivOffset, payload, payloadOffset, buf, 0, HopProcessor.IV_LENGTH);
        boolean dup = _filter.add(buf); 
        SimpleByteCache.release(buf);
        if (dup) _context.statManager().addRateData("tunnel.duplicateIV", 1);
        return !dup; // return true if it is OK, false if it isn't
    }

    public void destroy() {
        if (_filter != null)
            _filter.stopDecaying();
    }

    /** @since 0.9.20 */
    private void warn(long maxMemory, int KBps, long recMaxMem, int threshKBps) {
        if (SystemVersion.isAndroid())
            return;
        String path = OOMListener.getWrapperConfigPath(_context);
        String msg =
            "Configured for " + DataHelper.formatSize(KBps *1024L) +
            "Bps share bandwidth but only " +
            DataHelper.formatSize(maxMemory) + "B available memory.";
        if (_context.hasWrapper()) {
            msg += " Recommend increasing wrapper.java.maxmemory in " +
                   path;
        } else if (!SystemVersion.isWindows()) {
            msg += " Recommend increasing MAXMEMOPT in " +
                   _context.getBaseDir() + File.separatorChar + "runplain.sh or /usr/bin/i2prouter-nowrapper";
        } else {
            msg += " Recommend running the restartable version of I2P, and increasing wrapper.java.maxmemory in " +
                   path;
        }
        // getMaxMemory() returns significantly lower than wrapper config, so add 10%
        msg += " to at least " + (recMaxMem * 11 / 10 / (1024*1024)) + " (MB)" +
               " if the actual share bandwidth exceeds " +
               DataHelper.formatSize(threshKBps * 1024L) + "Bps.";
        System.out.println("WARN: " + msg);
        _context.logManager().getLog(BloomFilterIVValidator.class).logAlways(Log.WARN, msg);
    }
}
