package org.rrd4j.graph;

import java.util.Calendar;
import java.util.Locale;

/**
 * Simplified version of DateFormat for just defining how to map a timestamp into a label for
 * presentation.
 */
public interface TimeLabelFormat {
    /**
     * Format a timestamp.
     *
     * @param calendar   calendar to use for the formatter
     * @param locale     locale that will be used with {@code String.format}
     * @return           formatted string for the timestamp
     */
    String format(Calendar calendar, Locale locale);
}
