package net.i2p.i2ptunnel.access;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Clock;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AccessFilterCounterTest {
    private static final Hash HASH = new Hash(new byte[32]);

    @Test
    public void floodHistoryIsBoundedAndExpires() throws Exception {
        DestTracker tracker = new DestTracker(HASH, new Threshold(4, 60));
        for (int i = 0; i < 200000; i++)
            assertEquals(i >= 3, tracker.recordAccess(1000000));
        assertTrue("Rejected attempts must not accumulate", retained(tracker) <= 4);
        assertTrue(tracker.purge(1300000));
        assertFalse(tracker.recordAccess(1300000));
    }

    @Test
    public void decisionsMatchSlidingWindowReference() throws Exception {
        Random random = new Random(0x12f1);
        for (int connections = 1; connections <= 23; connections++) {
            DestTracker tracker = new DestTracker(HASH, new Threshold(connections, 3));
            List<Long> recent = new ArrayList<Long>();
            long now = 100000;
            for (int i = 0; i < 3000; i++) {
                now += random.nextInt(1000);
                while (!recent.isEmpty() && recent.get(0) < now - 3000)
                    recent.remove(0);
                recent.add(now);
                assertEquals(recent.size() >= connections, tracker.recordAccess(now));
                if (i % 17 == 0)
                    tracker.purge(now - 3000);
            }
        }
    }

    @Test
    public void boundaryIsInclusive() {
        DestTracker tracker = new DestTracker(HASH, new Threshold(2, 1));
        assertFalse(tracker.recordAccess(10000));
        assertTrue(tracker.recordAccess(11000));
        assertFalse(tracker.recordAccess(12001));
    }

    @Test
    public void longWindowsDoNotOverflowMilliseconds() {
        DestTracker tracker = new DestTracker(HASH, new Threshold(2, 3000000));
        assertFalse(tracker.recordAccess(47000000000L));
        assertTrue(tracker.recordAccess(50000000000L));
    }

    @Test
    public void longWindowsSurviveFilterPurge() throws Exception {
        AccessFilter filter = filter(context(50000000000L), new Threshold(2, 3000000), new Recorder[0]);
        assertTrue(filter.allowDestination(destination()));
        invoke(filter, "purge");
        assertFalse(filter.allowDestination(destination()));
    }

    @Test
    public void allowAndDenyDefaultsNeedNoUnknownHistory() throws Exception {
        for (Threshold threshold : new Threshold[] { Threshold.ALLOW, Threshold.DENY }) {
            AccessFilter filter = filter(context(100000), threshold, new Recorder[0]);
            assertEquals(threshold == Threshold.ALLOW, filter.allowDestination(destination()));
            assertTrue(((Map<?, ?>) field(filter, "unknownDests")).isEmpty());
        }
    }

    @Test
    public void explicitRuleOverridesStaticDefault() throws Exception {
        for (Threshold threshold : new Threshold[] { Threshold.ALLOW, Threshold.DENY }) {
            Threshold explicit = threshold == Threshold.ALLOW ? Threshold.DENY : Threshold.ALLOW;
            FilterDefinition definition = new FilterDefinition(threshold,
                new FilterDefinitionElement[] { new ExplicitFilterDefinitionElement(HASH.toBase32(), explicit) },
                new Recorder[0]);
            AccessFilter filter = new AccessFilter(context(100000), definition);
            assertEquals(explicit == Threshold.ALLOW, filter.allowDestination(destination()));
        }
    }

    @Test
    public void recorderRetainsMoreAttemptsThanAdmissionThreshold() throws Exception {
        File file = File.createTempFile("access-recorder", ".txt");
        try {
            AccessFilter filter = filter(context(100000), new Threshold(2, 60),
                new Recorder[] { new Recorder(file, new Threshold(5, 60)) });
            for (int i = 0; i < 4; i++)
                assertEquals(i == 0, filter.allowDestination(destination()));
            invoke(filter, "record");
            assertEquals(0, file.length());
            assertFalse(filter.allowDestination(destination()));
            invoke(filter, "record");
            assertEquals(HASH.toBase32(), new String(Files.readAllBytes(file.toPath()), "UTF-8").trim());
            DestTracker tracker = ((Map<Hash, DestTracker>) field(filter, "unknownDests")).get(HASH);
            assertTrue(retained(tracker) <= 5);
        } finally {
            file.delete();
        }
    }

    @Test
    public void recorderWorksWithUnlimitedAdmission() throws Exception {
        File file = File.createTempFile("access-unlimited", ".txt");
        try {
            AccessFilter filter = filter(context(100000), Threshold.ALLOW,
                new Recorder[] { new Recorder(file, new Threshold(3, 60)) });
            for (int i = 0; i < 1000; i++)
                assertTrue(filter.allowDestination(destination()));
            invoke(filter, "record");
            assertEquals(HASH.toBase32(), new String(Files.readAllBytes(file.toPath()), "UTF-8").trim());
            DestTracker tracker = ((Map<Hash, DestTracker>) field(filter, "unknownDests")).get(HASH);
            assertTrue(retained(tracker) <= 3);
        } finally {
            file.delete();
        }
    }

    private static I2PAppContext context(long now) {
        I2PAppContext context = mock(I2PAppContext.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(now);
        when(context.clock()).thenReturn(clock);
        return context;
    }

    private static Destination destination() {
        Destination destination = mock(Destination.class);
        when(destination.getHash()).thenReturn(HASH);
        return destination;
    }

    private static AccessFilter filter(I2PAppContext context, Threshold threshold, Recorder[] recorders) throws Exception {
        return new AccessFilter(context, new FilterDefinition(threshold, new FilterDefinitionElement[0], recorders));
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static int retained(DestTracker tracker) throws Exception {
        Object counter = field(tracker, "counter");
        Object accesses = field(counter, "accesses");
        return accesses instanceof List ? ((List<?>) accesses).size() : (Integer) field(counter, "size");
    }

    private static void invoke(AccessFilter filter, String name) throws Exception {
        Method method = AccessFilter.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(filter);
    }
}
