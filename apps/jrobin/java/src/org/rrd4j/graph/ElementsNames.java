package org.rrd4j.graph;

/**
 * The elements of the graph. For use in {@link RrdGraphDef#setColor(ElementsNames, java.awt.Paint)} method.
 * 
 * @author Fabrice Bacchella
 *
 */
public enum ElementsNames {
    /**
     * The canvas color
     */
    canvas,
    /**
     * The background color
     */
    back,
    /**
     * The top-left graph shade color
     */
    shadea,
    /**
     * The bottom-right graph shade color
     */
    shadeb,
    /**
     * The minor grid color
     */
    grid,
    /**
     * The major grid color
     */
    mgrid,
    /**
     * The font color
     */
    font,
    /**
     * The frame color
     */
    frame,
    /**
     * The arrow color
     */
    arrow,
    /**
     * The x-axis color
     */
    xaxis,
    /**
     * The y-axis color
     */
    yaxis;
}
