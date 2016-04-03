package com.maxmind.geoip;

import java.io.IOException;

/**
 * Signals that there was an issue reading from the database file due to
 * unexpected data formatting. This generally suggests that the database is
 * corrupt or otherwise not in a format supported by the reader.
 */
public final class InvalidDatabaseException extends RuntimeException {

    /**
     * @param message A message describing the reason why the exception was thrown.
     */
    public InvalidDatabaseException(String message) {
        super(message);
    }

    /**
     * @param message A message describing the reason why the exception was thrown.
     * @param cause   The cause of the exception.
     */
    public InvalidDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}