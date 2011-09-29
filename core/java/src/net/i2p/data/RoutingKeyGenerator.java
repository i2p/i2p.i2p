package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA256Generator;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Component to manage the munging of hashes into routing keys - given a hash, 
 * perform some consistent transformation against it and return the result.
 * This transformation is fed by the current "mod data".  
 *
 * Right now the mod data is the current date (GMT) as a string: "yyyyMMdd",
 * and the transformation takes the original hash, appends the bytes of that mod data,
 * then returns the SHA256 of that concatenation.
 *
 * Do we want this to simply do the XOR of the SHA256 of the current mod data and
 * the key?  does that provide the randomization we need?  It'd save an SHA256 op.
 * Bah, too much effort to think about for so little gain.  Other algorithms may come
 * into play layer on about making periodic updates to the routing key for data elements
 * to mess with Sybil.  This may be good enough though.
 *
 * Also - the method generateDateBasedModData() should be called after midnight GMT 
 * once per day to generate the correct routing keys!
 *
 */
public class RoutingKeyGenerator {
    private final Log _log;
    private final I2PAppContext _context;

    public RoutingKeyGenerator(I2PAppContext context) {
        _log = context.logManager().getLog(RoutingKeyGenerator.class);
        _context = context;
    }

    public static RoutingKeyGenerator getInstance() {
        return I2PAppContext.getGlobalContext().routingKeyGenerator();
    }
    
    private byte _currentModData[];
    private long _lastChanged;

    private final static Calendar _cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
    private final static SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd");

    public byte[] getModData() {
        return _currentModData;
    }

    public long getLastChanged() {
        return _lastChanged;
    }

    public void setModData(byte modData[]) {
        _currentModData = modData;
        _lastChanged = _context.clock().now();
    }

    /**
     * Update the current modifier data with some bytes derived from the current
     * date (yyyyMMdd in GMT)
     *
     */
    public void generateDateBasedModData() {
        Date today = null;
        long now = _context.clock().now();
        synchronized (_cal) {
            _cal.setTime(new Date(now));
            _cal.set(Calendar.YEAR, _cal.get(Calendar.YEAR));               // gcj <= 4.0 workaround
            _cal.set(Calendar.DAY_OF_YEAR, _cal.get(Calendar.DAY_OF_YEAR)); // gcj <= 4.0 workaround
            _cal.set(Calendar.HOUR_OF_DAY, 0);
            _cal.set(Calendar.MINUTE, 0);
            _cal.set(Calendar.SECOND, 0);
            _cal.set(Calendar.MILLISECOND, 0);
            today = _cal.getTime();
        }
        
        byte mod[] = null;
        String modVal = null;
        synchronized (_fmt) {
            modVal = _fmt.format(today);
        }
        mod = new byte[modVal.length()];
        for (int i = 0; i < modVal.length(); i++)
            mod[i] = (byte)(modVal.charAt(i) & 0xFF);
        if (_log.shouldLog(Log.INFO))
            _log.info("Routing modifier generated: " + modVal);
        setModData(mod);
    }
    
    /**
     * Generate a modified (yet consistent) hash from the origKey by generating the
     * SHA256 of the targetKey with the current modData appended to it, *then* 
     *
     * This makes Sybil's job a lot harder, as she needs to essentially take over the
     * whole keyspace.
     *
     * @throws IllegalArgumentException if origKey is null
     */
    public Hash getRoutingKey(Hash origKey) {
        if (origKey == null) throw new IllegalArgumentException("Original key is null");
        if (_currentModData == null) generateDateBasedModData();
        byte modVal[] = new byte[Hash.HASH_LENGTH + _currentModData.length];
        System.arraycopy(origKey.getData(), 0, modVal, 0, Hash.HASH_LENGTH);
        System.arraycopy(_currentModData, 0, modVal, Hash.HASH_LENGTH, _currentModData.length);
        return SHA256Generator.getInstance().calculateHash(modVal);
    }

/****
    public static void main(String args[]) {
        Hash k1 = new Hash();
        byte k1d[] = new byte[Hash.HASH_LENGTH];
        RandomSource.getInstance().nextBytes(k1d);
        k1.setData(k1d);

        for (int i = 0; i < 10; i++) {
            System.out.println("K1:  " + k1);
            Hash k1m = RoutingKeyGenerator.getInstance().getRoutingKey(k1);
            System.out.println("MOD: " + new String(RoutingKeyGenerator.getInstance().getModData()));
            System.out.println("K1M: " + k1m);
        }
        try {
            Thread.sleep(2000);
        } catch (Throwable t) { // nop
        }
    }
****/
}
