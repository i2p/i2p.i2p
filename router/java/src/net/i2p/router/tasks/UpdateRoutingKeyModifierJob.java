package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Update the routing Key modifier every day at midnight (plus on startup).
 * This is done here because we want to make sure the key is updated before anyone
 * uses it.
 *
 * @since 0.8.12 moved from Router.java
 */
public class UpdateRoutingKeyModifierJob extends JobImpl {
    private final Log _log;
    private final Calendar _cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    // Run every 15 minutes in case of time zone change, clock skew, etc.
    private static final long MAX_DELAY_FAILSAFE = 15*60*1000;

    public UpdateRoutingKeyModifierJob(RouterContext ctx) { 
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
    }

    public String getName() { return "Update Routing Key Modifier"; }

    public void runJob() {
        // make sure we requeue quickly if just before midnight
        long delay = Math.min(MAX_DELAY_FAILSAFE, getTimeTillMidnight());
        // TODO tell netdb if mod data changed?
        getContext().routingKeyGenerator().generateDateBasedModData();
        requeue(delay);
    }

    private long getTimeTillMidnight() {
        long now = getContext().clock().now();
        _cal.setTime(new Date(now));
        _cal.set(Calendar.YEAR, _cal.get(Calendar.YEAR));               // gcj <= 4.0 workaround
        _cal.set(Calendar.DAY_OF_YEAR, _cal.get(Calendar.DAY_OF_YEAR)); // gcj <= 4.0 workaround
        _cal.add(Calendar.DATE, 1);
        _cal.set(Calendar.HOUR_OF_DAY, 0);
        _cal.set(Calendar.MINUTE, 0);
        _cal.set(Calendar.SECOND, 0);
        _cal.set(Calendar.MILLISECOND, 0);
        long then = _cal.getTime().getTime();
        long howLong = then - now;
        if (howLong < 0) // hi kaffe
            howLong = 24*60*60*1000l + howLong;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Time till midnight: " + howLong + "ms");
        return howLong;
    }
}
