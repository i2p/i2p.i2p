package net.i2p.i2ptunnel.access;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AccessListReloadTest {
    private static final Hash HASH = new Hash(new byte[32]);

    @Test
    public void failedReadPreservesPreviousCache() throws Exception {
        File path = File.createTempFile("reload-cache", ".txt");
        ProbeFile file = new ProbeFile(path);
        try {
            write(path, true);
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(file, Threshold.DENY);
            element.update(new HashMap<Hash, DestTracker>());
            assertTrue(path.delete());
            file.modified = Long.MAX_VALUE;
            try {
                element.update(new HashMap<Hash, DestTracker>());
                fail("Read of missing file must fail");
            } catch (IOException expected) {}
            // The original version is restored, but opening the file is still
            // unavailable. The last successful snapshot must remain usable.
            file.modified = 1;
            Map<Hash, DestTracker> restored = new HashMap<Hash, DestTracker>();
            element.update(restored);
            assertTrue("Failed reload discarded the last valid cache", restored.containsKey(HASH));
        } finally {
            path.delete();
        }
    }

    @Test
    public void changedTimestampReloadsEvenWhenOlderThanWallClock() throws Exception {
        File path = File.createTempFile("reload-timestamp", ".txt");
        ProbeFile file = new ProbeFile(path);
        try {
            write(path, true);
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(file, Threshold.DENY);
            element.update(new HashMap<Hash, DestTracker>());
            write(path, false);
            file.modified = 2;
            Map<Hash, DestTracker> changed = new HashMap<Hash, DestTracker>();
            element.update(changed);
            assertTrue("Changed file was treated as unchanged", changed.isEmpty());
        } finally {
            path.delete();
        }
    }

    @Test
    public void shadowedEntryIsCachedForLaterUse() throws Exception {
        File path = File.createTempFile("reload-shadowed", ".txt");
        try {
            write(path, true);
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(new ProbeFile(path), Threshold.DENY);
            Map<Hash, DestTracker> first = new HashMap<Hash, DestTracker>();
            DestTracker earlier = new DestTracker(HASH, Threshold.ALLOW);
            first.put(HASH, earlier);
            element.update(first);
            assertSame(earlier, first.get(HASH));
            Map<Hash, DestTracker> next = new HashMap<Hash, DestTracker>();
            element.update(next);
            assertTrue(next.containsKey(HASH));
            assertTrue(next.get(HASH).recordAccess(100000));
        } finally {
            path.delete();
        }
    }

    @Test
    public void disappearingListKeepsCurrentPolicy() throws Exception {
        File path = File.createTempFile("reload-disappeared", ".txt");
        try {
            write(path, true);
            AccessFilter filter = filter(new FileFilterDefinitionElement(path, Threshold.DENY));
            assertFalse(filter.allowDestination(destination()));
            assertTrue(path.delete());
            try {
                reload(filter);
            } catch (IOException expected) {}
            assertFalse("A missing list must not silently remove its restrictions", filter.allowDestination(destination()));
        } finally {
            path.delete();
        }
    }

    @Test
    public void changedEffectiveThresholdReplacesOldPolicy() throws Exception {
        final AtomicBoolean firstRule = new AtomicBoolean(true);
        FilterDefinitionElement earlier = new FilterDefinitionElement(Threshold.ALLOW) {
            void update(Map<Hash, DestTracker> map) {
                if (firstRule.get())
                    map.put(HASH, new DestTracker(HASH, threshold));
            }
        };
        AccessFilter filter = filter(earlier, new ExplicitFilterDefinitionElement(HASH.toBase32(), Threshold.DENY));
        assertTrue(filter.allowDestination(destination()));
        firstRule.set(false);
        reload(filter);
        assertFalse("Reload left an obsolete allow rule active", filter.allowDestination(destination()));
    }

    @Test
    public void unchangedThresholdKeepsConnectionHistory() throws Exception {
        AccessFilter filter = filter(new ExplicitFilterDefinitionElement(HASH.toBase32(), new Threshold(3, 60)));
        assertTrue(filter.allowDestination(destination()));
        assertTrue(filter.allowDestination(destination()));
        reload(filter);
        assertFalse("Reload must not reset an unchanged rate limit", filter.allowDestination(destination()));
    }

    @Test
    public void initiallyMissingRecorderListIsAllowed() throws Exception {
        File directory = Files.createTempDirectory("reload-initial-missing").toFile();
        try {
            Map<Hash, DestTracker> map = new HashMap<Hash, DestTracker>();
            new FileFilterDefinitionElement(new File(directory, "recorder.txt"), Threshold.DENY).update(map);
            assertTrue(map.isEmpty());
        } finally {
            directory.delete();
        }
    }

    private static AccessFilter filter(FilterDefinitionElement... elements) throws IOException {
        return new AccessFilter(I2PAppContext.getGlobalContext(), new FilterDefinition(Threshold.ALLOW, elements, new Recorder[0]));
    }

    private static Destination destination() {
        Destination destination = mock(Destination.class);
        when(destination.getHash()).thenReturn(HASH);
        return destination;
    }

    private static void reload(AccessFilter filter) throws Exception {
        Method reload = AccessFilter.class.getDeclaredMethod("reload");
        reload.setAccessible(true);
        try {
            reload.invoke(filter);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void write(File path, boolean populated) throws Exception {
        Files.write(path.toPath(), (populated ? HASH.toBase32() + "\n" : "").getBytes("UTF-8"));
    }

    /** Simulate a stat/open race and filesystem timestamp changes deterministically. */
    private static class ProbeFile extends File {
        long modified = 1;
        ProbeFile(File file) { super(file.getPath()); }
        public boolean exists() { return true; }
        public boolean isFile() { return true; }
        public long lastModified() { return modified; }
    }
}
