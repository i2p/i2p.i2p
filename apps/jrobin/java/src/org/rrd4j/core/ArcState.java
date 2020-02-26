package org.rrd4j.core;

import java.io.IOException;

/**
 * Class to represent internal RRD archive state for a single datasource. Objects of this
 * class are never manipulated directly, it's up to Rrd4j to manage internal archive states.
 *
 * @author Sasa Markovic
 */
public class ArcState implements RrdUpdater<ArcState> {
    private Archive parentArc;

    private RrdDouble<ArcState> accumValue;
    private RrdLong<ArcState> nanSteps;

    ArcState(Archive parentArc, boolean shouldInitialize) throws IOException {
        this.parentArc = parentArc;
        accumValue = new RrdDouble<>(this);
        nanSteps = new RrdLong<>(this);
        if (shouldInitialize) {
            Header header = parentArc.getParentDb().getHeader();
            long step = header.getStep();
            long lastUpdateTime = header.getLastUpdateTime();
            long arcStep = parentArc.getArcStep();
            long initNanSteps = (Util.normalize(lastUpdateTime, step) -
                    Util.normalize(lastUpdateTime, arcStep)) / step;
            accumValue.set(Double.NaN);
            nanSteps.set(initNanSteps);
        }
    }

    String dump() throws IOException {
        return "accumValue:" + accumValue.get() + " nanSteps:" + nanSteps.get() + "\n";
    }

    void setNanSteps(long value) throws IOException {
        nanSteps.set(value);
    }

    /**
     * Returns the number of currently accumulated NaN steps.
     *
     * @return Number of currently accumulated NaN steps.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public long getNanSteps() throws IOException {
        return nanSteps.get();
    }

    void setAccumValue(double value) throws IOException {
        accumValue.set(value);
    }

    /**
     * Returns the value accumulated so far.
     *
     * @return Accumulated value
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public double getAccumValue() throws IOException {
        return accumValue.get();
    }

    /**
     * Returns the Archive object to which this ArcState object belongs.
     *
     * @return Parent Archive object.
     */
    public Archive getParent() {
        return parentArc;
    }

    void appendXml(XmlWriter writer) throws IOException {
        writer.startTag("ds");
        writer.writeTag("value", accumValue.get());
        writer.writeTag("unknown_datapoints", nanSteps.get());
        writer.closeTag(); // ds
    }

    /**
     * {@inheritDoc}
     *
     * Copies object's internal state to another ArcState object.
     */
    public void copyStateTo(ArcState arcState) throws IOException {
        arcState.accumValue.set(accumValue.get());
        arcState.nanSteps.set(nanSteps.get());
    }

    /**
     * Returns the underlying storage (backend) object which actually performs all
     * I/O operations.
     *
     * @return I/O backend object
     */
    public RrdBackend getRrdBackend() {
        return parentArc.getRrdBackend();
    }

    /**
     * Required to implement RrdUpdater interface. You should never call this method directly.
     *
     * @return Allocator object
     */
    public RrdAllocator getRrdAllocator() {
        return parentArc.getRrdAllocator();
    }
}
