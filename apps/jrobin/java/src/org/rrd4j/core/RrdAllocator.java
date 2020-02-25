package org.rrd4j.core;

import java.io.IOException;

/**
 * An internal usage class.
 *
 * @author Sasa Markovic
 */
public class RrdAllocator {
    private long allocationPointer = 0L;
    
    RrdAllocator() {
        super();
    }

    long allocate(long byteCount) throws IOException {
        long pointer = allocationPointer;
        allocationPointer += byteCount;
        return pointer;
    }
}
