package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

import net.i2p.I2PAppContext;

/**
 * Render a log record according to the log manager's settings
 *
 */
class LogRecordFormatter {
    private final static String NL = System.getProperty("line.separator");
    // arbitrary max length for the classname property (this makes is it lines up nicely)
    private final static int MAX_WHERE_LENGTH = 30;
    // if we're going to have one for where... be consistent
    private final static int MAX_THREAD_LENGTH = 12;
    private final static int MAX_PRIORITY_LENGTH = 5;

    public static String formatRecord(LogManager manager, LogRecord rec) {
        return formatRecord(manager, rec, true);
    }

    /**
     *  @param showDate if false, skip any date in the format (use when writing to wrapper log)
     *  @since 0.8.2
     */
    static String formatRecord(LogManager manager, LogRecord rec, boolean showDate) {
        int size = 128 + rec.getMessage().length();
        if (rec.getThrowable() != null)
            size += 512;
        StringBuilder buf = new StringBuilder(size);
        char format[] = manager.getFormat();
        for (int i = 0; i < format.length; ++i) {
            switch (format[i]) {
            case LogManager.DATE:
                if (showDate)
                    buf.append(getWhen(manager, rec));
                else if (i+1 < format.length && format[i+1] == ' ')
                    i++;  // skip following space
                break;
            case LogManager.CLASS:
                buf.append(getWhere(rec));
                break;
            case LogManager.THREAD:
                buf.append(getThread(rec));
                break;
            case LogManager.PRIORITY:
                buf.append(getPriority(rec, manager.getContext()));
                break;
            case LogManager.MESSAGE:
                buf.append(getWhat(rec));
                break;
            default:
                buf.append(format[i]);
                break;
            }
        }
        buf.append(NL);
        if (rec.getThrowable() != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            PrintWriter pw = new PrintWriter(baos, true);
            rec.getThrowable().printStackTrace(pw);
            try {
                pw.flush();
                baos.flush();
            } catch (IOException ioe) { // nop
            }
            byte tb[] = baos.toByteArray();
            buf.append(new String(tb));
        }
        return buf.toString();
    }

    private static String getThread(LogRecord logRecord) {
        return toString(logRecord.getThreadName(), MAX_THREAD_LENGTH);
    }

    private static String getWhen(LogManager manager, LogRecord logRecord) {
        return manager.getDateFormat().format(new Date(logRecord.getDate()));
    }

    /** don't translate */
/****
    private static String getPriority(LogRecord rec) {
        return toString(Log.toLevelString(rec.getPriority()), MAX_PRIORITY_LENGTH);
    }
****/

    /** */
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /** translate @since 0.7.14 */
    private static String getPriority(LogRecord rec, I2PAppContext ctx) {
        int len;
        if (Translate.getLanguage(ctx).equals("de"))
            len = 8;  // KRITISCH
        else
            len = MAX_PRIORITY_LENGTH;
        return toString(Translate.getString(Log.toLevelString(rec.getPriority()), ctx, BUNDLE_NAME), len);
    }

    private static String getWhat(LogRecord rec) {
        return rec.getMessage();
    }

    private static String getWhere(LogRecord rec) {
        String src = (rec.getSource() != null ? rec.getSource().getName() : rec.getSourceName());
        if (src == null) src = "<none>";
        return toString(src, MAX_WHERE_LENGTH);
    }

    /** truncates or pads to the specified size */
    private static String toString(String str, int size) {
        StringBuilder buf = new StringBuilder();
        if (str == null) str = "";
        if (str.length() > size) str = str.substring(str.length() - size);
        buf.append(str);
        while (buf.length() < size)
            buf.append(' ');
        return buf.toString();
    }
}
