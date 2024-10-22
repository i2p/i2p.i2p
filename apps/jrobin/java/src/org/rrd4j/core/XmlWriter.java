package org.rrd4j.core;

import java.awt.*;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Locale;

/**
 * Extremely simple utility class used to create XML documents.
 */
public class XmlWriter implements AutoCloseable {
    static final String INDENT_STR = "   ";
    private static final String STYLE = "style";
    private static final DateTimeFormatter ISOLIKE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSZ")
                                                                      .withLocale(Locale.ENGLISH)
                                                                      .withZone(ZoneId.of("UTC"));
    private static final String DEFAULT_NAN_STRING = Double.toString(Double.NaN);

    @FunctionalInterface
    public interface DoubleFormater {
        String format(double value, String nanString);
    }

    private final PrintWriter writer;
    private final StringBuilder indent = new StringBuilder();
    private final Deque<String> openTags = new LinkedList<>();
    private final DateTimeFormatter timeFormatter;
    private final DoubleFormater doubleFormatter;

    private XmlWriter(PrintWriter writer, DateTimeFormatter timeFormatter, DoubleFormater doubleFormatter) {
        this.writer = writer;
        this.timeFormatter = timeFormatter;
        this.doubleFormatter = doubleFormatter;
    }

    /**
     * Creates XmlWriter with the specified {@link OutputStream} to send XML code to.
     *
     * @param stream {@link OutputStream} which receives XML code
     */
    public XmlWriter(OutputStream stream) {
        writer = new PrintWriter(stream, true);
        timeFormatter = ISOLIKE;
        this.doubleFormatter = (d, n) -> Util.formatDouble(d, n,true);
    }

    /**
     * Creates XmlWriter with the specified output stream to send XML code to.
     *
     * @param stream Output stream which receives XML code
     * @param autoFlush is the stream to be flushed automatically
     */
    public XmlWriter(OutputStream stream, boolean autoFlush) {
        writer = new PrintWriter(stream, autoFlush);
        timeFormatter = ISOLIKE;
        this.doubleFormatter = (d, n) -> Util.formatDouble(d, n,true);
    }

    /**
     * Creates XmlWriter with the specified {@link PrintWriter} to send XML code to.
     *
     * @param stream {@link PrintWriter} which receives XML code
     */
    public XmlWriter(PrintWriter stream) {
        writer = stream;
        timeFormatter = ISOLIKE;
        this.doubleFormatter = (d, n) -> Util.formatDouble(d, n,true);
    }

    /**
     * Return a new {@link XmlWriter} that will format time stamp as ISO 8601 with this explicit time zone {@link ZoneId}
     * @param zid
     * @return
     */
    public XmlWriter withTimeZone(ZoneId zid) {
        if (indent.length() != 0 || !openTags.isEmpty()) {
            throw new IllegalStateException("Can't be used on a already used XmlWriter");
        }
        DateTimeFormatter dtf = this.timeFormatter.withZone(zid);
        return new XmlWriter(writer, dtf, doubleFormatter);
    }

    /**
     * Return a new {@link XmlWriter} that will format time stamp using this {@link ZoneId}
     * @param doubleFormatter
     * @return
     */
    public XmlWriter withDoubleFormatter(DoubleFormater doubleFormatter) {
        if (indent.length() != 0 || !openTags.isEmpty()) {
            throw new IllegalStateException("Can't be used on a already used XmlWriter");
        }
        return new XmlWriter(writer, timeFormatter, doubleFormatter);
    }

    /**
     * Opens XML tag
     *
     * @param tag XML tag name
     */
    public void startTag(String tag) {
        writer.println(indent + "<" + tag + ">");
        openTags.push(tag);
        indent.append(INDENT_STR);
    }

    /**
     * Closes the corresponding XML tag
     */
    public void closeTag() {
        String tag = openTags.pop();
        indent.setLength(indent.length() - INDENT_STR.length());
        writer.println(indent + "</" + tag + ">");
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, Object value) {
        if (value != null) {
            writer.println(indent + "<" + tag + ">" +
                    escape(value.toString()) + "</" + tag + ">");
        }
        else {
            writer.println(indent + "<" + tag + "></" + tag + ">");
        }
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, int value) {
        writeTag(tag, Integer.toString(value));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, long value) {
        writeTag(tag, Long.toString(value));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     * @param nanString a {@link java.lang.String} object.
     */
    public void writeTag(String tag, double value, String nanString) {
        writeTag(tag, doubleFormatter.format(value, nanString));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, double value) {
        writeTag(tag, doubleFormatter.format(value, DEFAULT_NAN_STRING));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, boolean value) {
        writeTag(tag, Boolean.toString(value));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, Color value) {
        int rgb = value.getRGB() & 0xFFFFFF;
        writeTag(tag, "#" + Integer.toHexString(rgb).toUpperCase());
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, Font value) {
        startTag(tag);
        writeTag("name", value.getName());
        int style = value.getStyle();
        if ((style & Font.BOLD) != 0 && (style & Font.ITALIC) != 0) {
            writeTag(STYLE, "BOLDITALIC");
        }
        else if ((style & Font.BOLD) != 0) {
            writeTag(STYLE, "BOLD");
        }
        else if ((style & Font.ITALIC) != 0) {
            writeTag(STYLE, "ITALIC");
        }
        else {
            writeTag(STYLE, "PLAIN");
        }
        writeTag("size", value.getSize());
        closeTag();
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, File value) {
        writeTag(tag, value.getPath());
    }

    /**
     * Flushes the output stream
     */
    public void flush() {
        writer.flush();
    }

    /**
     * Writes XML comment to output stream
     *
     * @param comment comment string
     */
    public void writeComment(Object comment) {
        writer.println(indent + "<!-- " + escape(comment.toString()) + " -->");
    }

    /**
     * Writes a timestamp using the configured {@link DateTimeFormatter} as an XML comment to output stream
     *
     * @param timestamp
     */
    public void writeComment(long timestamp) {
        writer.println(indent + "<!-- " + escape(formatTimestamp(timestamp)) + " -->");
    }

    /**
     * Format a timestamp using the configured {@link DateTimeFormatter}
     *
     * @param timestamp
     * @return
     */
    public String formatTimestamp(long timestamp) {
         return timeFormatter.format(Instant.ofEpochSecond(timestamp));
    }

    private static String escape(String s) {
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        writer.flush();
        writer.close();
    }

}
