package org.rrd4j.core;

import java.io.Closeable;
import java.io.IOException;

import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;

/**
 * <p>An abstract class to import data from external source.</p>
 * @author Fabrice Bacchella
 * @since 3.5
 */
public abstract class DataImporter implements Closeable {

    // header
    public abstract String getVersion() throws IOException;

    public abstract long getLastUpdateTime() throws IOException;

    public abstract long getStep() throws IOException;

    public abstract int getDsCount() throws IOException;

    public abstract int getArcCount() throws IOException;

    // datasource
    public abstract String getDsName(int dsIndex) throws IOException;

    public abstract DsType getDsType(int dsIndex) throws IOException;

    public abstract long getHeartbeat(int dsIndex) throws IOException;

    public abstract double getMinValue(int dsIndex) throws IOException;

    public abstract double getMaxValue(int dsIndex) throws IOException;

    // datasource state
    public abstract double getLastValue(int dsIndex) throws IOException;

    public abstract double getAccumValue(int dsIndex) throws IOException;

    public abstract long getNanSeconds(int dsIndex) throws IOException;

    // archive
    public abstract ConsolFun getConsolFun(int arcIndex) throws IOException;

    public abstract double getXff(int arcIndex) throws IOException;

    public abstract int getSteps(int arcIndex) throws IOException;

    public abstract int getRows(int arcIndex) throws IOException;

    // archive state
    public abstract double getStateAccumValue(int arcIndex, int dsIndex) throws IOException;

    public abstract int getStateNanSteps(int arcIndex, int dsIndex) throws IOException;

    public abstract double[] getValues(int arcIndex, int dsIndex) throws IOException;

    protected long getEstimatedSize() throws IOException {
        int dsCount = getDsCount();
        int arcCount = getArcCount();
        int rowCount = 0;
        for (int i = 0; i < arcCount; i++) {
            rowCount += getRows(i);
        }
        String[] dsNames = new String[getDsCount()];
        for (int i = 0 ; i < dsNames.length; i++) {
            dsNames[i] = getDsName(i);
        }
        return RrdDef.calculateSize(dsCount, arcCount, rowCount, dsNames);
    }

    void release() throws IOException {
        // NOP
    }

    @Override
    public void close() throws IOException {
        release();
    }

}
