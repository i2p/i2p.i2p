package org.rrd4j.core;

import java.io.IOException;

import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.jrrd.RRDatabase;

class RrdToolReader extends DataImporter {
    private RRDatabase rrd;

    RrdToolReader(String rrdPath) throws IOException {
        rrd = new RRDatabase(rrdPath);
    }

    public String getVersion() {
        return rrd.getHeader().getVersion();
    }

    public long getLastUpdateTime() {
        return Util.getTimestamp(rrd.getLastUpdate());
    }

    public long getStep() {
        return rrd.getHeader().getPDPStep();
    }

    public int getDsCount() {
        return rrd.getHeader().getDSCount();
    }

    public int getArcCount() {
        return rrd.getNumArchives();
    }

    public String getDsName(int dsIndex) {
        return rrd.getDataSource(dsIndex).getName();
    }

    @Override
    public DsType getDsType(int dsIndex) {
        return rrd.getDataSource(dsIndex).getType().getDsType();
    }

    public long getHeartbeat(int dsIndex) {
        return rrd.getDataSource(dsIndex).getMinimumHeartbeat();
    }

    public double getMinValue(int dsIndex) {
        return rrd.getDataSource(dsIndex).getMinimum();
    }

    public double getMaxValue(int dsIndex) {
        return rrd.getDataSource(dsIndex).getMaximum();
    }

    public double getLastValue(int dsIndex) {
        String valueStr = rrd.getDataSource(dsIndex).getPDPStatusBlock().getLastReading();
        return Util.parseDouble(valueStr);
    }

    public double getAccumValue(int dsIndex) {
        return rrd.getDataSource(dsIndex).getPDPStatusBlock().getValue();
    }

    public long getNanSeconds(int dsIndex) {
        return rrd.getDataSource(dsIndex).getPDPStatusBlock().getUnknownSeconds();
    }

    public ConsolFun getConsolFun(int arcIndex) {
        return rrd.getArchive(arcIndex).getType().getConsolFun();
    }

    public double getXff(int arcIndex) {
        return rrd.getArchive(arcIndex).getXff();
    }

    public int getSteps(int arcIndex) {
        return rrd.getArchive(arcIndex).getPdpCount();
    }

    public int getRows(int arcIndex) {
        return rrd.getArchive(arcIndex).getRowCount();
    }

    public double getStateAccumValue(int arcIndex, int dsIndex) {
        return rrd.getArchive(arcIndex).getCDPStatusBlock(dsIndex).getValue();
    }

    public int getStateNanSteps(int arcIndex, int dsIndex) {
        return rrd.getArchive(arcIndex).getCDPStatusBlock(dsIndex).getUnknownDatapoints();
    }

    public double[] getValues(int arcIndex, int dsIndex) {
        return rrd.getArchive(arcIndex).getValues()[dsIndex];
    }

    @Override
    void release() throws IOException {
        if (rrd != null) {
            rrd.close();
            rrd = null;
        }
    }

}
