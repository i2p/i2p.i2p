package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.*;
import net.i2p.I2PAppContext;
import net.i2p.util.Clock;
import net.i2p.util.SimpleTimer2;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public abstract class TaskSchedulerTestBase {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Spy protected I2PAppContext context = I2PAppContext.getGlobalContext();
    @Mock protected Clock clock;
    @Mock protected SimpleTimer2 timer;

    protected TaskScheduler scheduler;

    @Before
    public void setUp() {
        when(context.clock()).thenReturn(clock);
        when(context.simpleTimer2()).thenReturn(timer);

        scheduler = createScheduler();
    }

    protected abstract TaskScheduler createScheduler();

    @Test
    public void testAccept_null() {
        assertFalse(scheduler.accept(null));
    }
}
