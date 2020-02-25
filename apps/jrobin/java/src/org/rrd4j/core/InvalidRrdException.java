package org.rrd4j.core;

/**
 * An exception indicating a corrupted RRD.
 * 
 * @since 3.4
 */
public class InvalidRrdException extends RrdException {

    private static final long serialVersionUID = 1L;

    public InvalidRrdException(String message) {
        super(message);
    }

    public InvalidRrdException(String message, Exception cause) {
        super(message, cause);
    }

}
