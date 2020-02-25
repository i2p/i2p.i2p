package org.rrd4j.core.jrrd;

interface Constants {
    /** Constant <code>DS_NAM_SIZE=20</code> */
    final int DS_NAM_SIZE = 20;
    /** Constant <code>DST_SIZE=20</code> */
    final int DST_SIZE = 20;
    /** Constant <code>CF_NAM_SIZE=20</code> */
    final int CF_NAM_SIZE = 20;
    /** Constant <code>LAST_DS_LEN=30</code> */
    final  int LAST_DS_LEN = 30;
    /** Constant <code>COOKIE="RRD"</code> */
    static final String COOKIE = "RRD";
    /** Constant <code>MAX_SUPPORTED_VERSION=3</code> */
    public static final int MAX_SUPPORTED_VERSION = 3;
    /** Constant <code>UNDEFINED_VERSION="UNDEF"</code> */
    public static final String UNDEFINED_VERSION = "UNDEF";
    /** Constant <code>UNDEFINED_VERSION_AS_INT=-1</code> */
    public static final int UNDEFINED_VERSION_AS_INT = -1;
    /** Constant <code>VERSION_WITH_LAST_UPDATE_SEC=3</code> */
    public static int VERSION_WITH_LAST_UPDATE_SEC = 3;
    /** Constant <code>FLOAT_COOKIE=8.642135E130</code> */
    static final double FLOAT_COOKIE = 8.642135E130;
    /** Constant <code>SIZE_OF_DOUBLE=8</code> */
    public static final int SIZE_OF_DOUBLE = 8;
}
