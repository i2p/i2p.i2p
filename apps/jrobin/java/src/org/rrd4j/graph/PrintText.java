package org.rrd4j.graph;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.rrd4j.core.Util;
import org.rrd4j.data.DataProcessor;
import org.rrd4j.data.Variable;
import org.rrd4j.data.Variable.Value;

class PrintText extends CommentText {
    static final String UNIT_MARKER = "([^%]?)%(s|S)";
    static final Pattern UNIT_PATTERN = Pattern.compile(UNIT_MARKER);

    private final String srcName;
    private final boolean includedInGraph;
    private final boolean strftime;

    PrintText(String srcName, String text, boolean includedInGraph, boolean strftime) {
        super(text);
        this.srcName = srcName;
        this.includedInGraph = includedInGraph;
        this.strftime = strftime;
    }

    boolean isPrint() {
        return !includedInGraph;
    }

    void resolveText(Locale l, DataProcessor dproc, ValueScaler valueScaler) {
        super.resolveText(l, dproc, valueScaler);
        Value v = dproc.getVariable(srcName);
        if(resolvedText == null) {
            return;
        }
        else if(strftime) {
            if(v != Variable.INVALIDVALUE) {
                long time = v.timestamp;
                try {
                    Calendar c = new GregorianCalendar(dproc.getTimeZone(), l);
                    c.setTimeInMillis(time * 1000);
                    resolvedText = String.format(l, resolvedText, c);
                } catch (Exception e) {
                    throw new RuntimeException("can't format '" + resolvedText + "'", e);
                }
            }
            else {
                resolvedText = "-";
            }
        }
        else {
            double value = v.value;
            Matcher matcher = UNIT_PATTERN.matcher(resolvedText);
            if (matcher.find()) {
                // unit specified
                ValueScaler.Scaled scaled = valueScaler.scale(value, matcher.group(2).equals("s"));
                resolvedText = resolvedText.substring(0, matcher.start()) +
                        matcher.group(1) + scaled.unit + resolvedText.substring(matcher.end());
                value = scaled.value;
            }
            try {
                resolvedText = Util.sprintf(l, resolvedText, value);
            } catch (Exception e) {
                throw new RuntimeException("can't format '" + resolvedText + "'", e);
            }
        }
        trimIfGlue();
    }
}
