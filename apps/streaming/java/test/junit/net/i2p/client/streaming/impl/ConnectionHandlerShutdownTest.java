package net.i2p.client.streaming.impl;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.RouterRestartException;
import net.i2p.data.Destination;
import net.i2p.util.Clock;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class ConnectionHandlerShutdownTest {
    @Test
    public void closeWakesAllAcceptors() throws Exception {
        shutdown(false, false);
    }

    @Test
    public void restartWakesAllAcceptors() throws Exception {
        shutdown(true, false);
    }

    @Test
    public void closeReasonSurvivesImmediateReopen() throws Exception {
        shutdown(false, true);
    }

    @Test
    public void restartReasonSurvivesImmediateReopen() throws Exception {
        shutdown(true, true);
    }

    private void shutdown(boolean restart, boolean reopen) throws Exception {
        ConnectionHandler handler = handler(mock(ConnectionManager.class), mock(SimpleTimer2.class));
        handler.setActive(true);
        List<Waiter> waiters = new ArrayList<Waiter>();
        try {
            for (int i = 0; i < 8; i++) {
                Waiter waiter = new Waiter(handler, 0);
                waiters.add(waiter);
                waiter.awaitBlocked();
            }
            // Keep waiters out until both lifecycle changes have completed.
            synchronized (handler) {
                if (restart)
                    handler.setRestartPending();
                else
                    handler.setActive(false);
                if (reopen)
                    handler.setActive(true);
            }
            for (Waiter waiter : waiters) {
                Throwable result = waiter.result.get(1, TimeUnit.SECONDS);
                assertTrue(String.valueOf(result), restart ? result instanceof RouterRestartException : result instanceof ConnectException);
            }
        } finally {
            handler.setActive(false);
            for (Waiter waiter : waiters)
                waiter.thread.interrupt();
        }
    }

    @Test
    public void timeoutUsesElapsedTimeWhenContextClockDoesNotAdvance() throws Exception {
        I2PAppContext context = mock(I2PAppContext.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1000L);
        when(context.clock()).thenReturn(clock);
        when(context.logManager()).thenReturn(I2PAppContext.getGlobalContext().logManager());
        ConnectionHandler handler = new ConnectionHandler(context, mock(ConnectionManager.class), mock(SimpleTimer2.class));
        handler.setActive(true);
        Waiter waiter = new Waiter(handler, 20);
        try {
            assertTrue(waiter.result.get(1, TimeUnit.SECONDS) instanceof SocketTimeoutException);
        } finally {
            handler.setActive(false);
            waiter.thread.interrupt();
        }
    }

    @Test
    public void interruptUnblocksAcceptWithCause() throws Exception {
        ConnectionHandler handler = handler(mock(ConnectionManager.class), mock(SimpleTimer2.class));
        handler.setActive(true);
        Waiter waiter = new Waiter(handler, 0);
        try {
            waiter.awaitBlocked();
            waiter.thread.interrupt();
            Throwable result = waiter.result.get(1, TimeUnit.SECONDS);
            assertTrue(result instanceof ConnectException);
            assertTrue(result.getCause() instanceof InterruptedException);
        } finally {
            handler.setActive(false);
            waiter.thread.interrupt();
        }
    }

    @Test
    public void newListeningPeriodCanAcceptConnections() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        ConnectionHandler handler = handler(manager, mock(SimpleTimer2.class));
        handler.setActive(true);
        handler.setRestartPending();
        handler.setActive(true);
        Packet packet = mock(Packet.class);
        when(packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)).thenReturn(true);
        when(packet.getOptionalFrom()).thenReturn(mock(Destination.class));
        Connection connection = mock(Connection.class);
        when(manager.receiveConnection(packet)).thenReturn(connection);
        try {
            handler.receiveNewSyn(packet);
            assertSame(connection, handler.accept(1000));
        } finally {
            handler.setActive(false);
        }
    }

    @Test
    public void stoppedGenerationCannotQueueIntoReopenedListener() throws Exception {
        ConnectionManager manager = mock(ConnectionManager.class);
        SimpleTimer2 timer = mock(SimpleTimer2.class);
        ConnectionHandler handler = handler(manager, timer);
        handler.setActive(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(manager.wasRecentlyClosed(anyLong())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return false;
        });
        FutureTask<Void> receive = new FutureTask<Void>(() -> { handler.receiveNewSyn(mock(Packet.class)); return null; });
        Thread thread = new Thread(receive, "syn-during-close");
        thread.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            handler.setActive(false);
            handler.setActive(true);
            release.countDown();
            receive.get(2, TimeUnit.SECONDS);
            verify(timer, never()).addEvent(any(SimpleTimer.TimedEvent.class), anyLong());
        } finally {
            release.countDown();
            handler.setActive(false);
            thread.interrupt();
        }
    }

    @Test
    public void queueRemainsBoundedAndExpiryReleasesPackets() throws Exception {
        SimpleTimer2 timer = mock(SimpleTimer2.class);
        ConnectionHandler handler = handler(mock(ConnectionManager.class), timer);
        handler.setActive(true);
        List<Packet> packets = new ArrayList<Packet>();
        try {
            for (int i = 0; i < 65; i++) {
                Packet packet = mock(Packet.class);
                packets.add(packet);
                handler.receiveNewSyn(packet);
            }
            ArgumentCaptor<SimpleTimer.TimedEvent> events = ArgumentCaptor.forClass(SimpleTimer.TimedEvent.class);
            verify(timer, times(64)).addEvent(events.capture(), anyLong());
            for (SimpleTimer.TimedEvent event : events.getAllValues())
                event.timeReached();
            for (int i = 0; i < 64; i++)
                verify(packets.get(i)).releasePayload();
            handler.receiveNewSyn(mock(Packet.class));
            verify(timer, times(65)).addEvent(any(SimpleTimer.TimedEvent.class), anyLong());
        } finally {
            handler.setActive(false);
        }
    }

    private static ConnectionHandler handler(ConnectionManager manager, SimpleTimer2 timer) {
        return new ConnectionHandler(I2PAppContext.getGlobalContext(), manager, timer);
    }

    private static class Waiter {
        final FutureTask<Throwable> result;
        final Thread thread;
        Waiter(ConnectionHandler handler, long timeout) {
            result = new FutureTask<Throwable>(() -> {
                try {
                    handler.accept(timeout);
                    return new AssertionError("accept returned a connection");
                } catch (Throwable result) {
                    return result;
                }
            });
            thread = new Thread(result, "accept-shutdown-test");
            thread.setDaemon(true);
            thread.start();
        }
        void awaitBlocked() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                if (thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING)
                    return;
                if (result.isDone())
                    fail("accept stopped before shutdown: " + result.get());
                Thread.sleep(1);
            }
            fail("accept did not block");
        }
    }
}
