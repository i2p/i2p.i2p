package org.rrd4j.core;

import java.io.IOException;

/**
 * Class to represent RRD header. Header information is mainly static (once set, it
 * cannot be changed), with the exception of last update time (this value is changed whenever
 * RRD gets updated).
 * <p>
 *
 * Normally, you don't need to manipulate the Header object directly - Rrd4j framework
 * does it for you.
 * <p>
 *
 * @author Sasa Markovic*
 */
public class Header implements RrdUpdater<Header> {
    static final int SIGNATURE_LENGTH = 5;
    static final String SIGNATURE = "RRD4J";

    static final String DEFAULT_SIGNATURE = "RRD4J, version 0.1";
    static final String RRDTOOL_VERSION1 = "0001";
    static final String RRDTOOL_VERSION3 = "0003";
    private static final String[] VERSIONS = {"version 0.1", "version 0.2"};

    private RrdDb parentDb;
    private int version = -1;

    private RrdString<Header> signature;
    private RrdLong<Header> step;
    private RrdInt<Header> dsCount, arcCount;
    private RrdLong<Header> lastUpdateTime;

    Header(RrdDb parentDb, RrdDef rrdDef) throws IOException {
        this.parentDb = parentDb;

        String initSignature = null;
        if(rrdDef != null) {
            version = rrdDef.getVersion(); 
            initSignature = SIGNATURE + ", " + VERSIONS[version - 1];
        }
        else {
            initSignature = DEFAULT_SIGNATURE;
        }

        signature = new RrdString<>(this);     // NOT constant, may be cached
        step = new RrdLong<>(this, true);      // constant, may be cached
        dsCount = new RrdInt<>(this, true);    // constant, may be cached
        arcCount = new RrdInt<>(this, true);   // constant, may be cached
        lastUpdateTime = new RrdLong<>(this);

        if (rrdDef != null) {
            signature.set(initSignature);
            step.set(rrdDef.getStep());
            dsCount.set(rrdDef.getDsCount());
            arcCount.set(rrdDef.getArcCount());
            lastUpdateTime.set(rrdDef.getStartTime());
        }
    }

    Header(RrdDb parentDb, DataImporter reader) throws IOException {
        this(parentDb, (RrdDef) null);
        String importVersion = reader.getVersion();
        switch(importVersion) {
        case RRDTOOL_VERSION1:
            version = 1;
            break;
        case RRDTOOL_VERSION3:
            version = 2;
            break;
        default:
            throw new IllegalArgumentException("Could not get version " + version);
        }
        signature.set(SIGNATURE + ", " + VERSIONS[version - 1]);
        step.set(reader.getStep());
        dsCount.set(reader.getDsCount());
        arcCount.set(reader.getArcCount());
        lastUpdateTime.set(reader.getLastUpdateTime());
    }

    /**
     * Returns RRD signature. Initially, the returned string will be
     * of the form <b><i>Rrd4j, version x.x</i></b>.
     *
     * @return RRD signature
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public String getSignature() throws IOException {
        return signature.get();
    }

    /**
     * <p>getInfo.</p>
     *
     * @return a {@link java.lang.String} object.
     * @throws java.io.IOException if any.
     */
    public String getInfo() throws IOException {
        return getSignature().substring(SIGNATURE_LENGTH);
    }

    /**
     * <p>setInfo.</p>
     *
     * @param info a {@link java.lang.String} object.
     * @throws java.io.IOException if any.
     */
    public void setInfo(String info) throws IOException {
        if (info != null && info.length() > 0) {
            signature.set(SIGNATURE + info);
        }
        else {
            signature.set(SIGNATURE);
        }
    }

    /**
     * Returns the last update time of the RRD.
     *
     * @return Timestamp (Unix epoch, no milliseconds) corresponding to the last update time.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public long getLastUpdateTime() throws IOException {
        return lastUpdateTime.get();
    }

    /**
     * Returns primary RRD time step.
     *
     * @return Primary time step in seconds
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public long getStep() throws IOException {
        return step.get();
    }

    /**
     * Returns the number of datasources defined in the RRD.
     *
     * @return Number of datasources defined
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public int getDsCount() throws IOException {
        return dsCount.get();
    }

    /**
     * Returns the number of archives defined in the RRD.
     *
     * @return Number of archives defined
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public int getArcCount() throws IOException {
        return arcCount.get();
    }

    void setLastUpdateTime(long lastUpdateTime) throws IOException {
        this.lastUpdateTime.set(lastUpdateTime);
    }

    String dump() throws IOException {
        return "== HEADER ==\n" +
                "signature:" + getSignature() +
                " lastUpdateTime:" + getLastUpdateTime() +
                " step:" + getStep() +
                " dsCount:" + getDsCount() +
                " arcCount:" + getArcCount() + "\n";
    }

    void appendXml(XmlWriter writer) throws IOException {
        writer.writeComment(signature.get());
        writer.writeTag("version", RRDTOOL_VERSION3);
        writer.writeComment("Seconds");
        writer.writeTag("step", step.get());
        writer.writeComment(Util.getDate(lastUpdateTime.get()));
        writer.writeTag("lastupdate", lastUpdateTime.get());
    }

    /**
     * {@inheritDoc}
     *
     * Copies object's internal state to another Header object.
     */
    public void copyStateTo(Header header) throws IOException {
        header.lastUpdateTime.set(lastUpdateTime.get());
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
     * Return the RRD version.
     *
     * @return RRD version
     * @throws java.io.IOException if any.
     */
    public int getVersion() throws IOException {
        if(version < 0) {
            for(int i=0; i < VERSIONS.length; i++) {
                if(signature.get().endsWith(VERSIONS[i])) {
                    version = i + 1;
                    break;
                }
            }
        }
        return version;
    }

    boolean isRrd4jHeader() {
        try {
            return signature.get().startsWith(SIGNATURE) || signature.get().startsWith("JR"); // backwards compatible with JRobin
        } catch (IOException ioe) {
            return false;
        }
    }

    void validateHeader() throws IOException {
        if (!isRrd4jHeader()) {
            throw new InvalidRrdException("Invalid file header. File [" + parentDb.getCanonicalPath() + "] is not a RRD4J RRD file");
        }
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
