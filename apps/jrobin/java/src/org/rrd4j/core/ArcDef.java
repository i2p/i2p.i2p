package org.rrd4j.core;

import org.rrd4j.ConsolFun;

/**
 * Class to represent single archive definition within the RRD.
 * Archive definition consists of the following four elements:
 *
 * <ul>
 * <li>consolidation function
 * <li>X-files factor
 * <li>number of steps
 * <li>number of rows.
 * </ul>
 * For the complete explanation of all archive definition parameters, see RRDTool's
 * <a href="http://oss.oetiker.ch/rrdtool/doc/rrdcreate.en.html" target="man">rrdcreate man page</a>
 *
 * @author Sasa Markovic
 */
public class ArcDef {
    private final ConsolFun consolFun;
    private final double xff;
    private final int steps;
    private int rows;

    /**
     * Creates new archive definition object. This object should be passed as argument to
     * {@link org.rrd4j.core.RrdDef#addArchive(ArcDef) addArchive()} method of
     * {@link RrdDb RrdDb} object.
     * <p>For the complete explanation of all archive definition parameters, see RRDTool's
     * <a href="http://oss.oetiker.ch/rrdtool/doc/rrdcreate.en.html" target="man">rrdcreate man page</a></p>
     *
     * @param consolFun Consolidation function. Allowed values are "AVERAGE", "MIN",
     *                  "MAX", "LAST" and "TOTAL" (these string constants are conveniently defined in the
     *                  {@link org.rrd4j.ConsolFun} class).
     * @param xff       X-files factor, between 0 and 1.
     * @param steps     Number of archive steps.
     * @param rows      Number of archive rows.
     */
    public ArcDef(ConsolFun consolFun, double xff, int steps, int rows) {
        if (consolFun == null) {
            throw new IllegalArgumentException("Null consolidation function specified");
        }
        if (Double.isNaN(xff) || xff < 0.0 || xff >= 1.0) {
            throw new IllegalArgumentException("Invalid xff, must be >= 0 and < 1: " + xff);
        }
        if (steps < 1 || rows < 2) {
            throw new IllegalArgumentException("Invalid steps/rows settings: " + steps + "/" + rows +
                    ". Minimal values allowed are steps=1, rows=2");
        }

        this.consolFun = consolFun;
        this.xff = xff;
        this.steps = steps;
        this.rows = rows;
    }

    /**
     * Returns consolidation function.
     *
     * @return Consolidation function.
     */
    public ConsolFun getConsolFun() {
        return consolFun;
    }

    /**
     * Returns the X-files factor.
     *
     * @return X-files factor value.
     */
    public double getXff() {
        return xff;
    }

    /**
     * Returns the number of primary RRD steps which complete a single archive step.
     *
     * @return Number of steps.
     */
    public int getSteps() {
        return steps;
    }

    /**
     * Returns the number of rows (aggregated values) stored in the archive.
     *
     * @return Number of rows.
     */
    public int getRows() {
        return rows;
    }

    /**
     * Returns string representing archive definition (RRDTool format).
     *
     * @return String containing all archive definition parameters.
     */
    public String dump() {
        return "RRA:" + consolFun + ":" + xff + ":" + steps + ":" + rows;
    }

    /**
     * {@inheritDoc}
     *
     * Checks if two archive definitions are equal.
     * Archive definitions are considered equal if they have the same number of steps
     * and the same consolidation function. It is not possible to create RRD with two
     * equal archive definitions.
     */
    public boolean equals(Object obj) {
        if (obj instanceof ArcDef) {
            ArcDef arcObj = (ArcDef) obj;
            return consolFun == arcObj.consolFun && steps == arcObj.steps;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return consolFun.hashCode() + steps * 19;
    }

    void setRows(int rows) {
        this.rows = rows;
    }

    boolean exactlyEqual(ArcDef def) {
        return consolFun == def.consolFun && xff == def.xff && steps == def.steps && rows == def.rows;
    }

}
