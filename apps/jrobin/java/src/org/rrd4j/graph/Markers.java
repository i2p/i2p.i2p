package org.rrd4j.graph;

enum Markers {
    /**
     * Constant to represent left alignment marker
     */
    ALIGN_LEFT_MARKER("\\l"),
    /**
     * Constant to represent left alignment marker, without new line
     */
    ALIGN_LEFTNONL_MARKER("\\L"),
    /**
     * Constant to represent centered alignment marker
     */
    ALIGN_CENTER_MARKER("\\c"),
    /**
     * Constant to represent right alignment marker
     */
    ALIGN_RIGHT_MARKER("\\r"),
    /**
     * Constant to represent justified alignment marker
     */
    ALIGN_JUSTIFIED_MARKER("\\j"),
    /**
     * Constant to represent "glue" marker
     */
    GLUE_MARKER("\\g"),
    /**
     * Constant to represent vertical spacing marker
     */
    VERTICAL_SPACING_MARKER("\\s"),
    /**
     * Constant to represent no justification markers
     */
    NO_JUSTIFICATION_MARKER("\\J");

    final String marker;
    Markers(String marker) {
        this.marker = marker;
    }

    boolean check(String mark) {
        return marker.equals(mark);
    }

}
