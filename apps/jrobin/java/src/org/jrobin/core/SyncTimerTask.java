package org.jrobin.core;

import java.util.TimerTask;

public final class SyncTimerTask extends TimerTask {
    private final RrdNioBackend m_rrdNioBackend;

    SyncTimerTask(final RrdNioBackend rrdNioBackend) {
        m_rrdNioBackend = rrdNioBackend;
    }

    @Override public void run() {
        m_rrdNioBackend.sync();
    }
}