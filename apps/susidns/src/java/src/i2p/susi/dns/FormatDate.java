package i2p.susi.dns;

import java.util.Date;
import java.text.DateFormat;
import java.util.TimeZone;

import net.i2p.I2PAppContext;

/**
 * Format a date in local time zone
 * @since 0.8.7
 */
public abstract class FormatDate
{
    private static final DateFormat _dateFormat;

    static {
	DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
	// the router sets the JVM time zone to UTC but saves the original here so we can get it
	String systemTimeZone = I2PAppContext.getGlobalContext().getProperty("i2p.systemTimeZone");
	if (systemTimeZone != null)
		fmt.setTimeZone(TimeZone.getTimeZone(systemTimeZone));
	_dateFormat = fmt;
    }

    public static String format(long date)
    {
    	return _dateFormat.format(new Date(date));
    }
}
