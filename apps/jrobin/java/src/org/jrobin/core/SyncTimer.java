package org.jrobin.core;

import java.util.Timer;
import java.util.concurrent.atomic.AtomicInteger;

public class SyncTimer extends Timer {
    private static AtomicInteger m_serialNumber = new AtomicInteger();

    public SyncTimer() {
        super("SyncManager-" + m_serialNumber.getAndIncrement(), true);
    }
}
