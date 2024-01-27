package org.rrd4j.core;

import java.io.IOException;

import org.rrd4j.DsType;

/**
 * <p>Class to represent single datasource within RRD. Each datasource object holds the
 * following information: datasource definition (once set, never changed) and
 * datasource state variables (changed whenever RRD gets updated).</p>
 * <p>Normally, you don't need to manipulate Datasource objects directly, it's up to
 * Rrd4j framework to do it for you.</p>
 *
 * @author Sasa Markovic
 */
public class Datasource implements RrdUpdater<Datasource> {
    private static final double MAX_32_BIT = Math.pow(2, 32);
    private static final double MAX_64_BIT = Math.pow(2, 64);
    private static final String INVALID_MIN_MAX_VALUES = "Invalid min/max values: ";
    private double accumLastValue;

    private final RrdDb parentDb;

    // definition
    private final RrdString<Datasource> dsName;
    private final RrdEnum<Datasource, DsType> dsType;
    private final RrdLong<Datasource> heartbeat;
    private final RrdDouble<Datasource> minValue, maxValue;

    // state variables
    private final RrdDouble<Datasource> lastValue;
    private final RrdLong<Datasource> nanSeconds;
    private final RrdDouble<Datasource> accumValue;

    Datasource(RrdDb parentDb, DsDef dsDef) throws IOException {
        boolean shouldInitialize = dsDef != null;
        this.parentDb = parentDb;
        dsName = new RrdString<>(this);
        dsType = new RrdEnum<>(this, DsType.class);
        heartbeat = new RrdLong<>(this);
        minValue = new RrdDouble<>(this);
        maxValue = new RrdDouble<>(this);
        lastValue = new RrdDouble<>(this);
        accumValue = new RrdDouble<>(this);
        nanSeconds = new RrdLong<>(this);
        accumLastValue = Double.NaN;
        if (shouldInitialize) {
            dsName.set(dsDef.getDsName());
            dsType.set(dsDef.getDsType());
            heartbeat.set(dsDef.getHeartbeat());
            minValue.set(dsDef.getMinValue());
            maxValue.set(dsDef.getMaxValue());
            lastValue.set(Double.NaN);
            accumValue.set(0.0);
            Header header = parentDb.getHeader();
            nanSeconds.set(header.getLastUpdateTime() % header.getStep());
        }
    }

    Datasource(RrdDb parentDb, DataImporter reader, int dsIndex) throws IOException {
        this(parentDb, null);
        dsName.set(reader.getDsName(dsIndex));
        dsType.set(reader.getDsType(dsIndex));
        heartbeat.set(reader.getHeartbeat(dsIndex));
        minValue.set(reader.getMinValue(dsIndex));
        maxValue.set(reader.getMaxValue(dsIndex));
        lastValue.set(reader.getLastValue(dsIndex));
        accumValue.set(reader.getAccumValue(dsIndex));
        nanSeconds.set(reader.getNanSeconds(dsIndex));
    }

    String dump() throws IOException {
        return "== DATASOURCE ==\n" +
                "DS:" + dsName.get() + ":" + dsType.name() + ":" +
                heartbeat.get() + ":" + minValue.get() + ":" +
                maxValue.get() + "\nlastValue:" + lastValue.get() +
                " nanSeconds:" + nanSeconds.get() +
                " accumValue:" + accumValue.get() + "\n";
    }

    /**
     * Returns datasource name.
     *
     * @return Datasource name
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public String getName() throws IOException {
        return dsName.get();
    }

    /**
     * Returns datasource type (GAUGE, COUNTER, DERIVE, ABSOLUTE).
     *
     * @return Datasource type.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public DsType getType() throws IOException {
        return dsType.get();
    }

    /**
     * Returns datasource heartbeat
     *
     * @return Datasource heartbeat
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public long getHeartbeat() throws IOException {
        return heartbeat.get();
    }

    /**
     * Returns minimal allowed value for this datasource.
     *
     * @return Minimal value allowed.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public double getMinValue() throws IOException {
        return minValue.get();
    }

    /**
     * Returns maximal allowed value for this datasource.
     *
     * @return Maximal value allowed.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public double getMaxValue() throws IOException {
        return maxValue.get();
    }

    /**
     * Returns last known value of the datasource.
     *
     * @return Last datasource value.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public double getLastValue() throws IOException {
        return lastValue.get();
    }

    /**
     * Returns value this datasource accumulated so far.
     *
     * @return Accumulated datasource value.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public double getAccumValue() throws IOException {
        return accumValue.get();
    }

    /**
     * Returns the number of accumulated NaN seconds.
     *
     * @return Accumulated NaN seconds.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public long getNanSeconds() throws IOException {
        return nanSeconds.get();
    }

    final void process(long newTime, double newValue) throws IOException {
        Header header = parentDb.getHeader();
        long step = header.getStep();
        long oldTime = header.getLastUpdateTime();
        long startTime = Util.normalize(oldTime, step);
        long endTime = startTime + step;
        double oldValue = lastValue.get();
        double updateValue = calculateUpdateValue(oldTime, oldValue, newTime, newValue);
        if (newTime < endTime) {
            accumulate(oldTime, newTime, updateValue);
        }
        else {
            // should store something
            long boundaryTime = Util.normalize(newTime, step);
            accumulate(oldTime, boundaryTime, updateValue);
            double value = calculateTotal(startTime, boundaryTime);
            double lastCalculateValue = calculateLastTotal(startTime, boundaryTime);

            // how many updates?
            long numSteps = (boundaryTime - endTime) / step + 1L;

            // ACTION!
            parentDb.archive(this, value, lastCalculateValue, numSteps);

            // cleanup
            nanSeconds.set(0);
            accumValue.set(0.0);
            accumLastValue = Double.NaN;

            accumulate(boundaryTime, newTime, updateValue);
        }
    }

    private double calculateUpdateValue(long oldTime, double oldValue,
            long newTime, double newValue) throws IOException {
        double updateValue = Double.NaN;
        if (newTime - oldTime <= heartbeat.get()) {
            switch (dsType.get()) {
            case GAUGE:
                updateValue = newValue;
                break;
            case COUNTER:
                if (!Double.isNaN(newValue) && !Double.isNaN(oldValue)) {
                    double diff = newValue - oldValue;
                    if (diff < 0) {
                        diff += MAX_32_BIT;
                    }
                    if (diff < 0) {
                        diff += MAX_64_BIT - MAX_32_BIT;
                    }
                    if (diff >= 0) {
                        updateValue = diff / (newTime - oldTime);
                    }
                }
                break;
            case ABSOLUTE:
                if (!Double.isNaN(newValue)) {
                    updateValue = newValue / (newTime - oldTime);
                }
                break;
            case DERIVE:
                if (!Double.isNaN(newValue) && !Double.isNaN(oldValue)) {
                    updateValue = (newValue - oldValue) / (newTime - oldTime);
                }
                break;
            }

            if (!Double.isNaN(updateValue)) {
                double minVal = minValue.get();
                double maxVal = maxValue.get();
                if (!Double.isNaN(minVal) && updateValue < minVal) {
                    updateValue = Double.NaN;
                }
                if (!Double.isNaN(maxVal) && updateValue > maxVal) {
                    updateValue = Double.NaN;
                }
            }
        }
        lastValue.set(newValue);
        return updateValue;
    }

    private void accumulate(long oldTime, long newTime, double updateValue) throws IOException {
        if (Double.isNaN(updateValue)) {
            nanSeconds.set(nanSeconds.get() + (newTime - oldTime));
        }
        else {
            accumValue.set(accumValue.get() + updateValue * (newTime - oldTime));
            accumLastValue = updateValue;
        }
    }

    private double calculateTotal(long startTime, long boundaryTime) throws IOException {
        double totalValue = Double.NaN;
        long validSeconds = boundaryTime - startTime - nanSeconds.get();
        if (nanSeconds.get() <= heartbeat.get() && validSeconds > 0) {
            totalValue = accumValue.get() / validSeconds;
        }
        // IMPORTANT:
        // if datasource name ends with "!", we'll send zeros instead of NaNs
        // this might be handy from time to time
        if (Double.isNaN(totalValue) && dsName.get().endsWith(DsDef.FORCE_ZEROS_FOR_NANS_SUFFIX)) {
            totalValue = 0D;
        }
        return totalValue;
    }

    private double calculateLastTotal(long startTime, long boundaryTime) throws IOException {
        double totalValue = Double.NaN;
        long validSeconds = boundaryTime - startTime - nanSeconds.get();
        if (nanSeconds.get() <= heartbeat.get() && validSeconds > 0) {
            totalValue = accumLastValue;
        }

        if (Double.isNaN(totalValue) && dsName.get().endsWith(DsDef.FORCE_ZEROS_FOR_NANS_SUFFIX)) {
            totalValue = 0D;
        }
        return totalValue;
    }

    void appendXml(XmlWriter writer) throws IOException {
        writer.startTag("ds");
        writer.writeTag("name", dsName.get());
        writer.writeTag("type", dsType.name());
        writer.writeTag("minimal_heartbeat", heartbeat.get());
        writer.writeTag("min", minValue.get());
        writer.writeTag("max", maxValue.get());
        writer.writeComment("PDP Status");
        writer.writeTag("last_ds", lastValue.get(), "UNKN");
        writer.writeTag("value", accumValue.get());
        writer.writeTag("unknown_sec", nanSeconds.get());
        writer.closeTag();  // ds
    }

    /**
     * {@inheritDoc}
     *
     * Copies object's internal state to another Datasource object.
     */
    public void copyStateTo(Datasource datasource) throws IOException {
        if (!datasource.dsName.get().equals(dsName.get())) {
            throw new IllegalArgumentException("Incompatible datasource names");
        }
        if (datasource.dsType.get() != dsType.get()) {
            throw new IllegalArgumentException("Incompatible datasource types");
        }
        datasource.lastValue.set(lastValue.get());
        datasource.nanSeconds.set(nanSeconds.get());
        datasource.accumValue.set(accumValue.get());
    }

    /**
     * Returns index of this Datasource object in the RRD.
     *
     * @return Datasource index in the RRD.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public int getDsIndex() throws IOException {
        try {
            return parentDb.getDsIndex(dsName.get());
        }
        catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /**
     * Sets datasource heartbeat to a new value.
     *
     * @param heartbeat New heartbeat value
     * @throws java.io.IOException              Thrown in case of I/O error
     * @throws java.lang.IllegalArgumentException Thrown if invalid (non-positive) heartbeat value is specified.
     */
    public void setHeartbeat(long heartbeat) throws IOException {
        if (heartbeat < 1L) {
            throw new IllegalArgumentException("Invalid heartbeat specified: " + heartbeat);
        }
        this.heartbeat.set(heartbeat);
    }

    /**
     * Sets datasource name to a new value
     *
     * @param newDsName New datasource name
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public void setDsName(String newDsName) throws IOException {
        if (parentDb.containsDs(newDsName)) {
            throw new IllegalArgumentException("Datasource already defined in this RRD: " + newDsName);
        }

        this.dsName.set(newDsName);
    }

    /**
     * <p>Setter for the field <code>dsType</code>.</p>
     *
     * @param newDsType a {@link org.rrd4j.DsType} object.
     * @throws java.io.IOException if any.
     */
    public void setDsType(DsType newDsType) throws IOException {
        // set datasource type
        dsType.set(newDsType);
        // reset datasource status
        lastValue.set(Double.NaN);
        accumValue.set(0.0);
        // reset archive status
        int dsIndex = parentDb.getDsIndex(dsName.get());
        Archive[] archives = parentDb.getArchives();
        for (Archive archive : archives) {
            archive.getArcState(dsIndex).setAccumValue(Double.NaN);
        }
    }

    /**
     * Sets minimum allowed value for this datasource. If <code>filterArchivedValues</code>
     * argument is set to true, all archived values less then <code>minValue</code> will
     * be fixed to NaN.
     *
     * @param minValue             New minimal value. Specify <code>Double.NaN</code> if no minimal
     *                             value should be set
     * @param filterArchivedValues true, if archived datasource values should be fixed;
     *                             false, otherwise.
     * @throws java.io.IOException              Thrown in case of I/O error
     * @throws java.lang.IllegalArgumentException Thrown if invalid minValue was supplied (not less then maxValue)
     */
    public void setMinValue(double minValue, boolean filterArchivedValues) throws IOException {
        double maxValue = this.maxValue.get();
        if (!Double.isNaN(minValue) && !Double.isNaN(maxValue) && minValue >= maxValue) {
            throw new IllegalArgumentException(INVALID_MIN_MAX_VALUES + minValue + "/" + maxValue);
        }

        this.minValue.set(minValue);
        if (!Double.isNaN(minValue) && filterArchivedValues) {
            int dsIndex = getDsIndex();
            Archive[] archives = parentDb.getArchives();
            for (Archive archive : archives) {
                archive.getRobin(dsIndex).filterValues(minValue, Double.NaN);
            }
        }
    }

    /**
     * Sets maximum allowed value for this datasource. If <code>filterArchivedValues</code>
     * argument is set to true, all archived values greater then <code>maxValue</code> will
     * be fixed to NaN.
     *
     * @param maxValue             New maximal value. Specify <code>Double.NaN</code> if no max
     *                             value should be set.
     * @param filterArchivedValues true, if archived datasource values should be fixed;
     *                             false, otherwise.
     * @throws java.io.IOException              Thrown in case of I/O error
     * @throws java.lang.IllegalArgumentException Thrown if invalid maxValue was supplied (not greater then minValue)
     */
    public void setMaxValue(double maxValue, boolean filterArchivedValues) throws IOException {
        double minValue = this.minValue.get();
        if (!Double.isNaN(minValue) && !Double.isNaN(maxValue) && minValue >= maxValue) {
            throw new IllegalArgumentException(INVALID_MIN_MAX_VALUES + minValue + "/" + maxValue);
        }

        this.maxValue.set(maxValue);
        if (!Double.isNaN(maxValue) && filterArchivedValues) {
            int dsIndex = getDsIndex();
            Archive[] archives = parentDb.getArchives();
            for (Archive archive : archives) {
                archive.getRobin(dsIndex).filterValues(Double.NaN, maxValue);
            }
        }
    }

    /**
     * Sets min/max values allowed for this datasource. If <code>filterArchivedValues</code>
     * argument is set to true, all archived values less then <code>minValue</code> or
     * greater then <code>maxValue</code> will be fixed to NaN.
     *
     * @param minValue             New minimal value. Specify <code>Double.NaN</code> if no min
     *                             value should be set.
     * @param maxValue             New maximal value. Specify <code>Double.NaN</code> if no max
     *                             value should be set.
     * @param filterArchivedValues true, if archived datasource values should be fixed;
     *                             false, otherwise.
     * @throws java.io.IOException              Thrown in case of I/O error
     * @throws java.lang.IllegalArgumentException Thrown if invalid min/max values were supplied
     */
    public void setMinMaxValue(double minValue, double maxValue, boolean filterArchivedValues) throws IOException {
        if (!Double.isNaN(minValue) && !Double.isNaN(maxValue) && minValue >= maxValue) {
            throw new IllegalArgumentException(INVALID_MIN_MAX_VALUES + minValue + "/" + maxValue);
        }
        this.minValue.set(minValue);
        this.maxValue.set(maxValue);
        if (!(Double.isNaN(minValue) && Double.isNaN(maxValue)) && filterArchivedValues) {
            int dsIndex = getDsIndex();
            Archive[] archives = parentDb.getArchives();
            for (Archive archive : archives) {
                archive.getRobin(dsIndex).filterValues(minValue, maxValue);
            }
        }
    }

    /**
     * Returns the underlying storage (backend) object which actually performs all
     * I/O operations.
     *
     * @return I/O backend object
     */
    public RrdBackend getRrdBackend() {
        return parentDb.getRrdBackend();
    }

    /**
     * Required to implement RrdUpdater interface. You should never call this method directly.
     *
     * @return Allocator object
     */
    public RrdAllocator getRrdAllocator() {
        return parentDb.getRrdAllocator();
    }
}
