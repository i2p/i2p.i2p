package org.rrd4j.graph;

import java.util.Locale;

import org.rrd4j.data.DataProcessor;

class CommentText implements RrdGraphConstants {
    protected final String text; // original text

    String resolvedText; // resolved text
    Markers marker; // end-of-text marker
    boolean enabled; // hrule and vrule comments can be disabled at runtime
    int x, y; // coordinates, evaluated in LegendComposer

    CommentText(String text) {
        this.text = text;
    }

    void resolveText(Locale l, DataProcessor dproc, ValueScaler valueScaler) {
        resolvedText = text;
        marker = null;
        if (resolvedText != null) {
            for (Markers m : Markers.values()) {
                String tryMarker = m.marker;
                if (resolvedText.endsWith(tryMarker)) {
                    marker = m;
                    resolvedText = resolvedText.substring(0, resolvedText.length() - tryMarker.length());
                    trimIfGlue();
                    break;
                }
            }
        }
        enabled = resolvedText != null;
    }

    void trimIfGlue() {
        if (Markers.GLUE_MARKER == marker) {
            resolvedText = resolvedText.replaceFirst("\\s+$", "");
        }
    }

    boolean isPrint() {
        return false;
    }

    boolean isValidGraphElement() {
        return !isPrint() && enabled;
    }
}
