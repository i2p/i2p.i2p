package org.rrd4j.core;

import java.io.IOException;

/**
 * Factory class which creates actual {@link org.rrd4j.core.RrdRandomAccessFileBackend} objects. This was the default
 * backend factory in Rrd4j before 1.4.0 release.
 *
 */
@RrdBackendAnnotation(name="FILE", shouldValidateHeader=true)
public class RrdRandomAccessFileBackendFactory extends RrdFileBackendFactory {

    /**
     * {@inheritDoc}
     *
     * Creates RrdFileBackend object for the given file path.
     */
    protected RrdBackend open(String path, boolean readOnly) throws IOException {
        return new RrdRandomAccessFileBackend(path, readOnly);
    }

}
