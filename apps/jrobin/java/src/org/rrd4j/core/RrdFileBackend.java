package org.rrd4j.core;

import java.io.IOException;

/**
 * An abstract backend which is used to store RRD data to ordinary files on the disk.
 * <p>
 * Every backend storing RRD data as ordinary files should inherit from it, some check are done
 * in the code for instanceof.
 *
 */
public interface RrdFileBackend {

    /**
     * Returns canonical path to the file on the disk.
     *
     * @return Canonical file path
     * @throws java.io.IOException Thrown in case of I/O error
     */
    String getCanonicalPath() throws IOException;

}
