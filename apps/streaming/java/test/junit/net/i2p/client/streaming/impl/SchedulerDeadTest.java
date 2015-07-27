package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;
import org.mockito.Mock;

public class SchedulerDeadTest extends TaskSchedulerTestBase {

    @Mock private Connection con;
    @Mock private ConnectionOptions opts;

    protected TaskScheduler createScheduler() {
        return new SchedulerDead(context);
    }

    private void setMocks(int now, int discSchOn, int connTimeout, int lifetime, int sendStreamId) {
        when(clock.now()).thenReturn((long) now);
        when(con.getDisconnectScheduledOn()).thenReturn((long) discSchOn);
        when(con.getOptions()).thenReturn(opts);
        when(opts.getConnectTimeout()).thenReturn((long) connTimeout);
        when(con.getLifetime()).thenReturn((long) lifetime);
        when(con.getSendStreamId()).thenReturn((long) sendStreamId);
    }

    @Test
    public void testAccept_nothingLeftToDo() {
        setMocks(10*60*1000, 9*60*1000 - Connection.DISCONNECT_TIMEOUT, 0, 0, 0);
        assertTrue(scheduler.accept(con));
    }

    @Test
    public void testAccept_noDisconnectScheduled() {
        setMocks(10*60*1000, 0, 0, 0, 0);
        assertFalse(scheduler.accept(con));
    }

    @Test
    public void testAccept_timedOut() {
        setMocks(0, 0, Connection.DISCONNECT_TIMEOUT/2, Connection.DISCONNECT_TIMEOUT, 0);
        assertTrue(scheduler.accept(con));
    }

    @Test
    public void testEventOccurred() {
        scheduler.eventOccurred(con);
        verify(con).disconnectComplete();
    }
}
