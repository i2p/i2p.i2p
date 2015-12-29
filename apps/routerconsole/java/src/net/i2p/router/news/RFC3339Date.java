package net.i2p.router.news;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import net.i2p.util.SystemVersion;

/**
 *  Adapted from net.i2p.router.util.RFC822Date.
 *  This only supports parsing of the dates specified by Atom (RFC 4287)
 *  and a couple of others.
 *  In particular, 'T' is required, and either 'Z' or a numeric timezone offset is required,
 *  unless there's no time fields at all.
 *
 *  The full variety of RFC 3339 (ISO 8601) dates is not supported by the parser,
 *  but they could be added in the future.
 *
 *  See also: http://stackoverflow.com/questions/6038136/how-do-i-parse-rfc-3339-datetimes-with-java
 *
 *  @since 0.9.17
 */
public abstract class RFC3339Date {

    // SimpleDateFormat is not thread-safe, methods must be synchronized
    private static final SimpleDateFormat OUTPUT_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

    private static final String TZF1, TZF2;
    static {
        // Android's SimpleDateFormat doesn't support XXX at any API
        if (SystemVersion.isJava7() && !SystemVersion.isAndroid()) {
            // ISO 8601
            // These handle timezones like +1000, +10, and +10:00
            TZF1 = "yyyy-MM-dd'T'HH:mm:ssXXX";
            TZF2 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
        } else {
            // These handle timezones like +1000
            // These do NOT handle timezones like +10:00
            // This is fixed below
            TZF1 = "yyyy-MM-dd'T'HH:mm:ssZZZZZ";
            TZF2 = "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ";
        }
    }

    /**
     *  This only supports parsing of the dates specified by Atom, RFC 4287,
     *  together with the date only.
     */
    private static final SimpleDateFormat rfc3339DateFormats[] = new SimpleDateFormat[] {
                 OUTPUT_FORMAT,
                 // .S or .SS will get the milliseconds wrong,
                 // e.g. .1 will become 1 ms, .11 will become 11 ms
                 // This is NOT fixed below
                 new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
                 new SimpleDateFormat(TZF1, Locale.US),
                 new SimpleDateFormat(TZF2, Locale.US),
                 new SimpleDateFormat("yyyy-MM-dd", Locale.US),
                 // old school for backward compatibility
                 new SimpleDateFormat("yyyy/MM/dd", Locale.US)
    };

    //
    // The router JVM is forced to UTC but do this just in case
    //
    static {
        TimeZone utc = TimeZone.getTimeZone("GMT");
        for (int i = 0; i < rfc3339DateFormats.length; i++) {
            rfc3339DateFormats[i].setTimeZone(utc);
        }
    }

    /**
     * Parse the date
     *
     * @param s non-null
     * @return -1 on failure
     */
    public synchronized static long parse3339Date(String s) {
        s = s.trim();
        // strip the ':' out of the time zone, if present,
        // for Java 6 where we don't have the 'X' format
        int len = s.length();
        if ((!SystemVersion.isJava7() || SystemVersion.isAndroid()) &&
            s.charAt(len - 1) != 'Z' &&
            s.charAt(len - 3) == ':' &&
            (s.charAt(len - 6) == '+' || s.charAt(len - 6) == '-')) {
            s = s.substring(0, len - 3) + s.substring(len - 2);
        }
        for (int i = 0; i < rfc3339DateFormats.length; i++) {
            try {
                Date date = rfc3339DateFormats[i].parse(s);
                if (date != null)
                    return date.getTime();
            } catch (ParseException pe) {}
        }
        return -1;
    }

    /**
     * Format is "yyyy-MM-ddTHH:mm:ssZ"
     */
    public synchronized static String to3339Date(long t) {
        return OUTPUT_FORMAT.format(new Date(t));
    }

/****
    public static void main(String[] args) {
        if (args.length == 1) {
            try {
                System.out.println(to3339Date(Long.parseLong(args[0])));
            } catch (NumberFormatException nfe) {
                System.out.println(parse3339Date(args[0]));
            }
        } else {
            System.out.println("Usage: RFC3339Date numericDate|stringDate");
        }
    }
****/
}
