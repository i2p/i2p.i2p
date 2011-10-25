package net.i2p.router.tunnel;

import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.ByteCache;
import net.i2p.util.DecayingBloomFilter;
import net.i2p.util.DecayingHashSet;

/**
 * Manage the IV validation for all of the router's tunnels by way of a big
 * decaying bloom filter.  
 *
 */
class BloomFilterIVValidator implements IVValidator {
    private final RouterContext _context;
    private final DecayingBloomFilter _filter;
    private final ByteCache _ivXorCache = ByteCache.getInstance(32, HopProcessor.IV_LENGTH);
    
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
    private static final long MIN_MEM_TO_USE_BLOOM = 64*1024*1024l;
    private static final long MIN_MEM_FOR_BIG_BLOOM = 128*1024*1024l;
    private static final long MIN_MEM_FOR_HUGE_BLOOM = 256*1024*1024l;
    /** for testing */
    private static final String PROP_FORCE = "router.forceDecayingBloomFilter";

    public BloomFilterIVValidator(RouterContext ctx, int KBps) {
        _context = ctx;
        // Select the filter based on share bandwidth and memory.
        // Note that at rates above 512KB, we increase the filter size
        // to keep acceptable false positive rates.
        // See DBF, BloomSHA1, and KeySelector for details.
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 96*1024*1024l;
        if (_context.getBooleanProperty(PROP_FORCE))
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV");  // 2MB fixed
        else if (KBps < MIN_SHARE_KBPS_TO_USE_BLOOM || maxMemory < MIN_MEM_TO_USE_BLOOM)
            _filter = new DecayingHashSet(ctx, HALFLIFE_MS, 16, "TunnelIVV"); // appx. 4MB max
        else if (KBps >= MIN_SHARE_KBPS_FOR_HUGE_BLOOM && maxMemory >= MIN_MEM_FOR_HUGE_BLOOM)
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", 25);  // 8MB fixed
        else if (KBps >= MIN_SHARE_KBPS_FOR_BIG_BLOOM && maxMemory >= MIN_MEM_FOR_BIG_BLOOM)
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", 24);  // 4MB fixed
        else
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV");  // 2MB fixed
        ctx.statManager().createRateStat("tunnel.duplicateIV", "Note that a duplicate IV was received", "Tunnels", 
                                         new long[] { 60*60*1000l });
    }
    
    public boolean receiveIV(byte ivData[], int ivOffset, byte payload[], int payloadOffset) {
        ByteArray buf = _ivXorCache.acquire();
        DataHelper.xor(ivData, ivOffset, payload, payloadOffset, buf.getData(), 0, HopProcessor.IV_LENGTH);
        boolean dup = _filter.add(buf.getData()); 
        _ivXorCache.release(buf);
        if (dup) _context.statManager().addRateData("tunnel.duplicateIV", 1);
        return !dup; // return true if it is OK, false if it isn't
    }

    public void destroy() { _filter.stopDecaying(); }
}
