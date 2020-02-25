package org.rrd4j.core;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Extremely simple utility class used to create XML documents.
 */
public class XmlWriter {
    static final String INDENT_STR = "   ";
    private static final String STYLE = "style";
    private static final ThreadLocal<SimpleDateFormat> ISOLIKE = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.ENGLISH);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf;
        }
    };

    private final PrintWriter writer;
    private final StringBuilder indent = new StringBuilder("");
    private final Deque<String> openTags = new LinkedList<>();

    /**
     * Creates XmlWriter with the specified output stream to send XML code to.
     *
     * @param stream Output stream which receives XML code
     */
    public XmlWriter(OutputStream stream) {
        writer = new PrintWriter(stream, true);
    }

    /**
     * Creates XmlWriter with the specified output stream to send XML code to.
     *
     * @param stream Output stream which receives XML code
     * @param autoFlush is the stream to be flushed automatically
     */
    public XmlWriter(OutputStream stream, boolean autoFlush) {
        writer = new PrintWriter(stream, autoFlush);
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
        writeTag(tag, Util.formatDouble(value, nanString, true));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag   XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
     */
    public void writeTag(String tag, double value) {
        writeTag(tag, Util.formatDouble(value, true));
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
        if (comment instanceof Date) {
            comment = ISOLIKE.get().format((Date) comment);
        }
        writer.println(indent + "<!-- " + escape(comment.toString()) + " -->");
    }

    private static String escape(String s) {
        return s.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
    }

}
